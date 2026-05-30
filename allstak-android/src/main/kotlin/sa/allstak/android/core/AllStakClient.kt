package sa.allstak.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import sa.allstak.android.AllStakDiagnostics
import sa.allstak.android.AllStakOptions
import sa.allstak.android.core.internal.AllStakVersion
import sa.allstak.android.core.internal.SdkLogger
import sa.allstak.android.core.internal.TraceIds
import sa.allstak.android.core.masking.DataMasker
import sa.allstak.android.core.model.Breadcrumb
import sa.allstak.android.core.model.DatabaseQueryItem
import sa.allstak.android.core.model.ErrorEvent
import sa.allstak.android.core.model.Frame
import sa.allstak.android.core.model.HttpRequestItem
import sa.allstak.android.core.model.LogEvent
import sa.allstak.android.core.model.RequestContext
import sa.allstak.android.core.model.UserContext
import sa.allstak.android.core.scope.Scopes
import sa.allstak.android.core.session.FileSessionStateStore
import sa.allstak.android.core.session.SessionTracker
import sa.allstak.android.core.spool.EventSpool
import sa.allstak.android.core.transport.HttpTransport
import sa.allstak.android.core.transport.SendResult
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * Core engine. Owns the capture pipeline (sample -> pre-hook mask ->
 * beforeSend -> final mask -> transport), the breadcrumb/log/http/db buffers,
 * the offline spool, and the
 * release-health session. Mirrors the JVM SDK's `AllStakClient` so the wire
 * shape is identical; async work runs on coroutines instead of raw threads.
 *
 * Thread-safe. One instance per app.
 */
