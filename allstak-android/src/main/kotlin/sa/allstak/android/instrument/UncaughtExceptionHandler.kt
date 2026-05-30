package sa.allstak.android.instrument

import sa.allstak.android.core.AllStakClient
import sa.allstak.android.core.internal.SdkLogger

/**
 * Captures crashes from any thread (including the main thread). Installs as the
 * default uncaught-exception handler, chains the previously-installed one, and
 * flushes synchronously before re-raising so the crash report lands before the
 * process dies. ON by default; opt out via options.
 */
internal class UncaughtExceptionHandler private constructor(
    private val client: AllStakClient,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            client.captureFatalBlocking(throwable)
        } catch (t: Throwable) {
            SdkLogger.debug("Uncaught capture failed: ${t.message}")
        } finally {
            // Always chain so the OS / other crash reporters still run.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // No prior handler — re-raise so the platform default kills
                // the process with the original stack.
                throw throwable
            }
        }
    }

    companion object {
        @Volatile
        private var installed: UncaughtExceptionHandler? = null

        fun install(client: AllStakClient): Boolean {
            if (installed != null) return false
            return try {
                val prev = Thread.getDefaultUncaughtExceptionHandler()
                // Don't chain ourselves if somehow re-installed.
                val handler = UncaughtExceptionHandler(
                    client,
                    if (prev is UncaughtExceptionHandler) prev.previous else prev,
                )
                Thread.setDefaultUncaughtExceptionHandler(handler)
                installed = handler
                SdkLogger.debug("Uncaught exception handler installed")
                true
            } catch (t: Throwable) {
                SdkLogger.debug("Failed to install uncaught handler: ${t.message}")
                false
            }
        }

        fun uninstall() {
            val h = installed ?: return
            try {
                if (Thread.getDefaultUncaughtExceptionHandler() === h) {
                    Thread.setDefaultUncaughtExceptionHandler(h.previous)
                }
            } catch (ignored: Throwable) {
            } finally {
                installed = null
            }
        }
    }
}
