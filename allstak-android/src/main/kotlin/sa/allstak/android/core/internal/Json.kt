package sa.allstak.android.core.internal

/**
 * Minimal, allocation-light JSON encoder.
 *
 * The platform ingest contract is camelCase with null fields omitted
 * (the reference JVM models use `@JsonInclude(NON_NULL)`). This encoder
 * reproduces that exact shape so an Android payload is byte-identical to
 * the JVM one: insertion-ordered keys, omitted nulls, RFC 8259 string
 * escaping, and `Long`/`Int`/`Double`/`Boolean` rendered as bare JSON
 * scalars.
 *
 * Maps must use `LinkedHashMap` (or any insertion-ordered map) at the call
 * site to keep key order stable. Values may be:
 *   - null            -> the entry is OMITTED entirely
 *   - String          -> escaped JSON string
 *   - Boolean         -> true / false
 *   - Int/Long/Short  -> integer literal
 *   - Float/Double    -> number literal
 *   - Map<*, *>       -> nested object (recursively, omitting null values)
 *   - List<*>/Array   -> JSON array (null elements become `null`)
 *   - JsonValue       -> pre-encoded raw literal (escape hatch)
 *
 * No external dependency, no reflection — deterministic output.
 */
internal object Json {

    /** Wraps an already-encoded JSON fragment so it is emitted verbatim. */
    class Raw(val literal: String)

    fun encode(value: Any?): String {
        val sb = StringBuilder(256)
        writeValue(sb, value)
        return sb.toString()
    }

    /**
     * Encodes a map as a JSON object, omitting entries whose value is null —
     * this is the `NON_NULL` parity behavior for the top-level payloads.
     */
    fun encodeObject(map: Map<String, Any?>): String {
        val sb = StringBuilder(256)
        writeObject(sb, map)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Raw -> sb.append(value.literal)
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> writeDouble(sb, value)
            is Float -> writeDouble(sb, value.toDouble())
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                writeObject(sb, value as Map<String, Any?>)
            }
            is Iterable<*> -> writeArray(sb, value)
            is Array<*> -> writeArray(sb, value.asIterable())
            is IntArray -> writeArray(sb, value.asIterable())
            is LongArray -> writeArray(sb, value.asIterable())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeObject(sb: StringBuilder, map: Map<String, Any?>) {
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (v == null) continue // NON_NULL parity
            if (!first) sb.append(',')
            first = false
            writeString(sb, k)
            sb.append(':')
            writeValue(sb, v)
        }
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, items: Iterable<*>) {
        sb.append('[')
        var first = true
        for (item in items) {
            if (!first) sb.append(',')
            first = false
            writeValue(sb, item)
        }
        sb.append(']')
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) {
            sb.append("null")
            return
        }
        // Render whole doubles without a trailing ".0" only when they fit a long,
        // matching the compact numeric form the backend tolerates.
        if (d == d.toLong().toDouble()) {
            sb.append(d.toLong().toString())
        } else {
            sb.append(d.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
    }
}
