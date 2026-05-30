package sa.allstak.android.core.session

import sa.allstak.android.core.internal.AllStakVersion
import sa.allstak.android.core.internal.SdkLogger
import sa.allstak.android.core.transport.HttpTransport
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

interface SessionStateStore {
    fun read(): Map<String, Any?>?
    fun write(state: Map<String, Any?>)
    fun clear()
}

class FileSessionStateStore(private val file: File) : SessionStateStore {
    override fun read(): Map<String, Any?>? = try {
        if (!file.exists()) null else JSONObject(file.readText()).toMap()
    } catch (_: Throwable) {
        clear()
        null
    }

    override fun write(state: Map<String, Any?>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(JSONObject(state).toString())
            tmp.renameTo(file)
        } catch (_: Throwable) {
            // fail-open
        }
    }

    override fun clear() {
        try {
            file.delete()
        } catch (_: Throwable) {
            // ignore
        }
    }
}

/**
 * Mobile release-health session tracker. Starts a session on app foreground
 * and ends it on background, posting `/sessions/start` and `/sessions/end`.
 * Errored / crashed transitions are recorded in-memory; only the lifecycle
 * calls do network I/O. Re-entrancy safe.
 *
 * @param send the function used to POST a lifecycle envelope. Defaults to the
 *             transport; overridable in tests so no real network is touched.
 */
