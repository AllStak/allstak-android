package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.internal.TraceIds

class TraceIdsTest {

    @Test
    fun `generated ids are lowercase W3C hex`() {
        val traceId = TraceIds.newTraceId()
        val spanId = TraceIds.newSpanId()

        assertTrue(traceId.matches(Regex("^[0-9a-f]{32}$")))
        assertTrue(spanId.matches(Regex("^[0-9a-f]{16}$")))
        assertTrue(traceId.any { it != '0' })
        assertTrue(spanId.any { it != '0' })
    }

    @Test
    fun `uuid-form trace id normalizes to lowercase hex`() {
        assertEquals(
            "550e8400e29b41d4a716446655440000",
            TraceIds.normalizeTraceId("550E8400-E29B-41D4-A716-446655440000"),
        )
    }

    @Test
    fun `invalid and all-zero ids are rejected`() {
        assertNull(TraceIds.normalizeTraceId("0".repeat(32)))
        assertNull(TraceIds.normalizeTraceId("not-a-trace-id"))
        assertNull(TraceIds.normalizeSpanId("0".repeat(16)))
        assertNull(TraceIds.normalizeSpanId("g".repeat(16)))
    }

    @Test
    fun `traceparent is wire valid and normalizes inputs`() {
        val header = TraceIds.traceparent(
            "550E8400-E29B-41D4-A716-446655440000",
            "ABCDEFABCDEF1234",
            "01",
        )

        assertEquals(
            "00-550e8400e29b41d4a716446655440000-abcdefabcdef1234-01",
            header,
        )
    }
}
