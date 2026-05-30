package sa.allstak.android.core.internal

import java.security.SecureRandom
import java.util.Locale

/**
 * W3C trace-context ID helpers.
 *
 * Wire IDs are always lowercase hex:
 * - traceId: 32 chars, not all zero
 * - spanId: 16 chars, not all zero
 */
object TraceIds {
    private val random = SecureRandom()
    private val hexChars = "0123456789abcdef".toCharArray()

    fun newTraceId(): String = randomHex(32)

    fun newSpanId(): String = randomHex(16)

    fun normalizeTraceId(value: String?): String? =
        normalizeHex(value, 32)

    fun normalizeSpanId(value: String?): String? =
        normalizeHex(value, 16)

    fun traceparent(traceId: String?, spanId: String?, sampledFlag: String): String {
        val normalizedTraceId = normalizeTraceId(traceId) ?: newTraceId()
        val normalizedSpanId = normalizeSpanId(spanId) ?: newSpanId()
        val flag = if (sampledFlag.lowercase(Locale.US) == "01") "01" else "00"
        return "00-$normalizedTraceId-$normalizedSpanId-$flag"
    }

    private fun normalizeHex(value: String?, width: Int): String? {
        val compact = value
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace("-", "")
            ?: return null
        if (compact.length != width) return null
        if (compact.all { it == '0' }) return null
        if (compact.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return compact
    }

    private fun randomHex(width: Int): String {
        while (true) {
            val bytes = ByteArray(width / 2)
            random.nextBytes(bytes)
            val out = CharArray(width)
            var i = 0
            for (b in bytes) {
                val v = b.toInt() and 0xff
                out[i++] = hexChars[v ushr 4]
                out[i++] = hexChars[v and 0x0f]
            }
            val id = String(out)
            if (id.any { it != '0' }) return id
        }
    }
}
