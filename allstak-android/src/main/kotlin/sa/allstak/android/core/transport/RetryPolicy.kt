package sa.allstak.android.core.transport

import java.util.concurrent.ThreadLocalRandom

/**
 * Truncated exponential backoff with jitter. Mirrors the JVM SDK exactly:
 * immediate, 1s, 2s, 4s, 8s base delays (5 attempts total), +/- up to 500ms
 * jitter. Retries 5xx and 429; never retries 400/401/403/422.
 */
object RetryPolicy {

    private const val MAX_ATTEMPTS = 5
    private val BASE_DELAYS_MS = longArrayOf(0, 1000, 2000, 4000, 8000)
    private const val MAX_JITTER_MS = 500L

    fun maxAttempts(): Int = MAX_ATTEMPTS

    fun delayForAttempt(attempt: Int): Long {
        if (attempt < 0 || attempt >= MAX_ATTEMPTS) return 0
        val base = BASE_DELAYS_MS[attempt]
        if (base == 0L) return 0
        val jitter = ThreadLocalRandom.current().nextLong(0, MAX_JITTER_MS + 1)
        return base + jitter
    }

    fun isRetryable(statusCode: Int): Boolean {
        if (statusCode == 429) return true
        return statusCode >= 500
    }

    fun isClientError(statusCode: Int): Boolean =
        statusCode == 400 || statusCode == 401 || statusCode == 403 || statusCode == 422

    fun isAuthError(statusCode: Int): Boolean = statusCode == 401

    /** Parse a `Retry-After` delta-seconds header; returns 0 on anything else. */
    fun parseRetryAfterSeconds(header: String?): Long {
        if (header == null) return 0
        val trimmed = header.trim()
        if (trimmed.isEmpty()) return 0
        return try {
            val seconds = trimmed.toLong()
            if (seconds < 0) 0 else minOf(seconds, 300)
        } catch (e: NumberFormatException) {
            0
        }
    }
}
