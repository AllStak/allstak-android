package sa.allstak.android

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import sa.allstak.android.core.AllStakClient
import sa.allstak.android.core.internal.AllStakVersion
import sa.allstak.android.core.internal.SdkLogger
import sa.allstak.android.core.model.DatabaseQueryItem
import sa.allstak.android.core.model.HttpRequestItem
import sa.allstak.android.core.model.RequestContext
import sa.allstak.android.core.model.UserContext
import sa.allstak.android.core.scope.Scope
import sa.allstak.android.core.scope.Scopes
import sa.allstak.android.core.transport.HttpTransport
import sa.allstak.android.instrument.AnrWatchdog
import sa.allstak.android.instrument.ConnectivityWatcher
import sa.allstak.android.instrument.LifecycleInstrumentation
import sa.allstak.android.instrument.LogcatInstrumentation
import sa.allstak.android.instrument.UncaughtExceptionHandler
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Static entry point for the AllStak Android SDK.
 *
 * One-line install: add the dependency and the API key as manifest meta-data,
 * and the [AllStakInitializer] auto-inits the SDK before your Application runs —
 * giving you automatic errors, crashes, ANRs, HTTP, logs, traces, breadcrumbs,
 * and release-health sessions with zero further code.
 *
 * For explicit control:
 * ```
 * AllStak.init(context) {
 *     apiKey = "ask_live_xxx"
 *     environment = "production"
 *     release = "1.4.0+42"
 * }
 * ```
 */
object AllStak {

    private val CLIENT = AtomicReference<AllStakClient?>()
    private var lifecycle: LifecycleInstrumentation? = null
    private var anrWatchdog: AnrWatchdog? = null
    private var logcat: LogcatInstrumentation? = null

    // Holds the *application* context only (process-lived), so this is not an
    // Activity/View leak; the lint check cannot distinguish the two.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var connectivity: ConnectivityWatcher? = null

    /** The active client, or null before init. Internal — integrations use it. */
    val client: AllStakClient? get() = CLIENT.get()

    val isInitialized: Boolean get() = CLIENT.get() != null

    /**
     * Initialize with the Kotlin DSL. Idempotent — a second call is ignored.
     * Auto-wires every enabled instrumentation feature.
     */
    @JvmStatic
    fun init(context: Context, configure: AllStakOptions.() -> Unit) {
        val options = AllStakOptions().apply(configure)
        init(context, options)
    }

    /** Initialize with a pre-built [AllStakOptions]. */
    @JvmStatic
    fun init(context: Context, options: AllStakOptions) {
        if (CLIENT.get() != null) {
            SdkLogger.warn("AllStak.init() called more than once — ignoring")
            return
        }
        val appContext = context.applicationContext

        // Fill blanks from the manifest / package metadata so a one-line setup
        // works: API key from meta-data, release from versionName+versionCode.
        fillFromManifest(appContext, options)

        try {
            options.validate()
        } catch (e: Exception) {
            SdkLogger.error("AllStak.init() failed validation", e)
            return
        }

        try {
            val transport = HttpTransport(options.normalizedHost(), options.apiKey!!)
            val spoolDir = resolveSpoolDir(appContext, options)
            val newClient = AllStakClient(options, transport, spoolDir)
            if (!CLIENT.compareAndSet(null, newClient)) {
                newClient.shutdown()
                SdkLogger.warn("AllStak.init() called concurrently — ignoring duplicate")
                return
            }
            wireInstrumentation(appContext, newClient, options)
            SdkLogger.debug("AllStak Android SDK ready")
        } catch (e: Throwable) {
            SdkLogger.error("AllStak.init() failed", e)
        }
    }

