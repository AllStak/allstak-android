package sa.allstak.android

/** Privacy-safe SDK diagnostics. Contains counters only, never telemetry payload data. */
data class AllStakDiagnostics(
    val eventsCaptured: Long = 0,
    val eventsSent: Long = 0,
    val eventsFailed: Long = 0,
    val eventsDropped: Long = 0,
    val eventsPersisted: Long = 0,
    val eventsReplayed: Long = 0,
    val queueSize: Int = 0,
    val retryAttempts: Long = 0,
    val rateLimitedCount: Long = 0,
    val compressedPayloads: Long = 0,
    val uncompressedPayloads: Long = 0,
    val compressionBytesSaved: Long = 0,
    val sanitizerRedactionCount: Long? = null,
    val activeTraceCount: Int = 0,
    val activeSpanCount: Int = 0,
    val breadcrumbCount: Int = 0,
    val sessionRecoveryCount: Long = 0,
    val disabled: Boolean = false,
)
