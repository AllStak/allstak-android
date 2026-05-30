package sa.allstak.android

import android.content.Context
import androidx.startup.Initializer
import sa.allstak.android.core.internal.SdkLogger

/**
 * androidx.startup [Initializer] that auto-inits the SDK before
 * `Application.onCreate`, reading the API key from manifest `meta-data`. This
 * is what makes a one-line install + key fully automatic — no Application
 * subclass or boilerplate required.
 *
 * If no API key is present in the manifest, init is skipped quietly so the app
 * can still call [AllStak.init] explicitly. Apps that prefer explicit control
 * remove this provider entry in their manifest:
 *
 * ```xml
 * <provider
 *     android:name="androidx.startup.InitializationProvider"
 *     android:authorities="${applicationId}.androidx-startup"
 *     tools:node="merge">
 *     <meta-data
 *         android:name="sa.allstak.android.AllStakInitializer"
 *         tools:node="remove" />
 * </provider>
 * ```
 */
class AllStakInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        try {
            if (AllStak.isInitialized) return
            // Build options purely from manifest meta-data + package metadata.
            // AllStak.init skips quietly when no API key resolves.
            val options = AllStakOptions()
            AllStak.init(context, options)
        } catch (t: Throwable) {
            SdkLogger.debug("Auto-init skipped: ${t.message}")
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
