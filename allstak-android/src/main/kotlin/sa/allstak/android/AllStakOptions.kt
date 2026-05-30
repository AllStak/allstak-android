package sa.allstak.android

import sa.allstak.android.core.internal.AllStakVersion

/**
 * Configuration for the AllStak Android SDK. Construct via [AllStak.init] with
 * the Kotlin DSL:
 *
 * ```
 * AllStak.init(context) {
 *     apiKey = "ask_live_xxx"
 *     environment = "production"
 *     release = "1.4.0+42"
 * }
 * ```
 *
 * Every auto-instrumentation feature is ON by default and individually
 * toggleable. PII is OFF by default. The only required field is [apiKey] —
 * and even that is auto-read from manifest `meta-data` when present, so a
 * one-line install needs zero Kotlin.
 */
class AllStakOptions {

    /** Ingest API key. Sent as the `X-AllStak-Key` header. Required. */
    var apiKey: String? = null

    /** Ingest host. Override for self-hosted / validation. */
    var host: String = AllStakVersion.DEFAULT_HOST

    /** Deployment environment tag, e.g. "production" / "staging". */
    var environment: String = "production"

    /** Release identifier (versionName+versionCode is auto-detected if null). */
    var release: String? = null

    /** Optional distribution tag (build flavor / channel). */
    var dist: String? = null

    /** Logical service name stamped on logs / db / spans. */
    var serviceName: String? = null

    /** Verbose internal logging. Off in production. */
    var debug: Boolean = false

    // ── Privacy ──────────────────────────────────────────────────────────
    /** Ship user.email/ip + un-scrubbed bodies. Default false (privacy-first). */
    var sendDefaultPii: Boolean = false

    // ── Sampling ─────────────────────────────────────────────────────────
    /** Error/log keep-rate in [0,1]. Default 1.0. */
    var sampleRate: Double = 1.0

    /** Span keep-rate in [0,1], or null for always-on. */
    var tracesSampleRate: Double? = null

    // ── Auto-instrumentation toggles (all ON by default) ───────────────────
    var enableUncaughtExceptionHandler: Boolean = true
    var enableAnrDetection: Boolean = true
    var enableActivityLifecycleBreadcrumbs: Boolean = true
    var enableAppLifecycleTracking: Boolean = true
    var enableConnectivityBreadcrumbs: Boolean = true
    var enableAutoSessionTracking: Boolean = true
    var enableAutoBreadcrumbs: Boolean = true
    var enableOfflineQueue: Boolean = true

    /**
     * Automatic log capture from the app's own logcat stream — zero developer
     * code, no logging facade required. On by default; ships `WARN` and above
     * (see [logCaptureMinPriority]). The reader is scoped to this process only.
     */
    var enableAutoLogCapture: Boolean = true

    /**
     * Minimum android.util.Log priority captured by [enableAutoLogCapture].
     * Defaults to `WARN` (5). Use the `android.util.Log` constants:
     * VERBOSE=2, DEBUG=3, INFO=4, WARN=5, ERROR=6, ASSERT/FATAL=7.
     */
    var logCaptureMinPriority: Int = 5

    /**
     * Auto-plant the Timber tree when the `allstak-android-timber` integration
     * and Timber are both on the classpath, so structured logs flow with zero
     * `Timber.plant` call. On by default; harmless when Timber is absent.
     */
    var enableTimberAutoInstall: Boolean = true

    /** ANR threshold in ms. Main thread blocked longer than this -> ANR event. */
    var anrThresholdMs: Long = 5_000

    /** Background timeout: app backgrounded longer than this ends the session. */
    var sessionBackgroundTimeoutMs: Long = 30_000

    // ── Buffering ──────────────────────────────────────────────────────────
    var maxBreadcrumbs: Int = 100
    var flushIntervalMs: Long = 5_000
    var bufferSize: Int = 500

    /** Override the offline spool directory. Null -> app cacheDir/allstak-spool. */
    var offlineQueueDir: String? = null

    /**
     * Optional callback invoked just before an event is sent. Receives a
     * sanitized [sa.allstak.android.core.model.ErrorEvent] or
     * [sa.allstak.android.core.model.LogEvent]. Return the event (modified or
     * not) to keep it, or null to drop it. The SDK sanitizes again after the
     * hook so callbacks cannot reintroduce secrets.
     */
    var beforeSend: ((Any) -> Any?)? = null

    /** Outbound trace-propagation allowlist (substrings/regex). Null = all. */
    var tracePropagationTargets: List<String>? = null

    internal fun normalizedHost(): String = host.trimEnd('/')

    internal fun validate() {
        val key = apiKey
        require(!key.isNullOrBlank()) { "apiKey must not be blank" }
        require(bufferSize > 0) { "bufferSize must be positive" }
        require(flushIntervalMs > 0) { "flushIntervalMs must be positive" }
        require(sampleRate in 0.0..1.0) { "sampleRate must be between 0.0 and 1.0" }
        tracesSampleRate?.let {
            require(it in 0.0..1.0) { "tracesSampleRate must be between 0.0 and 1.0" }
        }
    }

    /** Release-tracking tags merged into every payload's metadata. */
    internal fun releaseTags(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["sdk.name"] = AllStakVersion.SDK_NAME
        out["sdk.version"] = AllStakVersion.SDK_VERSION
        out["platform"] = AllStakVersion.PLATFORM
        dist?.let { out["dist"] = it }
        return out
    }
}
