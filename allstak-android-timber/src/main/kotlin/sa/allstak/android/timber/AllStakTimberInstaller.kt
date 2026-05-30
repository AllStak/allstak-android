package sa.allstak.android.timber

import sa.allstak.android.core.internal.SdkLogger
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Auto-installer for the Timber integration. The core SDK invokes this
 * reflectively during `AllStak.init` (when `enableTimberAutoInstall` is on), so
 * merely having this module + Timber on the classpath ships structured logs
 * with **zero** `Timber.plant` call.
 *
 * Idempotent: plants at most one [AllStakTree], and never plants a second if one
 * is already present. Any failure (Timber absent, planting restricted) is
 * swallowed — logging must never destabilize the host app.
 */
object AllStakTimberInstaller {

    private val planted = AtomicBoolean(false)

    /** Plant an [AllStakTree] once, if Timber is available and none is planted. */
    @JvmStatic
    fun install() {
        if (!planted.compareAndSet(false, true)) return
        try {
            // Don't double-instrument if the host already planted our tree.
            val alreadyPlanted = runCatching {
                Timber.forest().any { it is AllStakTree }
            }.getOrDefault(false)
            if (alreadyPlanted) {
                SdkLogger.debug("Timber AllStakTree already planted — skipping auto-install")
                return
            }
            Timber.plant(AllStakTree())
            SdkLogger.debug("Timber AllStakTree auto-planted")
        } catch (t: Throwable) {
            planted.set(false)
            SdkLogger.debug("Timber auto-install skipped: ${t.message}")
        }
    }
}
