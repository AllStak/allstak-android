package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.AllStakClient
import sa.allstak.android.core.transport.HttpSendOutcome
import sa.allstak.android.core.transport.HttpSender
import sa.allstak.android.core.transport.HttpTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private class TraceRecordingSender : HttpSender {
    private val latch = CountDownLatch(1)
    val body = AtomicReference<String?>()

    override fun post(url: String, apiKey: String, body: String): HttpSendOutcome {
        this.body.set(body)
        latch.countDown()
        return HttpSendOutcome(202)
    }

    fun awaitBody(): String {
        assertTrue(latch.await(2, TimeUnit.SECONDS), "timed out waiting for send")
        return body.get() ?: error("missing body")
    }
}

class TraceNormalizationCaptureTest {

    @Test
    fun `captureSpan writes W3C normalized trace and span ids`() {
        val sender = TraceRecordingSender()
        val options = AllStakOptions().apply {
            apiKey = "ask_test"
            host = "https://h.test"
            environment = "test"
            release = "1.0.0"
            enableAutoSessionTracking = false
            enableOfflineQueue = false
        }
        val transport = HttpTransport("https://h.test", "ask_test", sender, sleeper = {})
        val client = AllStakClient(options, transport, spoolDir = null, randomSource = { 0.0 })

        client.captureSpan(
            traceId = "550E8400-E29B-41D4-A716-446655440000",
            spanId = "ABCDEFABCDEF1234",
            parentSpanId = "0000000000000000",
            operation = "test.span",
            description = "trace normalization",
            status = "ok",
            durationMs = 1,
            startTimeMillis = 1,
            endTimeMillis = 2,
            preSampled = true,
        )

        val body = sender.awaitBody()
        assertTrue(body.contains("\"traceId\":\"550e8400e29b41d4a716446655440000\""))
        assertTrue(body.contains("\"spanId\":\"abcdefabcdef1234\""))
        assertTrue(body.contains("\"parentSpanId\":\"\""))
        assertFalse(body.contains("550E8400-E29B"))
        assertFalse(body.contains("ABCDEFABCDEF1234"))
        client.shutdown()
    }
}
