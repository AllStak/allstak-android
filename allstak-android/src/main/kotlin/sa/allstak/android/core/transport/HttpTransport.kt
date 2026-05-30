package sa.allstak.android.core.transport

import sa.allstak.android.core.internal.AllStakVersion
import sa.allstak.android.core.internal.Json
import sa.allstak.android.core.internal.SdkLogger
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/**
 * The low-level HTTP send. Abstracted so JVM unit tests can inject a fake
 * connection layer without a device or real network.
 */
interface HttpSender {
    /** Returns the raw HTTP status code, or -1 on a network/IO failure. */
    fun post(url: String, apiKey: String, body: String): HttpSendOutcome

    fun post(url: String, apiKey: String, body: ByteArray, contentEncoding: String?): HttpSendOutcome =
        post(url, apiKey, body.toString(Charsets.UTF_8))
}

/** Status code plus an optional `Retry-After` header value. */
data class HttpSendOutcome(val statusCode: Int, val retryAfter: String? = null)

/**
 * Default sender backed by [HttpURLConnection] — works from API 21 up, no
 * extra dependency. Short connect/read timeouts keep telemetry off the
 * critical path.
 */
class UrlConnectionSender : HttpSender {
    override fun post(url: String, apiKey: String, body: String): HttpSendOutcome =
        post(url, apiKey, body.toByteArray(Charsets.UTF_8), contentEncoding = null)

    override fun post(url: String, apiKey: String, body: ByteArray, contentEncoding: String?): HttpSendOutcome {
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
                if (contentEncoding != null) {
                    setRequestProperty("Content-Encoding", contentEncoding)
                }
            }
            conn.outputStream.use { it.write(body) }
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

    private val eventsCaptured = AtomicLong(0)
    private val eventsSent = AtomicLong(0)
    private val eventsFailed = AtomicLong(0)
    private val eventsDropped = AtomicLong(0)
    private val eventsPersisted = AtomicLong(0)
    private val eventsReplayed = AtomicLong(0)
    private val retryAttempts = AtomicLong(0)
    private val rateLimitedCount = AtomicLong(0)
    private val compressedPayloads = AtomicLong(0)
    private val uncompressedPayloads = AtomicLong(0)
    private val compressionBytesSaved = AtomicLong(0)

    /** Serialize a payload map and send. Returns true on 2xx. */
    fun send(path: String, payload: Map<String, Any?>): Boolean =
        sendWithResult(path, payload).isAccepted

    fun sendWithResult(path: String, payload: Map<String, Any?>): SendResult {
        eventsCaptured.incrementAndGet()
        if (isDisabled) {
            SdkLogger.debug("SDK disabled (401) — dropping event for $path")
            eventsDropped.incrementAndGet()
            return SendResult.PERMANENT
        }
        val wire = try {
            Json.encodeObject(payload)
        } catch (e: Throwable) {
            SdkLogger.debug("Failed to serialize payload for $path: ${e.message}")
            eventsFailed.incrementAndGet()
            eventsDropped.incrementAndGet()
            return SendResult.PERMANENT
        }
        return sendJson(path, wire, replay = false)
    }

    /** Replay a pre-serialized (already scrubbed) JSON body byte-for-byte. */
    fun sendRawJson(path: String, wireJson: String?): SendResult {
        if (isDisabled) {
            eventsDropped.incrementAndGet()
            return SendResult.PERMANENT
        }
        if (wireJson == null) {
            eventsDropped.incrementAndGet()
            return SendResult.PERMANENT
        }
        return sendJson(path, wireJson, replay = true)
    }

    internal fun recordPersisted() {
        eventsPersisted.incrementAndGet()
    }

    internal fun recordDropped() {
        eventsDropped.incrementAndGet()
    }

    fun stats(): TransportStats = TransportStats(
        eventsCaptured = eventsCaptured.get(),
        eventsSent = eventsSent.get(),
        eventsFailed = eventsFailed.get(),
        eventsDropped = eventsDropped.get(),
        eventsPersisted = eventsPersisted.get(),
        eventsReplayed = eventsReplayed.get(),
        retryAttempts = retryAttempts.get(),
        rateLimitedCount = rateLimitedCount.get(),
        compressedPayloads = compressedPayloads.get(),
        uncompressedPayloads = uncompressedPayloads.get(),
        compressionBytesSaved = compressionBytesSaved.get(),
        disabled = isDisabled,
    )

