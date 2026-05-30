package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.internal.Json
import sa.allstak.android.core.model.Breadcrumb
import sa.allstak.android.core.model.DatabaseQueryItem
import sa.allstak.android.core.model.ErrorEvent
import sa.allstak.android.core.model.Frame
import sa.allstak.android.core.model.HttpRequestItem
import sa.allstak.android.core.model.LogEvent
import sa.allstak.android.core.model.RequestContext
import sa.allstak.android.core.model.UserContext

/**
 * Verifies the camelCase, NON_NULL wire shape matches the platform ingest
 * contract field-for-field.
 */
class ModelSerializationTest {

    @Test
    fun `error event serializes camelCase keys and omits nulls`() {
        val event = ErrorEvent(
            exceptionClass = "java.lang.IllegalStateException",
            message = "boom",
            stackTrace = listOf("java.lang.IllegalStateException: boom"),
            level = "error",
            environment = "production",
            release = "1.0.0+1",
            sessionId = "sess-1",
            traceId = null,
            user = UserContext.ofId("user-7"),
            metadata = linkedMapOf("k" to "v"),
            requestContext = null,
            breadcrumbs = null,
            platform = "android",
            sdkName = "allstak-android",
            sdkVersion = "0.2.0",
            dist = null,
            frames = listOf(Frame(filename = "Main.kt", function = "main", lineno = 12, inApp = true, platform = "android")),
        )
        val json = Json.encodeObject(event.toMap())
        assertTrue(json.contains("\"exceptionClass\":\"java.lang.IllegalStateException\""))
        assertTrue(json.contains("\"sdkName\":\"allstak-android\""))
        assertTrue(json.contains("\"sdkVersion\":\"0.2.0\""))
        assertTrue(json.contains("\"platform\":\"android\""))
        assertTrue(json.contains("\"user\":{\"id\":\"user-7\"}"))
        assertTrue(json.contains("\"frames\":[{\"filename\":\"Main.kt\""))
        // traceId/dist/breadcrumbs are null -> omitted
        assertFalse(json.contains("traceId"))
        assertFalse(json.contains("\"dist\""))
        assertFalse(json.contains("breadcrumbs"))
    }

    @Test
    fun `http request batch wire envelope`() {
        val item = HttpRequestItem(
            traceId = "t1",
            requestId = "r1",
            direction = "outbound",
            method = "GET",
            host = "api.example.com",
            path = "/v1/users",
            statusCode = 200,
            durationMs = 42,
        )
        val payload = linkedMapOf<String, Any?>("requests" to listOf(item.toMap()))
        val json = Json.encodeObject(payload)
        assertTrue(json.startsWith("""{"requests":[{"""))
        assertTrue(json.contains("\"statusCode\":200"))
        assertTrue(json.contains("\"durationMs\":42"))
        assertTrue(json.contains("\"direction\":\"outbound\""))
    }

    @Test
    fun `db query batch wire envelope`() {
        val q = DatabaseQueryItem(
            normalizedQuery = "SELECT * FROM users WHERE id = ?",
            queryHash = "abcd1234",
            queryType = "SELECT",
            durationMs = 5,
            timestampMillis = 1000,
            status = "success",
            databaseType = "sqlite",
        )
        val payload = linkedMapOf<String, Any?>("queries" to listOf(q.toMap()))
        val json = Json.encodeObject(payload)
        assertTrue(json.contains("\"normalizedQuery\":\"SELECT * FROM users WHERE id = ?\""))
        assertTrue(json.contains("\"queryType\":\"SELECT\""))
        assertTrue(json.contains("\"databaseType\":\"sqlite\""))
    }

    @Test
    fun `log event serializes expected keys`() {
        val log = LogEvent(level = "info", message = "hi", service = "svc", environment = "staging")
        val json = Json.encodeObject(log.toMap())
        assertTrue(json.contains("\"level\":\"info\""))
        assertTrue(json.contains("\"message\":\"hi\""))
        assertTrue(json.contains("\"service\":\"svc\""))
        assertTrue(json.contains("\"environment\":\"staging\""))
    }

    @Test
    fun `request context omits internal traceId field`() {
        val rc = RequestContext("POST", "/p", "h", 201, "ua", "trace-xyz")
        val json = Json.encodeObject(rc.toMap())
        assertTrue(json.contains("\"statusCode\":201"))
        assertTrue(json.contains("\"userAgent\":\"ua\""))
        assertFalse(json.contains("traceId"))
    }

    @Test
    fun `breadcrumb normalizes invalid type and level`() {
        val b = Breadcrumb("not-a-type", "msg", "loud", null)
        assertEquals("default", b.type)
        assertEquals("info", b.level)
        assertEquals("default", b.category)
        val valid = Breadcrumb("http", "GET /x", "warn", linkedMapOf("k" to 1))
        assertEquals("http", valid.type)
        assertEquals("warn", valid.level)
    }
}
