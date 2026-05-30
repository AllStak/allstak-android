package sa.allstak.android.instrument

import android.os.Process
import sa.allstak.android.core.AllStakClient
import sa.allstak.android.core.internal.SdkLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fully automatic log capture. Reads this process's own logcat stream and ships
 * matching lines as AllStak log events — with **zero developer code** and
 * without requiring any logging facade. This is what makes log telemetry part
 * of the "install + key" baseline instead of an opt-in integration.
 *
 * Design notes:
 *  - Scoped to the current PID (`logcat --pid`) so the SDK only ever sees the
 *    host app's own output — never other apps' logs.
 *  - Captures `WARN` and above by default (the [minPriority] threshold) to keep
 *    volume sane; the host can widen it via [sa.allstak.android.AllStakOptions].
 *  - The SDK's own `AllStak` tag is filtered out to avoid a feedback loop.
 *  - Runs on a single low-priority daemon thread; any failure (older devices
 *    that restrict `logcat`, a killed reader, an SELinux denial) is swallowed
 *    and the feature degrades to a no-op. Logging must never destabilize the
 *    host app.
 *
 * On devices/ROMs that deny `logcat` to a non-debuggable app the reader simply
 * yields nothing; structured logs still flow through the Timber integration and
 * the manual `AllStak.captureLog` API.
 */
internal class LogcatInstrumentation(
    private val client: AllStakClient,
    private val minPriority: Int = PRIORITY_WARN,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    private var process: java.lang.Process? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ runReader() }, "allstak-logcat")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        thread = t
        try {
            t.start()
            SdkLogger.debug("Automatic log capture started (minPriority=$minPriority)")
        } catch (th: Throwable) {
            running.set(false)
            SdkLogger.debug("Log capture could not start: ${th.message}")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            process?.destroy()
        } catch (ignored: Throwable) {
        } finally {
            process = null
            thread = null
        }
    }

    private fun runReader() {
        var reader: BufferedReader? = null
        try {
            // `--pid` scopes to our own process; `threadtime` gives a stable,
            // parseable line format across API levels; `-v` sets the format.
            val pid = Process.myPid().toString()
            val cmd = arrayOf("logcat", "-v", "threadtime", "--pid", pid)
            val proc = try {
                Runtime.getRuntime().exec(cmd)
            } catch (t: Throwable) {
                // `--pid` is unsupported on very old devices; fall back to an
                // unfiltered read and filter by PID ourselves.
                Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime"))
            }
            process = proc
            reader = BufferedReader(InputStreamReader(proc.inputStream), 8 * 1024)
            val myPid = Process.myPid()
            var line: String? = reader.readLine()
            while (running.get() && line != null) {
                val parsed = LogcatLineParser.parse(line, myPid)
                if (parsed != null && parsed.priority >= minPriority && parsed.tag != LogcatLineParser.SDK_TAG) {
                    // SDK_TAG is filtered to avoid a self-feedback loop.
                    shipLine(parsed)
                }
                line = reader.readLine()
            }
        } catch (t: Throwable) {
            SdkLogger.debug("Log capture reader stopped: ${t.message}")
        } finally {
            running.set(false)
            try {
                reader?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun shipLine(parsed: LogcatLineParser.ParsedLine) {
        try {
            val meta = LinkedHashMap<String, Any?>()
            if (parsed.tag.isNotBlank()) meta["tag"] = parsed.tag
            meta["source"] = "logcat"
            client.captureLog(
                level = LogcatLineParser.levelFor(parsed.priority),
                message = if (parsed.tag.isNotBlank()) "${parsed.tag}: ${parsed.message}" else parsed.message,
                metadata = meta,
            )
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        const val PRIORITY_WARN = LogcatLineParser.PRIORITY_WARN
    }
}

/**
 * Pure, device-free parser for a `threadtime`-format logcat line. Extracted so
 * the parsing/level mapping is host-JVM unit-testable without spawning a reader.
 */
internal object LogcatLineParser {
    const val PRIORITY_VERBOSE = 2
    const val PRIORITY_DEBUG = 3
    const val PRIORITY_INFO = 4
    const val PRIORITY_WARN = 5
    const val PRIORITY_ERROR = 6
    const val PRIORITY_FATAL = 7

    const val SDK_TAG = "AllStak"
    private val WS_RUN = Regex("\\s+")

    data class ParsedLine(val priority: Int, val tag: String, val message: String)

    /**
     * Parses `MM-DD HH:MM:SS.mmm  PID  TID PRIO TAG: message`. Returns null for
     * header lines ("--------- beginning of …"), lines from other processes, and
     * anything that doesn't match the expected shape.
     */
    fun parse(raw: String, myPid: Int): ParsedLine? {
        if (raw.isEmpty() || raw.startsWith("---")) return null
        val parts = raw.trim().split(WS_RUN, limit = 6)
        // parts: [date, time, pid, tid, prio, "TAG: message"]
        if (parts.size < 6) return null
        val pid = parts[2].toIntOrNull() ?: return null
        if (pid != myPid) return null
        val priority = priorityOf(parts[4].firstOrNull())
        val tagAndMsg = parts[5]
        val sep = tagAndMsg.indexOf(": ")
        val tag: String
        val message: String
        if (sep >= 0) {
            tag = tagAndMsg.substring(0, sep).trim()
            message = tagAndMsg.substring(sep + 2)
        } else {
            tag = ""
            message = tagAndMsg
        }
        return ParsedLine(priority, tag, message)
    }

    fun priorityOf(c: Char?): Int = when (c) {
        'V' -> PRIORITY_VERBOSE
        'D' -> PRIORITY_DEBUG
        'I' -> PRIORITY_INFO
        'W' -> PRIORITY_WARN
        'E' -> PRIORITY_ERROR
        'F', 'A' -> PRIORITY_FATAL
        else -> PRIORITY_INFO
    }

    fun levelFor(priority: Int): String = when (priority) {
        PRIORITY_VERBOSE, PRIORITY_DEBUG -> "debug"
        PRIORITY_INFO -> "info"
        PRIORITY_WARN -> "warn"
        PRIORITY_ERROR -> "error"
        PRIORITY_FATAL -> "fatal"
        else -> "info"
    }
}