    private fun sendJson(path: String, wireJson: String, replay: Boolean): SendResult {
        SdkLogger.debug("Sending to $baseUrl$path: event_bytes=${wireJson.length}")
        val prepared = prepareRequestBody(wireJson)
        if (prepared.compressed) {
            compressedPayloads.incrementAndGet()
            compressionBytesSaved.addAndGet(prepared.bytesSaved.toLong())
        } else {
            uncompressedPayloads.incrementAndGet()
        }
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

            val outcome = sender.post(
                baseUrl + path,
                apiKey,
                prepared.body,
                if (prepared.compressed) "gzip" else null,
            )
            val status = outcome.statusCode

            if (status == 202) {
                eventsSent.incrementAndGet()
                if (replay) eventsReplayed.incrementAndGet()
                return SendResult.ACCEPTED
            }

            if (status == -1) {
                // Network/IO error — retry.
                SdkLogger.debug("Network error for $path — attempt ${attempt + 1}/${RetryPolicy.maxAttempts()}")
                if (attempt < RetryPolicy.maxAttempts() - 1) retryAttempts.incrementAndGet()
                continue
            }

            if (RetryPolicy.isAuthError(status)) {
                SdkLogger.warn("Invalid API key — disabling SDK")
                isDisabled = true
                eventsFailed.incrementAndGet()
                eventsDropped.incrementAndGet()
                return SendResult.PERMANENT
            }

            if (RetryPolicy.isClientError(status)) {
                SdkLogger.debug("Client error $status for $path — dropping event")
                eventsFailed.incrementAndGet()
                eventsDropped.incrementAndGet()
                return SendResult.PERMANENT
            }

            if (RetryPolicy.isRetryable(status)) {
                if (status == 429) rateLimitedCount.incrementAndGet()
                val ra = RetryPolicy.parseRetryAfterSeconds(outcome.retryAfter)
                if (ra > 0) {
                    retryAfterOverrideMs = ra * 1000
                    SdkLogger.debug("Retryable $status for $path — honoring Retry-After ${retryAfterOverrideMs}ms")
                    if (attempt < RetryPolicy.maxAttempts() - 1) retryAttempts.incrementAndGet()
                    continue
                }
                SdkLogger.debug("Retryable $status for $path — attempt ${attempt + 1}/${RetryPolicy.maxAttempts()}")
                if (attempt < RetryPolicy.maxAttempts() - 1) retryAttempts.incrementAndGet()
                continue
            }

            // 2xx other than 202, or an unknown status — don't retry.
            if (status in 200..299) {
                eventsSent.incrementAndGet()
                if (replay) eventsReplayed.incrementAndGet()
                return SendResult.ACCEPTED
            }
            SdkLogger.debug("Unexpected status $status for $path — dropping event")
            eventsFailed.incrementAndGet()
            eventsDropped.incrementAndGet()
            return SendResult.PERMANENT
        }

        SdkLogger.debug("All retry attempts exhausted for $path — transient")
        eventsFailed.incrementAndGet()
        return SendResult.TRANSIENT
    }

    private fun prepareRequestBody(wireJson: String): PreparedBody {
        val raw = wireJson.toByteArray(Charsets.UTF_8)
        if (raw.size < COMPRESSION_THRESHOLD_BYTES) return PreparedBody(raw, false, 0)
        return try {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(raw) }
            val compressed = out.toByteArray()
            if (compressed.size >= raw.size) PreparedBody(raw, false, 0)
            else PreparedBody(compressed, true, raw.size - compressed.size)
        } catch (_: Throwable) {
            PreparedBody(raw, false, 0)
        }
    }

    private data class PreparedBody(
        val body: ByteArray,
        val compressed: Boolean,
        val bytesSaved: Int,
    )

    private companion object {
        const val COMPRESSION_THRESHOLD_BYTES = 1024
    }
}

data class TransportStats(
    val eventsCaptured: Long,
    val eventsSent: Long,
    val eventsFailed: Long,
    val eventsDropped: Long,
    val eventsPersisted: Long,
    val eventsReplayed: Long,
    val retryAttempts: Long,
    val rateLimitedCount: Long,
    val compressedPayloads: Long,
    val uncompressedPayloads: Long,
    val compressionBytesSaved: Long,
    val disabled: Boolean,
)
