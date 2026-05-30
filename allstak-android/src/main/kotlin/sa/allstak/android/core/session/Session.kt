package sa.allstak.android.core.session

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Lifecycle status of a release-health session. Wire values are lowercase. */
enum class SessionStatus(val wire: String) {
    OK("ok"),
    ERRORED("errored"),
    CRASHED("crashed"),
    ABNORMAL("abnormal");

    fun wireValue(): String = wire
}

/**
 * A single release-health session. On mobile, a session spans a foreground
 * lifetime: started when the app comes to the foreground, ended when it is
 * backgrounded past the timeout. Mutable through atomics so the uncaught
 * handler can mark it crashed from any thread.
 */
class Session(
    val id: String = UUID.randomUUID().toString(),
    internal val startedAtMillis: Long = System.currentTimeMillis(),
) {
    private val statusRef = AtomicReference(SessionStatus.OK)
    private val errorCount = AtomicInteger()

    val status: SessionStatus get() = statusRef.get()
    fun getErrorCount(): Int = errorCount.get()

    fun recordError() {
        errorCount.incrementAndGet()
        statusRef.compareAndSet(SessionStatus.OK, SessionStatus.ERRORED)
    }

    fun recordCrash() {
        statusRef.set(SessionStatus.CRASHED)
        errorCount.incrementAndGet()
    }

    fun durationMs(): Long = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L)
}
