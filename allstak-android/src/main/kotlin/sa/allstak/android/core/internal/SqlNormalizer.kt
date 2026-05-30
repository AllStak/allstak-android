package sa.allstak.android.core.internal

import java.security.MessageDigest

/**
 * Normalizes SQL for the DB-query telemetry: literal values become `?`,
 * whitespace collapses, and a short stable hash groups identical query shapes.
 * Mirrors the JVM SDK so query fingerprints match across platforms.
 */
object SqlNormalizer {

    private val STRING_LITERAL = Regex("'[^']*'")
    private val NUMERIC_LITERAL = Regex("\\b\\d+(\\.\\d+)?\\b")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(sql: String?): String? {
        if (sql.isNullOrEmpty()) return sql
        var result = STRING_LITERAL.replace(sql, "?")
        result = NUMERIC_LITERAL.replace(result, "?")
        result = WHITESPACE.replace(result.trim(), " ")
        return result
    }

    fun hash(normalized: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(normalized.toByteArray())
            val sb = StringBuilder()
            for (i in 0 until 8) sb.append(String.format("%02x", digest[i]))
            sb.toString()
        } catch (e: Exception) {
            Integer.toHexString(normalized.hashCode())
        }
    }

    fun detectQueryType(sql: String?): String {
        if (sql.isNullOrEmpty()) return "OTHER"
        val first = sql.trim().split(WHITESPACE)[0].uppercase()
        return when (first) {
            "SELECT", "INSERT", "UPDATE", "DELETE" -> first
            else -> "OTHER"
        }
    }
}
