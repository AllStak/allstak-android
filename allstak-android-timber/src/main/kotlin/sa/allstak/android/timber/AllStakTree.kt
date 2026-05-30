package sa.allstak.android.timber

import android.util.Log
import sa.allstak.android.AllStak
import timber.log.Timber

/**
 * A [Timber.Tree] that ships logs to AllStak. Plant it once at startup:
 *
 * ```
 * Timber.plant(AllStakTree())
 * ```
 *
 * Behavior:
 *  - INFO/WARN/ERROR/ASSERT logs become AllStak log events (DEBUG/VERBOSE are
 *    dropped by default to avoid noise; lower [minLevel] to include them).
 *  - An ERROR/ASSERT log that carries a throwable is additionally promoted to a
 *    captured exception so it shows up as an issue, not just a log line.
 *
 * Guarded: any failure shipping a log is swallowed so logging never crashes the
 * host app. Timber itself is `compileOnly`, so this module only activates when
 * the host app already depends on Timber.
 */
class AllStakTree @JvmOverloads constructor(
    private val minLevel: Int = Log.INFO,
    private val promoteThrowables: Boolean = true,
) : Timber.Tree() {

    public override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= minLevel

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        try {
            val metadata = LinkedHashMap<String, Any?>()
            if (!tag.isNullOrBlank()) metadata["tag"] = tag

            if (promoteThrowables && t != null && priority >= Log.ERROR) {
                AllStak.captureException(t, level = levelFor(priority), metadata = metadata.ifEmpty { null })
                return
            }

            AllStak.captureLog(levelFor(priority), message, metadata.ifEmpty { null })
        } catch (ignored: Throwable) {
            // Logging must never crash the host app.
        }
    }

    private fun levelFor(priority: Int): String = when (priority) {
        Log.VERBOSE, Log.DEBUG -> "debug"
        Log.INFO -> "info"
        Log.WARN -> "warn"
        Log.ERROR -> "error"
        Log.ASSERT -> "fatal"
        else -> "info"
    }
}
