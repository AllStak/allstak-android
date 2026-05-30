package sa.allstak.android.core.masking

import java.util.regex.Pattern

/**
 * Scrubs sensitive data before it leaves the SDK. Mirrors the JVM SDK's
 * `DataMasker` so the privacy posture is identical across platforms.
 *
 * Two layers compose:
 *  1. Key-name redaction — keys whose name looks sensitive
 *     (password/token/cookie/...) are blanked regardless of value.
 *  2. Value-pattern scrubbing — PII that leaks into free-text values is
 *     matched by shape and replaced with [REDACTED]:
 *       - Always: Luhn-valid credit-card numbers with a known issuer prefix,
 *         and hyphenated US SSNs.
 *       - Unless `sendDefaultPii`: email addresses and validated IPv4 literals.
 *
 * Every entry point is fail-open: a scrubber error returns the input unchanged.
 */
object DataMasker {

    private const val MASKED = "[MASKED]"
    private const val REDACTED = "[REDACTED]"

    private const val MAX_SCAN_CHARS = 64 * 1024
    private const val MAX_METADATA_DEPTH = 8

    private val CREDIT_CARD_CANDIDATE: Pattern =
        Pattern.compile("(?<![\\d])(?:\\d[ -]?){12,18}\\d(?![\\d])")
    private val SSN: Pattern = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b")
    private val EMAIL: Pattern =
        Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
    private val IPV4: Pattern = Pattern.compile(
        "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b"
    )

    private val SENSITIVE_METADATA_KEYS: Set<String> = setOf(
        "password", "secret", "token", "key", "authorization",
        "creditcard", "credit_card", "cardnumber", "card_number",
        "cvv", "ssn", "api_key", "apikey", "email", "phone",
        "phonenumber", "phone_number", "mobile", "nationalid",
        "national_id", "idnumber", "id_number", "otp", "otpcode",
        "otp_code", "passcode", "pin", "access_token", "refreshtoken",
        "refresh_token", "id_token", "jwt", "cookie", "set_cookie",
        "set-cookie", "iban", "pan", "cvc"
    )

    private val SENSITIVE_HEADERS: Set<String> = setOf(
        "authorization", "cookie", "x-allstak-key", "x-api-key", "x-auth-token"
    )

    /** Key-name redaction only. */
    fun maskMetadata(metadata: Map<String, Any?>?): Map<String, Any?>? {
        if (metadata.isNullOrEmpty()) return metadata
        val result = LinkedHashMap<String, Any?>(metadata.size)
        for ((k, v) in metadata) {
            if (k.lowercase() in SENSITIVE_METADATA_KEYS) result[k] = MASKED else result[k] = v
        }
        return result
    }

    /** Both layers: sensitive-key redaction and value-pattern PII scrubbing. */
    fun maskMetadata(metadata: Map<String, Any?>?, sendDefaultPii: Boolean): Map<String, Any?>? {
        if (metadata.isNullOrEmpty()) return metadata
        return try {
            maskMetadataMap(metadata, sendDefaultPii, 0)
        } catch (t: Throwable) {
            try {
                maskMetadata(metadata)
            } catch (ignored: Throwable) {
                metadata
            }
        }
    }

    private fun maskMetadataMap(
        metadata: Map<String, Any?>,
        sendDefaultPii: Boolean,
        depth: Int,
    ): LinkedHashMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>(metadata.size)
        for ((key, value) in metadata) {
            if (key.lowercase() in SENSITIVE_METADATA_KEYS) {
                result[key] = MASKED
            } else {
                result[key] = scrubMetadataValue(value, sendDefaultPii, depth)
            }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun scrubMetadataValue(value: Any?, sendDefaultPii: Boolean, depth: Int): Any? {
        if (value == null) return null
        if (value is String) return scrubValue(value, sendDefaultPii)
        if (depth >= MAX_METADATA_DEPTH) return value
        if (value is Map<*, *>) {
            return maskMetadataMap(value as Map<String, Any?>, sendDefaultPii, depth + 1)
        }
        if (value is List<*>) {
            return value.map { scrubMetadataValue(it, sendDefaultPii, depth + 1) }
        }
        return value
    }

    /**
     * Scrub PII patterns from a single free-text value. Always redacts
     * Luhn-valid cards + hyphenated SSNs; redacts email + IPv4 only when
     * [sendDefaultPii] is false. Fail-open.
     */
    fun scrubValue(value: String?, sendDefaultPii: Boolean): String? {
        if (value.isNullOrEmpty()) return value
        if (value.length > MAX_SCAN_CHARS) return value
        return try {
            var out = scrubCreditCards(value)
            out = SSN.matcher(out).replaceAll(REDACTED)
            if (!sendDefaultPii) {
                out = EMAIL.matcher(out).replaceAll(REDACTED)
                out = IPV4.matcher(out).replaceAll(REDACTED)
            }
            out
        } catch (t: Throwable) {
            value
        }
    }

    private fun scrubCreditCards(input: String): String {
        val m = CREDIT_CARD_CANDIDATE.matcher(input)
        var sb: StringBuffer? = null
        while (m.find()) {
            val candidate = m.group()
            val digits = candidate.replace(Regex("[ -]"), "")
            if (isCardLength(digits) && hasCardIin(digits) && isLuhnValid(candidate)) {
                if (sb == null) sb = StringBuffer(input.length)
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(REDACTED))
            }
        }
        if (sb == null) return input
        m.appendTail(sb)
        return sb.toString()
    }

    private fun isCardLength(digits: String): Boolean = digits.length in 13..19

    private fun hasCardIin(d: String): Boolean {
        if (d.startsWith("4")) return true
        if (d.startsWith("34") || d.startsWith("37")) return true
        if (d.startsWith("36") || d.startsWith("38") || d.startsWith("39")) return true
        if (between(d, 3, "300", "305")) return true
        if (between(d, 4, "3528", "3589")) return true
        if (between(d, 2, "51", "55")) return true
        if (between(d, 4, "2221", "2720")) return true
        if (d.startsWith("6011") || d.startsWith("65")) return true
        if (between(d, 3, "644", "649")) return true
        if (d.startsWith("62")) return true
        return false
    }

    private fun between(d: String, len: Int, lo: String, hi: String): Boolean {
        if (d.length < len) return false
        val head = d.substring(0, len)
        return head >= lo && head <= hi
    }

    fun isLuhnValid(candidate: String): Boolean {
        var sum = 0
        var digits = 0
        var alternate = false
        for (i in candidate.length - 1 downTo 0) {
            val c = candidate[i]
            if (c == ' ' || c == '-') continue
            if (c < '0' || c > '9') return false
            var d = c - '0'
            digits++
            if (alternate) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alternate = !alternate
        }
        if (digits < 13 || digits > 19) return false
        return sum % 10 == 0
    }

    /** Strip the query string from a URL path so secrets in params never ship. */
    fun stripSensitiveQueryParams(path: String?): String? {
        if (path == null) return null
        val q = path.indexOf('?')
        if (q < 0) return path
        return path.substring(0, q)
    }

    fun isSensitiveHeader(name: String?): Boolean {
        if (name == null) return false
        return name.lowercase() in SENSITIVE_HEADERS
    }
}