    private fun wireInstrumentation(
        appContext: Context,
        client: AllStakClient,
        options: AllStakOptions,
    ) {
        // Uncaught / crash capture (any thread).
        if (options.enableUncaughtExceptionHandler) {
            UncaughtExceptionHandler.install(client)
        }

        // Activity lifecycle: screen breadcrumbs/spans, app start span,
        // foreground/background sessions. Requires an Application context.
        val app = appContext as? Application
        if (app != null) {
            lifecycle = LifecycleInstrumentation(
                app, client, options, ProcessStart.uptimeMs,
            ).also { it.register() }
        } else {
            // No Application — start the session immediately so release-health
            // still works in a non-Application context (e.g. tests).
            if (options.enableAutoSessionTracking) client.startSession()
        }

        // ANR watchdog.
        if (options.enableAnrDetection) {
            anrWatchdog = AnrWatchdog(client, options.anrThresholdMs).also { it.start() }
        }

        // Connectivity breadcrumbs + reconnect spool drain.
        if (options.enableConnectivityBreadcrumbs) {
            connectivity = ConnectivityWatcher(appContext, client).also { it.register() }
        }

        // Automatic log capture from the app's own logcat stream — zero
        // developer code, no logging facade required.
        if (options.enableAutoLogCapture) {
            logcat = LogcatInstrumentation(client, options.logCaptureMinPriority).also { it.start() }
        }

        // Auto-plant the Timber tree if the integration module + Timber are on
        // the classpath. Reflective so the core module never hard-depends on
        // the optional module; a missing class degrades to a silent no-op.
        if (options.enableTimberAutoInstall) {
            tryAutoInstallTimber()
        }
    }

    /**
     * Reflectively invoke the Timber integration's auto-installer if present, so
     * structured logs flow with zero `Timber.plant` call. No-ops silently when
     * the `allstak-android-timber` module or Timber itself is absent.
     */
    private fun tryAutoInstallTimber() {
        try {
            val installer = Class.forName("sa.allstak.android.timber.AllStakTimberInstaller")
            val instance = installer.getField("INSTANCE").get(null)
            installer.getMethod("install").invoke(instance)
        } catch (ignored: Throwable) {
            // Module/Timber not on the classpath, or already planted — fine.
        }
    }

    /** Shut down the SDK: flush, end the session, and detach instrumentation. */
    @JvmStatic
    fun close() {
        close(5_000)
    }

    /** Shut down the SDK with a hard timeout for pending transport work. */
    @JvmStatic
    fun close(timeoutMs: Long): Boolean {
        val c = CLIENT.getAndSet(null) ?: return true
        try {
            anrWatchdog?.stop()
            logcat?.stop()
            connectivity?.unregister()
            UncaughtExceptionHandler.uninstall()
            val flushed = c.flush(timeoutMs)
            c.shutdown()
            return flushed
        } finally {
            anrWatchdog = null
            logcat = null
            connectivity = null
            lifecycle = null
        }
    }

    // =====================================================================
    // Capture API (no-ops before init)
    // =====================================================================

    @JvmStatic
    @JvmOverloads
    fun captureException(
        throwable: Throwable,
        level: String = "error",
        metadata: Map<String, Any?>? = null,
    ) {
        CLIENT.get()?.captureException(throwable, level, metadata)
    }

    @JvmStatic
    @JvmOverloads
    fun captureMessage(message: String, level: String = "info", metadata: Map<String, Any?>? = null) {
        CLIENT.get()?.captureLog(level, message, metadata)
    }

    @JvmStatic
    @JvmOverloads
    fun captureLog(level: String, message: String, metadata: Map<String, Any?>? = null) {
        CLIENT.get()?.captureLog(level, message, metadata)
    }

    @JvmStatic
    fun captureHttpRequest(item: HttpRequestItem) {
        CLIENT.get()?.captureHttpRequest(item)
    }

    @JvmStatic
    fun captureDbQuery(item: DatabaseQueryItem) {
        CLIENT.get()?.captureDbQuery(item)
    }

    @JvmStatic
    @JvmOverloads
    fun captureSpan(
        traceId: String,
        spanId: String,
        parentSpanId: String? = null,
        operation: String,
        description: String? = null,
        status: String = "ok",
        durationMs: Long,
        startTimeMillis: Long,
        endTimeMillis: Long,
        service: String? = null,
        tags: Map<String, String>? = null,
        data: Map<String, Any?>? = null,
        preSampled: Boolean = false,
    ) {
        CLIENT.get()?.captureSpan(
            traceId = traceId,
            spanId = spanId,
            parentSpanId = parentSpanId,
            operation = operation,
            description = description,
            status = status,
            durationMs = durationMs,
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            service = service,
            tags = tags,
            data = data,
            preSampled = preSampled,
        )
    }

