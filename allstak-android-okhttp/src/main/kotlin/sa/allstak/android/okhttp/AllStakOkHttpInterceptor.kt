package sa.allstak.android.okhttp

import okhttp3.Interceptor
import okhttp3.Response
import sa.allstak.android.AllStak
import sa.allstak.android.core.internal.TraceIds
import sa.allstak.android.core.model.HttpRequestItem
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * OkHttp [Interceptor] that, per call, emits an AllStak HTTP-request record + a
 * client span + a breadcrumb, and injects trace-propagation headers
 * (`x-allstak-trace-id`, `x-allstak-span-id`, and the W3C `traceparent` sampled
 * flag) on outbound requests — gated by the trace-propagation allowlist so
 * trace context never leaks to third-party hosts.
 *
 * Register on any client you want instrumented:
 * ```
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(AllStakOkHttpInterceptor())
 *     .build()
 * ```
 *
 * Degrades to a passthrough when the SDK is not initialized.
 */
class AllStakOkHttpInterceptor : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val client = AllStak.client

        val startNanos = System.nanoTime()
        val startEpoch = System.currentTimeMillis()
        val url = request.url.toString()
        val method = request.method
        val host = request.url.host
        val path = request.url.encodedPath

        val traceId = TraceIds.newTraceId()
        val spanId = TraceIds.newSpanId()

        var enriched = request
        if (client != null && client.shouldPropagateTrace(url)) {
            enriched = request.newBuilder()
                .header(HEADER_TRACE_ID, traceId)
                .header(HEADER_SPAN_ID, spanId)
                .header(HEADER_TRACEPARENT, TraceIds.traceparent(traceId, spanId, client.traceparentSampledFlag()))
                .build()
        }

        var response: Response? = null
        var failure: Throwable? = null
        try {
            response = chain.proceed(enriched)
            return response
        } catch (t: IOException) {
            failure = t
            throw t
        } catch (t: RuntimeException) {
            failure = t
            throw t
        } finally {
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
            val status = response?.code ?: 0
            val requestSize = enriched.body?.contentLength()?.coerceAtLeast(0) ?: 0
            val responseSize = response?.body?.contentLength()?.coerceAtLeast(0) ?: 0

            // Breadcrumb summarising the call.
            val data = linkedMapOf<String, Any?>(
                "method" to method,
                "host" to host,
                "status_code" to status,
                "duration_ms" to durationMs,
            )
            failure?.let { data["error"] = it.javaClass.simpleName }
            AllStak.addBreadcrumb("http", "$method $host", levelFor(status, failure), data)

            // HTTP-request record.
            AllStak.captureHttpRequest(
                HttpRequestItem(
                    traceId = traceId,
                    requestId = UUID.randomUUID().toString(),
                    spanId = spanId,
                    direction = "outbound",
                    method = method,
                    host = host,
                    path = path,
                    statusCode = status,
                    durationMs = durationMs,
                    requestSize = requestSize,
                    responseSize = responseSize,
                    timestamp = isoTimestamp(startEpoch),
                )
            )

            // Client span — fire-and-forget.
            client?.let {
                try {
                    it.captureSpan(
                        traceId = traceId,
                        spanId = spanId,
                        parentSpanId = null,
                        operation = "http.client",
                        description = "$method $url",
                        status = if (failure == null && status in 1..399) "ok" else "error",
                        durationMs = durationMs,
                        startTimeMillis = startEpoch,
                        endTimeMillis = System.currentTimeMillis(),
                        service = "okhttp",
                    )
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private fun levelFor(status: Int, failure: Throwable?): String = when {
        failure != null -> "error"
        status >= 500 -> "error"
        status >= 400 -> "warn"
        else -> "info"
    }

    // API-21-safe ISO-8601 UTC timestamp (java.time requires API 26).
    private fun isoTimestamp(epochMillis: Long): String =
        ISO_FORMAT.get()!!.format(Date(epochMillis))

    companion object {
        const val HEADER_TRACE_ID = "x-allstak-trace-id"
        const val HEADER_SPAN_ID = "x-allstak-span-id"
        const val HEADER_TRACEPARENT = "traceparent"

        private val ISO_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }
    }
}
