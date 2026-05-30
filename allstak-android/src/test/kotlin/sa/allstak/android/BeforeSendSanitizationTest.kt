package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.AllStakClient
import sa.allstak.android.core.model.ErrorEvent
import sa.allstak.android.core.transport.HttpSendOutcome
import sa.allstak.android.core.transport.HttpSender
import sa.allstak.android.core.transport.HttpTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private class BeforeSendRecordingSender : HttpSender {
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

class BeforeSendSanitizationTest {

    @Test
    fun `beforeSend receives sanitized event and cannot reintroduce secrets`() {
        val seen = AtomicReference<ErrorEvent?>()
        val sender = BeforeSendRecordingSender()
        val options = AllStakOptions().apply {
            apiKey = "ask_test"
            host = "https://h.test"
            environment = "test"
            release = "1.0.0"
            enableAutoSessionTracking = false
            enableOfflineQueue = false
            beforeSend = { event ->
                val e = event as ErrorEvent
                seen.set(e)
                e.copy(
                    message = "mutated 4111111111111111",
                    metadata = linkedMapOf(
                        "Authorization" to "Bearer abc",
                        "nested" to linkedMapOf("token" to "secret-token"),
                    ),
                )
            }
        }
        val transport = HttpTransport("https://h.test", "ask_test", sender, sleeper = {})
        val client = AllStakClient(options, transport, spoolDir = null, randomSource = { 0.0 })

        client.captureException(
            RuntimeException("card 4111111111111111"),
            metadata = linkedMapOf(
                "Authorization" to "Bearer abc",
                "nested" to linkedMapOf("apiKey" to "key-123"),
            ),
        )

        val body = sender.awaitBody()
        assertNotNull(seen.get())
        val hookEvent = seen.get()!!
        assertEquals("card [REDACTED]", hookEvent.message)
        assertEquals("[MASKED]", hookEvent.metadata?.get("Authorization"))
        @Suppress("UNCHECKED_CAST")
        val hookNested = hookEvent.metadata?.get("nested") as Map<String, Any?>
        assertEquals("[MASKED]", hookNested["apiKey"])

        assertTrue(body.contains("\"message\":\"mutated [REDACTED]\""))
        assertTrue(body.contains("\"Authorization\":\"[MASKED]\""))
        assertTrue(body.contains("\"token\":\"[MASKED]\""))
        client.shutdown()
    }
}
