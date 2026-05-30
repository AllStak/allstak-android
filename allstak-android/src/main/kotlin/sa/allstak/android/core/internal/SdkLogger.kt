package sa.allstak.android.core.internal

import android.util.Log

/**
 * Tiny internal logger. Off unless `debug` is enabled in options so the SDK is
 * silent in production. Never throws; routes through android.util.Log when
 * available and degrades to stderr under a host JVM unit test.
 */
object SdkLogger {

    private const val TAG = "AllStak"

    @Volatile
    var debug: Boolean = false

    fun debug(message: String) {
        if (!debug) return
        safe { runCatching { Log.d(TAG, message) }.getOrElse { System.err.println("[AllStak] $message") } }
    }

    fun warn(message: String) {
        safe { runCatching { Log.w(TAG, message) }.getOrElse { System.err.println("[AllStak][warn] $message") } }
    }

    fun warn(message: String, t: Throwable) {
        safe { runCatching { Log.w(TAG, message, t) }.getOrElse { System.err.println("[AllStak][warn] $message: ${t.message}") } }
    }

    fun error(message: String, t: Throwable?) {
        safe { runCatching { Log.e(TAG, message, t) }.getOrElse { System.err.println("[AllStak][error] $message: ${t?.message}") } }
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (ignored: Throwable) {
            // Logging must never crash the host app.
        }
    }
}
