package sa.allstak.android.instrument

import android.os.Handler
import android.os.Looper
import sa.allstak.android.core.AllStakClient
import sa.allstak.android.core.internal.SdkLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-Not-Responding watchdog. A background thread posts a sentinel to
 * the main [Looper] and waits up to the threshold for it to run. If the main
 * thread is still blocked, an ANR event is captured with the main thread's
 * stack trace. ON by default (5s threshold), individually toggleable.
 */
internal class AnrWatchdog(
    private val client: AllStakClient,
    private val thresholdMs: Long,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ loop() }, "allstak-anr-watchdog")
        t.isDaemon = true
        t.start()
        thread = t
        SdkLogger.debug("ANR watchdog started (threshold=${thresholdMs}ms)")
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var reportedForThisStall = false
        while (running.get()) {
            val completed = AtomicBoolean(false)
            mainHandler.post { completed.set(true) }
            val deadline = System.currentTimeMillis() + thresholdMs
            try {
                while (!completed.get() && System.currentTimeMillis() < deadline && running.get()) {
                    Thread.sleep(POLL_MS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!completed.get()) {
                if (!reportedForThisStall) {
                    reportedForThisStall = true
                    reportAnr()
                }
            } else {
                reportedForThisStall = false
            }
            // Pace the next probe.
            try {
                Thread.sleep(thresholdMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun reportAnr() {
        try {
            val mainThread = Looper.getMainLooper().thread
            val anr = ApplicationNotResponding(
                "Application Not Responding for at least ${thresholdMs}ms",
                mainThread.stackTrace,
            )
            client.captureException(
                anr,
                level = "error",
                metadata = linkedMapOf("anr.threshold_ms" to thresholdMs, "anr" to true),
            )
            SdkLogger.debug("ANR detected and captured")
        } catch (t: Throwable) {
            SdkLogger.debug("ANR report failed: ${t.message}")
        }
    }

    companion object {
        private const val POLL_MS = 100L
    }
}

/** Synthetic throwable carrying the blocked main-thread stack. */
internal class ApplicationNotResponding(
    message: String,
    stack: Array<StackTraceElement>,
) : RuntimeException(message) {
    init {
        stackTrace = stack
    }

    // The main-thread stack is the signal; suppress the watchdog thread's own.
    override fun fillInStackTrace(): Throwable = this
}