    @JvmStatic
    @JvmOverloads
    fun captureHeartbeat(slug: String, status: String, durationMs: Long, message: String? = null) {
        CLIENT.get()?.captureHeartbeat(slug, status, durationMs, message)
    }

    @JvmStatic
    @JvmOverloads
    fun addBreadcrumb(type: String, message: String, level: String? = null, data: Map<String, Any?>? = null) {
        CLIENT.get()?.addBreadcrumb(type, message, level, data)
    }

    @JvmStatic
    @JvmOverloads
    fun setUser(id: String?, email: String? = null, ip: String? = null) {
        CLIENT.get()?.setUser(if (id == null && email == null && ip == null) null else UserContext(id, email, ip))
    }

    @JvmStatic
    fun clearUser() {
        CLIENT.get()?.clearUser()
    }

    @JvmStatic
    fun setTag(key: String, value: String?) {
        Scopes.global().setTag(key, value)
    }

    @JvmStatic
    fun setExtra(key: String, value: Any?) {
        Scopes.global().setExtra(key, value)
    }

    /** Run [block] against a forked current scope, restoring it afterward. */
    @JvmStatic
    fun withScope(block: (Scope) -> Unit) {
        val forked = Scopes.current().copy()
        try {
            block(forked)
        } finally {
            // Forked scope is local to this call; nothing to restore on the
            // global scope. (Provided for ergonomic parity.)
        }
    }

    @JvmStatic
    fun flush() {
        CLIENT.get()?.flush()
    }

    @JvmStatic
    fun flush(timeoutMs: Long): Boolean =
        CLIENT.get()?.flush(timeoutMs) ?: true

    @JvmStatic
    fun getDiagnostics(): AllStakDiagnostics =
        CLIENT.get()?.getDiagnostics() ?: AllStakDiagnostics()

    /** Build a [RequestContext] for manual inbound-request correlation. */
    @JvmStatic
    @JvmOverloads
    fun requestContext(
        method: String?,
        path: String?,
        host: String?,
        statusCode: Int? = null,
        userAgent: String? = null,
        traceId: String? = null,
    ): RequestContext = RequestContext(method, path, host, statusCode, userAgent, traceId)

    // =====================================================================
    // Manifest / package metadata helpers
    // =====================================================================

    private fun fillFromManifest(context: Context, options: AllStakOptions) {
        try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val meta = ai.metaData
            if (meta != null) {
                if (options.apiKey.isNullOrBlank()) {
                    meta.getString(META_API_KEY)?.let { if (it.isNotBlank()) options.apiKey = it }
                }
                meta.getString(META_HOST)?.let { if (it.isNotBlank()) options.host = it }
                meta.getString(META_ENVIRONMENT)?.let { if (it.isNotBlank()) options.environment = it }
                if (meta.containsKey(META_DEBUG)) options.debug = meta.getBoolean(META_DEBUG, options.debug)
            }
        } catch (t: Throwable) {
            SdkLogger.debug("Manifest meta-data read skipped: ${t.message}")
        }

        if (options.release.isNullOrBlank()) {
            options.release = detectRelease(context)
        }
    }

    private fun detectRelease(context: Context): String {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val pi = pm.getPackageInfo(context.packageName, 0)
            val name = pi.versionName ?: AllStakVersion.SDK_VERSION
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION") pi.versionCode.toLong()
            }
            "$name+$code"
        } catch (t: Throwable) {
            AllStakVersion.SDK_VERSION
        }
    }

    private fun resolveSpoolDir(context: Context, options: AllStakOptions): File? {
        if (!options.enableOfflineQueue) return null
        return try {
            val base = options.offlineQueueDir?.let { File(it) }
                ?: File(context.cacheDir, "allstak-spool")
            base
        } catch (t: Throwable) {
            null
        }
    }

    private const val META_API_KEY = "sa.allstak.android.API_KEY"
    private const val META_HOST = "sa.allstak.android.HOST"
    private const val META_ENVIRONMENT = "sa.allstak.android.ENVIRONMENT"
    private const val META_DEBUG = "sa.allstak.android.DEBUG"
}

/**
 * Captures the earliest uptime the SDK class is loaded, used as the cold-start
 * span origin. Loaded by the androidx.startup initializer very early in the
 * process, so it approximates process start closely.
 */
internal object ProcessStart {
    val uptimeMs: Long = SystemClock.uptimeMillis()
}
