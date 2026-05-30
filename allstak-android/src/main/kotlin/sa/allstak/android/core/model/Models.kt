package sa.allstak.android.core.model

import sa.allstak.android.core.internal.Json
import sa.allstak.android.core.internal.Time

/**
 * Wire models mirroring the platform ingest v1 contract one-to-one. Each model
 * builds an insertion-ordered `LinkedHashMap` via [toMap]; the transport's
 * [Json] encoder omits null values, reproducing the JVM SDK's
 * `@JsonInclude(NON_NULL)` shape byte-for-byte. All keys are camelCase.
 */

/** `user{id,email,ip}` on the error payload. */
data class UserContext(
    val id: String? = null,
    val email: String? = null,
    val ip: String? = null,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to id,
        "email" to email,
        "ip" to ip,
    )

    companion object {
        fun ofId(id: String?): UserContext = UserContext(id = id)
    }
}

/** `requestContext{method,path,host,statusCode,userAgent}`. */
data class RequestContext(
    val method: String? = null,
    val path: String? = null,
    val host: String? = null,
    val statusCode: Int? = null,
    val userAgent: String? = null,
    /** Not serialized — used internally to copy onto the event traceId. */
    val traceId: String? = null,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "method" to method,
        "path" to path,
        "host" to host,
        "statusCode" to statusCode,
        "userAgent" to userAgent,
    )
}

private val VALID_BREADCRUMB_TYPES =
    setOf("http", "log", "ui", "navigation", "query", "default")
private val VALID_BREADCRUMB_LEVELS = setOf("info", "warn", "error", "debug")

/**
 * `breadcrumbs[{timestamp,type,category,message,level,data}]`. The constructor
 * normalizes type/level/category exactly as the JVM model does so the dashboard
 * always has something to group by.
 */
class Breadcrumb(
    type: String?,
    category: String?,
    val message: String?,
    level: String?,
    val data: Map<String, Any?>?,
) {
    val timestamp: String = Time.nowIso()
    val type: String =
        if (type != null && VALID_BREADCRUMB_TYPES.contains(type)) type else "default"
    val category: String =
        if (!category.isNullOrBlank()) category else this.type
    val level: String =
        if (level != null && VALID_BREADCRUMB_LEVELS.contains(level)) level else "info"

    constructor(type: String?, message: String?, level: String?, data: Map<String, Any?>?) :
        this(type, null, message, level, data)

    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "timestamp" to timestamp,
        "type" to type,
        "category" to category,
        "message" to message,
        "level" to level,
        "data" to data,
    )
}

/** Structured stack frame: `frames[{filename,absPath,function,lineno,colno,inApp,platform,debugId}]`. */
data class Frame(
    val filename: String? = null,
    val absPath: String? = null,
    val function: String? = null,
    val lineno: Int? = null,
    val colno: Int? = null,
    val inApp: Boolean? = null,
    val platform: String? = null,
    val debugId: String? = null,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "filename" to filename,
        "absPath" to absPath,
        "function" to function,
        "lineno" to lineno,
        "colno" to colno,
        "inApp" to inApp,
        "platform" to platform,
        "debugId" to debugId,
    )
}

/** `POST /ingest/v1/errors` body. */
data class ErrorEvent(
    val exceptionClass: String?,
    val message: String?,
    val stackTrace: List<String>?,
    val level: String?,
    val environment: String?,
    val release: String?,
    val sessionId: String?,
    val traceId: String?,
    val user: UserContext?,
    val metadata: Map<String, Any?>?,
    val requestContext: RequestContext?,
    val breadcrumbs: List<Breadcrumb>?,
    val platform: String?,
    val sdkName: String?,
    val sdkVersion: String?,
    val dist: String?,
    val frames: List<Frame>?,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "exceptionClass" to exceptionClass,
        "message" to message,
        "stackTrace" to stackTrace,
        "level" to level,
        "environment" to environment,
        "release" to release,
        "sessionId" to sessionId,
        "traceId" to traceId,
        "user" to user?.toMap(),
        "metadata" to metadata,
        "requestContext" to requestContext?.toMap(),
        "breadcrumbs" to breadcrumbs?.map { it.toMap() },
        "platform" to platform,
        "sdkName" to sdkName,
        "sdkVersion" to sdkVersion,
        "dist" to dist,
        "frames" to frames?.map { it.toMap() },
    )
}

/** An item inside `POST /ingest/v1/http-requests` `requests[]`. */
data class HttpRequestItem(
    val traceId: String? = null,
    val requestId: String? = null,
    val spanId: String? = null,
    val parentSpanId: String? = null,
    val direction: String? = null,
    val method: String? = null,
    val host: String? = null,
    val path: String? = null,
    val statusCode: Int = 0,
    val durationMs: Long = 0,
    val requestSize: Long = 0,
    val responseSize: Long = 0,
    val userId: String? = null,
    val errorFingerprint: String? = null,
    val timestamp: String? = null,
    val environment: String? = null,
    val release: String? = null,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "traceId" to traceId,
        "requestId" to requestId,
        "spanId" to spanId,
        "parentSpanId" to parentSpanId,
        "direction" to direction,
        "method" to method,
        "host" to host,
        "path" to path,
        "statusCode" to statusCode,
        "durationMs" to durationMs,
        "requestSize" to requestSize,
        "responseSize" to responseSize,
        "userId" to userId,
        "errorFingerprint" to errorFingerprint,
        "timestamp" to timestamp,
        "environment" to environment,
        "release" to release,
    )
}

/** `POST /ingest/v1/logs` body. */
data class LogEvent(
    val level: String?,
    val message: String?,
    val service: String? = null,
    val traceId: String? = null,
    val environment: String? = null,
    val spanId: String? = null,
    val requestId: String? = null,
    val userId: String? = null,
    val errorId: String? = null,
    val metadata: Map<String, Any?>? = null,
    val release: String? = null,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "level" to level,
        "message" to message,
        "service" to service,
        "traceId" to traceId,
        "environment" to environment,
        "spanId" to spanId,
        "requestId" to requestId,
        "userId" to userId,
        "errorId" to errorId,
        "metadata" to metadata,
        "release" to release,
    )
}

/** An item inside `POST /ingest/v1/db` `queries[]`. */
data class DatabaseQueryItem(
    val normalizedQuery: String? = null,
    val queryHash: String? = null,
    val queryType: String? = null,
    val durationMs: Long = 0,
    val timestampMillis: Long = 0,
    val status: String? = null,
    val errorMessage: String? = null,
    val databaseName: String? = null,
    val databaseType: String? = null,
    val service: String? = null,
    val environment: String? = null,
    val traceId: String? = null,
    val spanId: String? = null,
    val release: String? = null,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "normalizedQuery" to normalizedQuery,
        "queryHash" to queryHash,
        "queryType" to queryType,
        "durationMs" to durationMs,
        "timestampMillis" to timestampMillis,
        "status" to status,
        "errorMessage" to errorMessage,
        "databaseName" to databaseName,
        "databaseType" to databaseType,
        "service" to service,
        "environment" to environment,
        "traceId" to traceId,
        "spanId" to spanId,
        "release" to release,
    )
}

/** `POST /ingest/v1/heartbeat` body. */
data class HeartbeatEvent(
    val slug: String?,
    val status: String?,
    val durationMs: Long,
    val message: String? = null,
    val environment: String? = null,
    val release: String? = null,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "slug" to slug,
        "status" to status,
        "durationMs" to durationMs,
        "message" to message,
        "environment" to environment,
        "release" to release,
    )
}
