package sa.allstak.android.core.internal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * API-21-safe ISO-8601 timestamps. `java.time.Instant` requires API 26, so the
 * SDK formats UTC timestamps with [SimpleDateFormat] to keep `minSdk 21`
 * without core-library desugaring. The output (`2026-05-30T12:34:56.789Z`)
 * matches the wire shape the platform expects for breadcrumb/HTTP timestamps.
 */
internal object Time {

    // SimpleDateFormat is not thread-safe; use a per-thread instance.
    private val ISO = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }

    fun nowIso(): String = isoOf(System.currentTimeMillis())

    fun isoOf(epochMillis: Long): String = ISO.get()!!.format(Date(epochMillis))
}
