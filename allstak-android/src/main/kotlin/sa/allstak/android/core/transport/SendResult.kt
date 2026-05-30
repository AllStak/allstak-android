package sa.allstak.android.core.transport

/**
 * Outcome of a transport send. Lets the offline spool decide whether a failed
 * envelope should be PERSISTED for a later retry (transient) or DROPPED
 * (permanent). Mirrors the JVM SDK's `SendResult`.
 */
enum class SendResult {
    /** Accepted by the backend (2xx). Remove from the spool. */
    ACCEPTED,

    /** Could not be delivered now but might later — network error, timeout,
     *  retries exhausted, or a retryable server status. Keep on the spool. */
    TRANSIENT,

    /** Will never be accepted as-is — a non-retryable 4xx (other than 429) or
     *  the SDK is disabled after a 401. Do not persist. */
    PERMANENT;

    val isAccepted: Boolean get() = this == ACCEPTED
}
