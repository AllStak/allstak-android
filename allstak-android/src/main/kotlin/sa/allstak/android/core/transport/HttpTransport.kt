package sa.allstak.android.core.transport

import sa.allstak.android.core.internal.AllStakVersion
import sa.allstak.android.core.internal.Json
import sa.allstak.android.core.internal.SdkLogger
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The low-level HTTP send. Abstracted so JVM unit tests can inject a fake
 * connection layer without a device or real network.
 */
interface HttpSender {
    /** Returns the raw HTTP status code, or -1 on a network/IO failure. */
    fun post(url: String, apiKey: String, body: String): HttpSendOutcome
}

/** Status code plus an optional `Retry-After` header value. */
data class HttpSendOutcome(val statusCode: Int, val retryAfter: String? = null)

/**
 * Default sender backed by [HttpURLConnection] — works from API 21 up, no
 * extra dependency. Short connect/read timeouts keep telemetry off the
 * critical path.
 */
class UrlConnectionSender : HttpSender {
    override fun post(url: String, apiKey: String, body: String): HttpSendOutcome {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = REQUEST_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-AllStak-Key", apiKey)
                setRequestProperty("User-Agent", AllStakVersion.userAgent())
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            // Drain the body so the connection can be pooled/reused.
            val stream = if (status in 200..399) conn.inputStream else conn.errorStream
            stream?.use { s -> BufferedReader(s.reader()).use { it.readText() } }
            HttpSendOutcome(status, conn.getHeaderField("Retry-After"))
        } catch (e: Throwable) {
            SdkLogger.debug("Network error: ${e.message}")
            HttpSendOutcome(-1)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val REQUEST_TIMEOUT_MS = 5_000
    }
}

/**
 * HTTP transport mirroring the JVM SDK: serialize -> POST with retries +
 * truncated exponential backoff -> honor Retry-After -> disable on 401.
 *
 * The wire form is byte-identical to the JVM SDK: camelCase keys, null fields
 * omitted, `Content-Type: application/json`, `X-AllStak-Key` auth header,
 * `User-Agent: allstak-android/<version>`.
 */
class HttpTransport(
    private val baseUrl: String,
    private val apiKey: String,
    private val sender: HttpSender = UrlConnectionSender(),
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
) {

    @Volatile
    var isDisabled: Boolean = false
        private set

    /** Serialize a payload map and send. Returns true on 2xx. */
    fun send(path: String, payload: Map<String, Any?>): Boolean =
        sendWithResult(path, payload).isAccepted

    fun sendWithResult(path: String, payload: Map<String, Any?>): SendResult {
        if (isDisabled) {
            SdkLogger.debug("SDK disabled (401) — dropping event for $path")
            return SendResult.PERMANENT
        }
        val wire = try {
            Json.encodeObject(payload)
        } catch (e: Throwable) {
            SdkLogger.debug("Failed to serialize payload for $path: ${e.message}")
            return SendResult.PERMANENT
        }
        return sendJson(path, wire)
    }

    /** Replay a pre-serialized (already scrubbed) JSON body byte-for-byte. */
    fun sendRawJson(path: String, wireJson: String?): SendResult {
        if (isDisabled) return SendResult.PERMANENT
        if (wireJson == null) return SendResult.PERMANENT
        return sendJson(path, wireJson)
    }

    private fun sendJson(path: String, wireJson: String): SendResult {
        SdkLogger.debug("Sending to $baseUrl$path: event_bytes=${wireJson.length}")
        var retryAfterOverrideMs = -1L

        for (attempt in 0 until RetryPolicy.maxAttempts()) {
            val delay =
                if (retryAfterOverrideMs >= 0) retryAfterOverrideMs
                else RetryPolicy.delayForAttempt(attempt)
            retryAfterOverrideMs = -1
            if (delay > 0) {
                try {
                    sleeper(delay)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return SendResult.TRANSIENT
                }
            }

            val outcome = sender.post(baseUrl + path, apiKey, wireJson)
            val status = outcome.statusCode

            if (status == 202) return SendResult.ACCEPTED

            if (status == -1) {
                // Network/IO error — retry.
                SdkLogger.debug("Network error for $path — attempt ${attempt + 1}/${RetryPolicy.maxAttempts()}")
                continue
            }

            if (RetryPolicy.isAuthError(status)) {
                SdkLogger.warn("Invalid API key — disabling SDK")
                isDisabled = true
                return SendResult.PERMANENT
            }

            if (RetryPolicy.isClientError(status)) {
                SdkLogger.debug("Client error $status for $path — dropping event")
                return SendResult.PERMANENT
            }

            if (RetryPolicy.isRetryable(status)) {
                val ra = RetryPolicy.parseRetryAfterSeconds(outcome.retryAfter)
                if (ra > 0) {
                    retryAfterOverrideMs = ra * 1000
                    SdkLogger.debug("Retryable $status for $path — honoring Retry-After ${retryAfterOverrideMs}ms")
                    continue
                }
                SdkLogger.debug("Retryable $status for $path — attempt ${attempt + 1}/${RetryPolicy.maxAttempts()}")
                continue
            }

            // 2xx other than 202, or an unknown status — don't retry.
            if (status in 200..299) return SendResult.ACCEPTED
            SdkLogger.debug("Unexpected status $status for $path — dropping event")
            return SendResult.PERMANENT
        }

        SdkLogger.debug("All retry attempts exhausted for $path — transient")
        return SendResult.TRANSIENT
    }
}