class AllStakClient internal constructor(
    val options: AllStakOptions,
    val transport: HttpTransport,
    spoolDir: File?,
    private val randomSource: () -> Double = { Random.nextDouble() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shutdown = AtomicBoolean(false)

    @Volatile
    private var legacyUser: UserContext? = null

    private val spool: EventSpool? =
        if (options.enableOfflineQueue && spoolDir != null) {
            runCatching { EventSpool(spoolDir) }.getOrNull()
        } else null

    // Buffered sinks flushed on a timer / capacity.
    private val logBuffer = ArrayList<LogEvent>()
    private val httpBuffer = ArrayList<HttpRequestItem>()
    private val dbBuffer = ArrayList<DatabaseQueryItem>()
    private val bufferLock = ReentrantLock()
    private val inFlightJobs = Collections.synchronizedSet(LinkedHashSet<Job>())
    private var flushJob: Job? = null

    val sessionTracker: SessionTracker? =
        if (options.enableAutoSessionTracking) {
            SessionTracker(
                options.environment,
                options.release,
                transport,
                stateStore = spoolDir?.let { FileSessionStateStore(File(it, "session-state.json")) },
            )
        } else null

    init {
        SdkLogger.debug = options.debug
        startFlushLoop()
        drainSpoolAsync()
        SdkLogger.debug(
            "AllStak Android SDK initialized — host=${options.normalizedHost()}, " +
                "env=${options.environment}, release=${options.release}"
        )
    }

    // ── Lifecycle helpers used by the auto-instrumentation layer ────────────

    fun startSession() {
        sessionTracker?.start(initialUserId())
    }

    fun endSession() {
        sessionTracker?.end()
    }

    // =====================================================================
    // Error capture — sent immediately (errors are urgent)
    // =====================================================================

    @JvmOverloads
    fun captureException(
        throwable: Throwable,
        level: String = "error",
        metadata: Map<String, Any?>? = null,
        requestContext: RequestContext? = null,
    ) {
        try {
            if (shutdown.get() || transport.isDisabled) return
            if (isSampledOut(options.sampleRate)) {
                SdkLogger.debug("Exception dropped by sampleRate=${options.sampleRate}")
                return
            }

            val exceptionClass = throwable.javaClass.name
            val message = throwable.message ?: exceptionClass
            val stackTrace = extractStackTrace(throwable)
            val merged = Scopes.mergedForCapture()

            val meta = LinkedHashMap<String, Any?>()
            options.releaseTags().forEach { (k, v) -> meta[k] = v }
            metadata?.let { meta.putAll(it) }
            if (merged.tags.isNotEmpty()) meta["tags"] = merged.tags
            if (merged.contexts.isNotEmpty()) meta["contexts"] = merged.contexts
            if (merged.extras.isNotEmpty()) meta["extras"] = merged.extras

            val crumbs = merged.breadcrumbs.ifEmpty { null }
            var user = merged.user ?: legacyUser
            user = redactUserForPii(user)

            val frames = buildFrames(throwable)
            val sessionId = sessionTracker?.currentSessionId()
            val effectiveLevel = level.ifBlank { merged.level ?: "error" }

            val event = ErrorEvent(
                exceptionClass = exceptionClass,
                message = message,
                stackTrace = stackTrace,
                level = effectiveLevel,
                environment = options.environment,
                release = options.release,
                sessionId = sessionId,
                traceId = TraceIds.normalizeTraceId(requestContext?.traceId),
                user = user,
                metadata = meta.ifEmpty { null },
                requestContext = requestContext,
                breadcrumbs = crumbs,
                platform = AllStakVersion.PLATFORM,
                sdkName = AllStakVersion.SDK_NAME,
                sdkVersion = AllStakVersion.SDK_VERSION,
                dist = options.dist,
                frames = frames.ifEmpty { null },
            )

            val processed = applyBeforeSend(event) ?: run {
                SdkLogger.debug("Exception dropped by beforeSend")
                return
            }
            val masked = maskError(processed, options.sendDefaultPii)
            sendOrSpoolAsync(PATH_ERRORS, masked.toMap())

            // Release-health: bump the session.
            sessionTracker?.let {
                when {
                    effectiveLevel.equals("fatal", true) -> it.recordCrash()
                    effectiveLevel.equals("error", true) -> it.recordError()
                }
            }
        } catch (e: Throwable) {
            SdkLogger.debug("Failed to capture exception: ${e.message}")
        }
    }

    /**
     * Synchronous capture used by the uncaught-exception handler: serialize +
     * send + flush on the calling thread so the event lands before the process
     * dies. Falls back to spool persistence on a transient failure.
     */
    fun captureFatalBlocking(throwable: Throwable) {
        try {
            if (transport.isDisabled) return
            val exceptionClass = throwable.javaClass.name
            val message = throwable.message ?: exceptionClass
            val merged = Scopes.mergedForCapture()
            val meta = LinkedHashMap<String, Any?>()
            options.releaseTags().forEach { (k, v) -> meta[k] = v }
            if (merged.tags.isNotEmpty()) meta["tags"] = merged.tags
            val user = redactUserForPii(merged.user ?: legacyUser)
            val event = ErrorEvent(
                exceptionClass = exceptionClass,
                message = message,
                stackTrace = extractStackTrace(throwable),
                level = "fatal",
                environment = options.environment,
                release = options.release,
                sessionId = sessionTracker?.currentSessionId(),
                traceId = null,
                user = user,
                metadata = meta.ifEmpty { null },
                requestContext = null,
                breadcrumbs = merged.breadcrumbs.ifEmpty { null },
                platform = AllStakVersion.PLATFORM,
                sdkName = AllStakVersion.SDK_NAME,
                sdkVersion = AllStakVersion.SDK_VERSION,
                dist = options.dist,
                frames = buildFrames(throwable).ifEmpty { null },
            )
            val processed = applyBeforeSend(event) ?: return
            val masked = maskError(processed, options.sendDefaultPii)
            sessionTracker?.recordCrash()
            // Synchronous: do not hand to coroutine — the JVM is about to exit.
            sendOrSpoolSync(PATH_ERRORS, masked.toMap())
            flushBlocking()
            sessionTracker?.end()
        } catch (e: Throwable) {
            SdkLogger.debug("Failed to capture fatal: ${e.message}")
        }
    }

    // =====================================================================
    // Log capture — buffered
    // =====================================================================

    @JvmOverloads
    fun captureLog(
        level: String,
        message: String,
        metadata: Map<String, Any?>? = null,
        service: String? = null,
        traceId: String? = null,
        spanId: String? = null,
    ) {
        try {
            if (shutdown.get() || transport.isDisabled) return
            if (!isValidLogLevel(level)) {
                SdkLogger.debug("Invalid log level '$level' — dropping log")
                return
            }
            if (isSampledOut(options.sampleRate)) return
            if (options.enableAutoBreadcrumbs && level in setOf("warn", "error", "fatal")) {
                Scopes.current().addBreadcrumb(Breadcrumb("log", message, level, metadata))
            }

            val meta = LinkedHashMap<String, Any?>()
            options.releaseTags().forEach { (k, v) -> meta[k] = v }
            metadata?.let { meta.putAll(it) }

            val event = LogEvent(
                level = level,
                message = message,
                service = service ?: options.serviceName,
                traceId = TraceIds.normalizeTraceId(traceId),
                environment = options.environment,
                spanId = TraceIds.normalizeSpanId(spanId),
                metadata = meta.ifEmpty { null },
                release = options.release,
            )
            val processed = applyBeforeSend(event) ?: return
            val masked = maskLog(processed, options.sendDefaultPii)
            bufferLock.withLock { logBuffer.add(masked) }
        } catch (e: Throwable) {
            SdkLogger.debug("Failed to capture log: ${e.message}")
        }
    }

    // =====================================================================
    // HTTP request monitoring — buffered, batched
    // =====================================================================

    fun captureHttpRequest(item: HttpRequestItem) {
        try {
            if (shutdown.get() || transport.isDisabled) return
            val sanitized = item.copy(
                traceId = TraceIds.normalizeTraceId(item.traceId),
                spanId = TraceIds.normalizeSpanId(item.spanId),
                parentSpanId = TraceIds.normalizeSpanId(item.parentSpanId),
                path = DataMasker.stripSensitiveQueryParams(item.path),
                environment = item.environment ?: options.environment,
                release = item.release ?: options.release,
            )
            bufferLock.withLock { httpBuffer.add(sanitized) }
        } catch (e: Throwable) {
            SdkLogger.debug("Failed to capture HTTP request: ${e.message}")
        }
    }

    // =====================================================================
    // DB query monitoring — buffered, batched
    // =====================================================================

    fun captureDbQuery(item: DatabaseQueryItem) {
        try {
            if (shutdown.get() || transport.isDisabled) return
            val enriched = item.copy(
                timestampMillis = if (item.timestampMillis == 0L) System.currentTimeMillis() else item.timestampMillis,
                status = item.status ?: "success",
                queryType = item.queryType ?: "OTHER",
                service = item.service ?: options.serviceName,
                environment = item.environment ?: options.environment,
                release = item.release ?: options.release,
                traceId = TraceIds.normalizeTraceId(item.traceId),
                spanId = TraceIds.normalizeSpanId(item.spanId),
            )
            bufferLock.withLock { dbBuffer.add(enriched) }
        } catch (e: Throwable) {
            SdkLogger.debug("Failed to capture DB query: ${e.message}")
        }
    }

    // =====================================================================
    // Span capture — sent immediately
    // =====================================================================

    @JvmOverloads
    fun captureSpan(
        traceId: String,
        spanId: String,
        parentSpanId: String?,
        operation: String,
        description: String?,
        status: String,
        durationMs: Long,
        startTimeMillis: Long,
        endTimeMillis: Long,
        service: String? = null,
        tags: Map<String, String>? = null,
        data: Map<String, Any?>? = null,
        preSampled: Boolean = false,
    ) {
        if (shutdown.get() || transport.isDisabled) return
        if (!preSampled && !isSpanSampled()) {
            SdkLogger.debug("Span dropped by tracesSampleRate=${options.tracesSampleRate}")
            return
        }
        try {
            val normalizedTraceId = TraceIds.normalizeTraceId(traceId) ?: TraceIds.newTraceId()
            val normalizedSpanId = TraceIds.normalizeSpanId(spanId) ?: TraceIds.newSpanId()
            val normalizedParentSpanId = TraceIds.normalizeSpanId(parentSpanId)
            val span = LinkedHashMap<String, Any?>()
            span["traceId"] = normalizedTraceId
            span["spanId"] = normalizedSpanId
            span["parentSpanId"] = normalizedParentSpanId ?: ""
            span["operation"] = operation
            span["description"] = description ?: ""
            span["status"] = status
            span["durationMs"] = durationMs
            span["startTimeMillis"] = startTimeMillis
            span["endTimeMillis"] = endTimeMillis
            span["service"] = service ?: options.serviceName ?: "android"
            span["environment"] = options.environment
            span["release"] = options.release ?: ""
            span["tags"] = tags ?: emptyMap<String, String>()
            span["data"] =
                if (!data.isNullOrEmpty()) DataMasker.maskMetadata(data, options.sendDefaultPii)
                else ""
            val payload = linkedMapOf<String, Any?>("spans" to listOf(span))
            sendOrSpoolAsync(PATH_SPANS, payload)
        } catch (e: Throwable) {
            SdkLogger.debug("Failed to capture span: ${e.message}")
        }
    }

    // =====================================================================
    // Cron / heartbeat monitoring — sent immediately
    // =====================================================================

    /** Post a heartbeat for a background job / scheduled task. */
    @JvmOverloads
    fun captureHeartbeat(slug: String, status: String, durationMs: Long, message: String? = null) {
        if (shutdown.get() || transport.isDisabled) return
        if (!slug.matches(Regex("^[a-z0-9\\-]+$"))) {
            SdkLogger.debug("Invalid cron slug '$slug'")
            return
        }
        val event = sa.allstak.android.core.model.HeartbeatEvent(
            slug = slug,
            status = status.lowercase(),
            durationMs = durationMs,
            message = message,
            environment = options.environment,
            release = options.release,
        )
        sendOrSpoolAsync(PATH_HEARTBEAT, event.toMap())
    }

    /** The W3C traceparent sampled flag for the current config. */
    fun traceparentSampledFlag(): String = if (isSpanSampled()) "01" else "00"

    fun shouldPropagateTrace(url: String): Boolean {
        val targets = options.tracePropagationTargets
        if (targets.isNullOrEmpty()) return true
        return targets.any { url.contains(it, ignoreCase = true) || runCatching { url.matches(Regex(it)) }.getOrDefault(false) }
    }

    // =====================================================================
    // Breadcrumbs / user
    // =====================================================================

    @JvmOverloads
    fun addBreadcrumb(type: String, message: String, level: String? = null, data: Map<String, Any?>? = null) {
        if (shutdown.get()) return
        val safe = DataMasker.maskMetadata(data)
        Scopes.current().addBreadcrumb(Breadcrumb(type, message, level, safe))
    }

    /**
     * Internal breadcrumb path that lets auto-instrumentation set an explicit
     * [category] under a valid contract [type]. Lifecycle/connectivity events
     * therefore group as e.g. `type=navigation, category=app.lifecycle` instead
     * of collapsing to the `default` bucket.
     */
    fun addBreadcrumb(type: String, category: String?, message: String, level: String?, data: Map<String, Any?>?) {
        if (shutdown.get()) return
        val safe = DataMasker.maskMetadata(data)
        Scopes.current().addBreadcrumb(Breadcrumb(type, category, message, level, safe))
    }

    fun setUser(user: UserContext?) {
        legacyUser = user
        Scopes.global().user = user
    }

    fun clearUser() {
        legacyUser = null
        Scopes.global().user = null
    }

    // =====================================================================
    // Flush & shutdown
    // =====================================================================

    fun flush() {
        val job = scope.launch { flushBlocking() }
        trackInFlight(job)
    }

    fun flush(timeoutMs: Long): Boolean {
        flushBlocking()
        return awaitInFlight(timeoutMs)
    }

    private fun flushBlocking() {
        try {
            val logs: List<LogEvent>
            val https: List<HttpRequestItem>
            val dbs: List<DatabaseQueryItem>
            bufferLock.withLock {
                logs = ArrayList(logBuffer); logBuffer.clear()
                https = ArrayList(httpBuffer); httpBuffer.clear()
                dbs = ArrayList(dbBuffer); dbBuffer.clear()
            }
            logs.forEach { sendOrSpoolSync(PATH_LOGS, it.toMap()) }
            https.chunked(HTTP_BATCH_MAX).forEach { batch ->
                sendOrSpoolSync(PATH_HTTP_REQUESTS, linkedMapOf("requests" to batch.map { it.toMap() }))
            }
            dbs.chunked(DB_BATCH_MAX).forEach { batch ->
                sendOrSpoolSync(PATH_DB_QUERIES, linkedMapOf("queries" to batch.map { it.toMap() }))
            }
        } catch (e: Throwable) {
            SdkLogger.debug("Flush failed: ${e.message}")
        }
    }

    fun shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            SdkLogger.debug("AllStak Android SDK shutting down")
            flushJob?.cancel()
            flushBlocking()
            awaitInFlight(DEFAULT_SHUTDOWN_TIMEOUT_MS)
            sessionTracker?.end()
        }
    }

    fun getDiagnostics(): AllStakDiagnostics {
        val stats = transport.stats()
        val buffered = bufferLock.withLock {
            logBuffer.size + httpBuffer.size + dbBuffer.size
        }
        return AllStakDiagnostics(
            eventsCaptured = stats.eventsCaptured,
            eventsSent = stats.eventsSent,
            eventsFailed = stats.eventsFailed,
            eventsDropped = stats.eventsDropped,
            eventsPersisted = stats.eventsPersisted,
            eventsReplayed = stats.eventsReplayed,
            queueSize = buffered + (spool?.count() ?: 0),
            retryAttempts = stats.retryAttempts,
            rateLimitedCount = stats.rateLimitedCount,
            compressedPayloads = stats.compressedPayloads,
            uncompressedPayloads = stats.uncompressedPayloads,
            compressionBytesSaved = stats.compressionBytesSaved,
            sanitizerRedactionCount = null,
            activeTraceCount = 0,
            activeSpanCount = 0,
            breadcrumbCount = Scopes.mergedForCapture().breadcrumbs.size,
            sessionRecoveryCount = sessionTracker?.recoveryCount() ?: 0,
            disabled = shutdown.get() || stats.disabled,
        )
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private fun startFlushLoop() {
        flushJob = scope.launch {
            while (!shutdown.get()) {
                try {
                    kotlinx.coroutines.delay(options.flushIntervalMs)
                    flushBlocking()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    break
                } catch (e: Throwable) {
                    SdkLogger.debug("Flush loop error: ${e.message}")
                }
            }
        }
    }

    private fun sendOrSpoolAsync(path: String, payload: Map<String, Any?>) {
        val job = scope.launch { sendOrSpoolSync(path, payload) }
        trackInFlight(job)
    }

    private fun sendOrSpoolSync(path: String, payload: Map<String, Any?>): Boolean {
        val result = transport.sendWithResult(path, payload)
        val s = spool
        if (result == SendResult.TRANSIENT && s != null && s.isAvailable) {
            val persisted = runCatching {
                s.tryPersist(path, sa.allstak.android.core.internal.Json.encodeObject(payload))
            }.getOrDefault(false)
            if (persisted) transport.recordPersisted() else transport.recordDropped()
        } else if (result == SendResult.TRANSIENT) {
            transport.recordDropped()
        }
        return result.isAccepted
    }

    private fun drainSpoolAsync() {
        val s = spool ?: return
        if (!s.isAvailable) return
        val job = scope.launch {
            try {
                val handles = s.load()
                if (handles.isEmpty()) return@launch
                SdkLogger.debug("Draining ${handles.size} persisted event(s) from offline spool")
                for (h in handles) {
                    if (shutdown.get() || transport.isDisabled) break
                    val r = transport.sendRawJson(h.path, h.body)
                    if (r == SendResult.ACCEPTED || r == SendResult.PERMANENT) s.remove(h)
                }
            } catch (e: Throwable) {
                SdkLogger.debug("Spool drain failed: ${e.message}")
            }
        }
        trackInFlight(job)
    }

    /** Public so the connectivity watcher can replay on reconnect. */
    fun drainSpoolNow() = drainSpoolAsync()

    private fun initialUserId(): String? =
        (Scopes.mergedForCapture().user ?: legacyUser)?.id

    private fun isSampledOut(rate: Double): Boolean {
        if (rate >= 1.0) return false
        if (rate <= 0.0) return true
        return randomSource() >= rate
    }

    fun isSpanSampled(): Boolean {
        val rate = options.tracesSampleRate ?: return true
        if (rate >= 1.0) return true
        if (rate <= 0.0) return false
        return randomSource() < rate
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> applyBeforeSend(event: T): T? {
        val hook = options.beforeSend ?: return event
        val sanitized = sanitizeForBeforeSend(event)
        return try {
            hook(sanitized as Any) as T?
        } catch (t: Throwable) {
            SdkLogger.debug("beforeSend threw — sending sanitized event: ${t.message}")
            sanitized
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> sanitizeForBeforeSend(event: T): T = try {
        when (event) {
            is ErrorEvent -> maskError(event, options.sendDefaultPii) as T
            is LogEvent -> maskLog(event, options.sendDefaultPii) as T
            else -> event
        }
    } catch (t: Throwable) {
        SdkLogger.debug("Pre-beforeSend sanitization failed — using redacted event: ${t.message}")
        when (event) {
            is ErrorEvent -> event.copy(
                message = REDACTED,
                stackTrace = null,
                metadata = mapOf("redacted" to true),
                breadcrumbs = null,
            ) as T
            is LogEvent -> event.copy(
                message = REDACTED,
                metadata = mapOf("redacted" to true),
            ) as T
            else -> event
        }
    }

    private fun redactUserForPii(user: UserContext?): UserContext? {
        if (user == null) return null
        if (options.sendDefaultPii) return user
        if (user.id.isNullOrBlank()) return null
        if (user.email == null && user.ip == null) return user
        return UserContext.ofId(user.id)
    }

    private fun maskError(e: ErrorEvent, sendDefaultPii: Boolean): ErrorEvent = try {
        e.copy(
            message = DataMasker.scrubValue(e.message, sendDefaultPii),
            stackTrace = e.stackTrace?.map { DataMasker.scrubValue(it, sendDefaultPii) ?: it },
            metadata = DataMasker.maskMetadata(e.metadata, sendDefaultPii),
            breadcrumbs = e.breadcrumbs?.map { maskBreadcrumb(it, sendDefaultPii) },
        )
    } catch (t: Throwable) {
        SdkLogger.debug("Value scrubbing failed for error — using redacted fallback: ${t.message}")
        e.copy(message = REDACTED, stackTrace = null, metadata = mapOf("redacted" to true), breadcrumbs = null)
    }

    private fun maskLog(e: LogEvent, sendDefaultPii: Boolean): LogEvent = try {
        e.copy(
            message = DataMasker.scrubValue(e.message, sendDefaultPii),
            metadata = DataMasker.maskMetadata(e.metadata, sendDefaultPii),
        )
    } catch (t: Throwable) {
        e.copy(message = REDACTED, metadata = mapOf("redacted" to true))
    }

    private fun maskBreadcrumb(b: Breadcrumb, sendDefaultPii: Boolean): Breadcrumb = Breadcrumb(
        b.type,
        b.category,
        DataMasker.scrubValue(b.message, sendDefaultPii),
        b.level,
        b.data?.let { DataMasker.maskMetadata(it, sendDefaultPii) },
    )

    private fun buildFrames(throwable: Throwable): List<Frame> {
        val frames = ArrayList<Frame>()
        var count = 0
        for (st in throwable.stackTrace) {
            if (count++ >= MAX_STACK_FRAMES) break
            val fn = "${st.className}.${st.methodName}"
            val file = st.fileName
            frames.add(
                Frame(
                    filename = file,
                    absPath = file,
                    function = fn,
                    lineno = if (st.lineNumber > 0) st.lineNumber else null,
                    colno = null,
                    inApp = isInApp(st.className),
                    platform = AllStakVersion.PLATFORM,
                    debugId = null,
                )
            )
        }
        return frames
    }

    private fun extractStackTrace(throwable: Throwable): List<String> {
        val result = ArrayList<String>()
        var total = 0
        var current: Throwable? = throwable
        var first = true
        while (current != null && total < MAX_STACK_FRAMES) {
            val header = (if (first) "" else "Caused by: ") +
                current.javaClass.name +
                (current.message?.let { ": $it" } ?: "")
            first = false
            result.add(header)
            for (el in current.stackTrace) {
                if (total >= MAX_STACK_FRAMES) break
                result.add("at ${el.className}.${el.methodName}(${el.fileName ?: "Unknown"}:${el.lineNumber})")
                total++
            }
            current = current.cause
        }
        return result
    }

    private fun isInApp(className: String?): Boolean {
        if (className == null) return true
        return !(className.startsWith("java.") ||
            className.startsWith("javax.") ||
            className.startsWith("jdk.") ||
            className.startsWith("sun.") ||
            className.startsWith("android.") ||
            className.startsWith("androidx.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("kotlinx.") ||
            className.startsWith("com.android.") ||
            className.startsWith("dalvik.") ||
            className.startsWith("sa.allstak.android."))
    }

    private fun isValidLogLevel(level: String?): Boolean =
        level != null && level in setOf("debug", "info", "warn", "error", "fatal")

    private fun trackInFlight(job: Job) {
        inFlightJobs.add(job)
        job.invokeOnCompletion { inFlightJobs.remove(job) }
    }

    private fun awaitInFlight(timeoutMs: Long): Boolean {
        val jobs = synchronized(inFlightJobs) { inFlightJobs.toList() }
        if (jobs.isEmpty()) return true
        return try {
            runBlocking {
                withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                    jobs.forEach { job -> job.join() }
                    true
                } == true
            }
        } catch (t: Throwable) {
            SdkLogger.debug("Timed out waiting for transport jobs: ${t.message}")
            false
        }
    }

    companion object {
        private const val REDACTED = "[REDACTED]"
        private const val DEFAULT_SHUTDOWN_TIMEOUT_MS = 5_000L
        const val PATH_ERRORS = "/ingest/v1/errors"
        const val PATH_LOGS = "/ingest/v1/logs"
        const val PATH_HTTP_REQUESTS = "/ingest/v1/http-requests"
        const val PATH_HEARTBEAT = "/ingest/v1/heartbeat"
        const val PATH_DB_QUERIES = "/ingest/v1/db"
        const val PATH_SPANS = "/ingest/v1/spans"

        private const val HTTP_BATCH_MAX = 100
        private const val DB_BATCH_MAX = 100
        private const val MAX_STACK_FRAMES = 100
    }
}