class SessionTracker(
    private val environment: String,
    private val release: String?,
    private val transport: HttpTransport?,
    private val stateStore: SessionStateStore? = null,
    private val send: (String, Map<String, Any?>) -> Unit = { path, payload ->
        transport?.send(path, payload)
    },
) {
    private val active = AtomicReference<Session?>()
    private val recoveredSessions = AtomicLong(0)

    fun currentSessionId(): String? = active.get()?.id

    fun recoveryCount(): Long = recoveredSessions.get()

    /** Start a new session if none is active. Idempotent for the active one. */
    fun start(userId: String?): Session {
        val candidate = Session()
        if (!active.compareAndSet(null, candidate)) return active.get()!!
        recoverPreviousSession()
        if (transport?.isDisabled == true) return candidate

        writeOpenState(candidate, userId)
        val payload = linkedMapOf<String, Any?>(
            "sessionId" to candidate.id,
            "release" to resolveRelease(),
            "environment" to environment,
            "userId" to userId,
            "sdkName" to AllStakVersion.SDK_NAME,
            "sdkVersion" to AllStakVersion.SDK_VERSION,
            "platform" to AllStakVersion.PLATFORM,
        )
        try {
            send(PATH_START, payload)
            SdkLogger.debug("Session started: ${candidate.id}")
        } catch (t: Throwable) {
            SdkLogger.debug("Session start failed: ${t.message}")
        }
        return candidate
    }

    fun recordError() {
        active.get()?.let {
            it.recordError()
            writeOpenState(it, null)
        }
    }

    fun recordCrash() {
        active.get()?.let {
            it.recordCrash()
            writeOpenState(it, null)
        }
    }

    /** End the active session and POST `/sessions/end`. Idempotent. */
    fun end(finalStatus: SessionStatus? = null) {
        val s = active.getAndSet(null) ?: return
        val status = finalStatus ?: s.status
        writeClosedState(s, status)
        if (transport?.isDisabled == true) return
        val payload = linkedMapOf<String, Any?>(
            "sessionId" to s.id,
            "release" to resolveRelease(),
            "environment" to environment,
            "sdkName" to AllStakVersion.SDK_NAME,
            "sdkVersion" to AllStakVersion.SDK_VERSION,
            "platform" to AllStakVersion.PLATFORM,
            "durationMs" to s.durationMs(),
            "status" to status.wireValue(),
        )
        try {
            send(PATH_END, payload)
            SdkLogger.debug("Session ended: ${s.id} status=${status.wireValue()} errors=${s.getErrorCount()}")
        } catch (t: Throwable) {
            SdkLogger.debug("Session end failed: ${t.message}")
        }
    }

    private fun resolveRelease(): String =
        if (!release.isNullOrBlank()) release else AllStakVersion.SDK_VERSION

    private fun recoverPreviousSession() {
        val store = stateStore ?: return
        val state = store.read() ?: return
        if (state["closed"] == true) {
            store.clear()
            return
        }
        val startedAt = (state["startedAt"] as? Number)?.toLong() ?: run {
            store.clear()
            return
        }
        val now = System.currentTimeMillis()
        if (now - startedAt > STATE_MAX_AGE_MS) {
            store.clear()
            return
        }
        val attempts = (state["recoveryAttempts"] as? Number)?.toInt() ?: 0
        if (attempts >= RECOVERY_MAX_ATTEMPTS) {
            store.clear()
            return
        }
        val lockUntil = (state["recoveryLockUntil"] as? Number)?.toLong() ?: 0L
        if (lockUntil > now) return

        val owner = Session().id
        val locked = LinkedHashMap(state)
        locked["recoveryAttempts"] = attempts + 1
        locked["recoveryLockOwner"] = owner
        locked["recoveryLockUntil"] = now + RECOVERY_LOCK_MS
        locked["updatedAt"] = now
        store.write(locked)
        if (store.read()?.get("recoveryLockOwner") != owner) return

        val previousStatus = state["status"] as? String
        val status = if (previousStatus == SessionStatus.CRASHED.wireValue()) {
            SessionStatus.CRASHED
        } else {
            SessionStatus.ABNORMAL
        }
        val payload = linkedMapOf<String, Any?>(
            "sessionId" to state["sessionId"],
            "durationMs" to (((state["updatedAt"] as? Number)?.toLong() ?: now) - startedAt).coerceAtLeast(0),
            "status" to status.wireValue(),
        )
        try {
            if (transport?.isDisabled != true) send(PATH_END, payload)
            locked["status"] = status.wireValue()
            locked["closed"] = true
            locked["endedAt"] = now
            locked["recoveredAt"] = now
            locked["recoveryLockUntil"] = 0
            store.write(locked)
            recoveredSessions.incrementAndGet()
        } catch (t: Throwable) {
            locked["recoveryLockUntil"] = 0
            store.write(locked)
            SdkLogger.debug("Session recovery failed: ${t.message}")
        }
    }

    private fun writeOpenState(session: Session, userId: String?) {
        stateStore?.write(linkedMapOf(
            "version" to STATE_VERSION,
            "sessionId" to session.id,
            "startedAt" to session.startedAtMillis,
            "updatedAt" to System.currentTimeMillis(),
            "status" to session.status.wireValue(),
            "release" to resolveRelease(),
            "environment" to environment,
            "userId" to userId,
            "sdkName" to AllStakVersion.SDK_NAME,
            "sdkVersion" to AllStakVersion.SDK_VERSION,
            "platform" to AllStakVersion.PLATFORM,
            "closed" to false,
        ))
    }

    private fun writeClosedState(session: Session, status: SessionStatus) {
        stateStore?.write(linkedMapOf(
            "version" to STATE_VERSION,
            "sessionId" to session.id,
            "startedAt" to session.startedAtMillis,
            "updatedAt" to System.currentTimeMillis(),
            "status" to status.wireValue(),
            "release" to resolveRelease(),
            "environment" to environment,
            "sdkName" to AllStakVersion.SDK_NAME,
            "sdkVersion" to AllStakVersion.SDK_VERSION,
            "platform" to AllStakVersion.PLATFORM,
            "closed" to true,
            "endedAt" to System.currentTimeMillis(),
        ))
    }

    companion object {
        const val PATH_START = "/ingest/v1/sessions/start"
        const val PATH_END = "/ingest/v1/sessions/end"
        private const val STATE_VERSION = 1
        private const val STATE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val RECOVERY_LOCK_MS = 30_000L
        private const val RECOVERY_MAX_ATTEMPTS = 3
    }
}

private fun JSONObject.toMap(): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out[key] = opt(key)
    }
    return out
}
