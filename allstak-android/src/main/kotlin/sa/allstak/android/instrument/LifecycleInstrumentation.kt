package sa.allstak.android.instrument

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import sa.allstak.android.core.AllStakClient
import sa.allstak.android.core.internal.SdkLogger
import sa.allstak.android.core.internal.TraceIds
import java.util.concurrent.atomic.AtomicInteger

/**
 * Auto-registered [Application.ActivityLifecycleCallbacks] that drives:
 *  - screen breadcrumbs (navigation to/from each Activity),
 *  - a screen span per Activity foreground time,
 *  - app foreground/background detection that starts/ends release-health
 *    sessions and emits the cold/warm app-start span,
 *  - reconnect-driven spool replay when the app returns to the foreground.
 *
 * Each concern is gated by its own option so it degrades to a no-op cleanly.
 */
internal class LifecycleInstrumentation(
    private val app: Application,
    private val client: AllStakClient,
    private val options: sa.allstak.android.AllStakOptions,
    private val processStartUptimeMs: Long,
) : Application.ActivityLifecycleCallbacks {

    private val startedActivities = AtomicInteger(0)
    private var coldStartReported = false

    // Per-Activity screen span bookkeeping.
    private val screenSpans = HashMap<String, ScreenSpan>()
    private var lastBackgroundUptimeMs = 0L
    private var inForeground = false

    fun register() {
        app.registerActivityLifecycleCallbacks(this)
        SdkLogger.debug("Activity lifecycle instrumentation registered")
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (options.enableActivityLifecycleBreadcrumbs) {
            client.addBreadcrumb(
                "navigation",
                "Created ${activity.localClassName}",
                "info",
                linkedMapOf("screen" to activity.localClassName),
            )
        }
    }

    override fun onActivityStarted(activity: Activity) {
        val previousCount = startedActivities.getAndIncrement()
        if (previousCount == 0) {
            onEnterForeground()
        }
        if (options.enableActivityLifecycleBreadcrumbs) {
            client.addBreadcrumb(
                "ui",
                "Started ${activity.localClassName}",
                "info",
                linkedMapOf("screen" to activity.localClassName),
            )
        }
        // Begin a screen span for this Activity's visible time.
        val key = activity.toKey()
        screenSpans[key] = ScreenSpan(
            traceId = TraceIds.newTraceId(),
            spanId = TraceIds.newSpanId(),
            startUptimeMs = SystemClock.uptimeMillis(),
            startEpochMs = System.currentTimeMillis(),
            screen = activity.localClassName,
        )
    }

    override fun onActivityResumed(activity: Activity) {
        if (!coldStartReported) {
            coldStartReported = true
            reportAppStartSpan(activity.localClassName)
        }
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        // Finish the screen span.
        val key = activity.toKey()
        screenSpans.remove(key)?.let { sp ->
            val durationMs = SystemClock.uptimeMillis() - sp.startUptimeMs
            client.captureSpan(
                traceId = sp.traceId,
                spanId = sp.spanId,
                parentSpanId = null,
                operation = "ui.screen",
                description = sp.screen,
                status = "ok",
                durationMs = durationMs,
                startTimeMillis = sp.startEpochMs,
                endTimeMillis = System.currentTimeMillis(),
                service = "android",
                tags = linkedMapOf("screen" to sp.screen),
            )
        }
        val remaining = startedActivities.decrementAndGet()
        if (remaining <= 0) {
            onEnterBackground()
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}

    private fun onEnterForeground() {
        inForeground = true
        if (options.enableAppLifecycleTracking) {
            client.addBreadcrumb("navigation", "app.lifecycle", "App entered foreground", "info", null)
        }
        if (options.enableAutoSessionTracking) {
            // Resume / start a session, but only restart if the prior session
            // was ended after a long background period.
            client.startSession()
        }
        // Returning to foreground is the natural reconnect signal: replay any
        // events spooled while we were offline / backgrounded.
        client.drainSpoolNow()
        lastBackgroundUptimeMs = 0L
    }

    private fun onEnterBackground() {
        inForeground = false
        lastBackgroundUptimeMs = SystemClock.uptimeMillis()
        if (options.enableAppLifecycleTracking) {
            client.addBreadcrumb("navigation", "app.lifecycle", "App entered background", "info", null)
        }
        // Flush buffered telemetry promptly when leaving the foreground.
        client.flush()
        if (options.enableAutoSessionTracking) {
            client.endSession()
        }
    }

    private fun reportAppStartSpan(firstScreen: String) {
        try {
            val nowUptime = SystemClock.uptimeMillis()
            val durationMs = (nowUptime - processStartUptimeMs).coerceAtLeast(0)
            // Guard against an implausibly large value if the process had been
            // alive a long time before init (warm path).
            val capped = durationMs.coerceAtMost(60_000)
            client.captureSpan(
                traceId = TraceIds.newTraceId(),
                spanId = TraceIds.newSpanId(),
                parentSpanId = null,
                operation = "app.start",
                description = "Cold start to $firstScreen",
                status = "ok",
                durationMs = capped,
                startTimeMillis = System.currentTimeMillis() - capped,
                endTimeMillis = System.currentTimeMillis(),
                service = "android",
                tags = linkedMapOf("start.type" to "cold"),
            )
            client.addBreadcrumb(
                "navigation",
                "app.lifecycle",
                "Cold start completed in ${capped}ms",
                "info",
                null,
            )
        } catch (t: Throwable) {
            SdkLogger.debug("App-start span failed: ${t.message}")
        }
    }

    private fun Activity.toKey(): String = "${javaClass.name}@${System.identityHashCode(this)}"

    private class ScreenSpan(
        val traceId: String,
        val spanId: String,
        val startUptimeMs: Long,
        val startEpochMs: Long,
        val screen: String,
    )
}
