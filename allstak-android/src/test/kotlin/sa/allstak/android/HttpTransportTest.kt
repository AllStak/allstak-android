package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.transport.HttpSendOutcome
import sa.allstak.android.core.transport.HttpSender
import sa.allstak.android.core.transport.HttpTransport
import sa.allstak.android.core.transport.SendResult
import java.util.concurrent.atomic.AtomicInteger

/** Records what was sent without touching a real socket. */
private class RecordingSender(
    private val outcomes: List<HttpSendOutcome>,
) : HttpSender {
    val calls = AtomicInteger(0)
    var lastUrl: String? = null
    var lastApiKey: String? = null
    var lastBody: String? = null

    override fun post(url: String, apiKey: String, body: String): HttpSendOutcome {
        lastUrl = url
        lastApiKey = apiKey
        lastBody = body
        val i = calls.getAndIncrement()
        return outcomes[minOf(i, outcomes.size - 1)]
    }
}

class HttpTransportTest {

    @Test
    fun `202 is accepted on first attempt`() {
        val sender = RecordingSender(listOf(HttpSendOutcome(202)))
        val transport = HttpTransport("https://api.allstak.sa", "key", sender, sleeper = {})
        val result = transport.sendWithResult("/ingest/v1/errors", linkedMapOf("a" to 1))
        assertEquals(SendResult.ACCEPTED, result)
        assertEquals(1, sender.calls.get())
    }

    @Test
    fun `sends correct url api key and json body`() {
        val sender = RecordingSender(listOf(HttpSendOutcome(202)))
        val transport = HttpTransport("https://api.allstak.sa", "secret-key", sender, sleeper = {})
        transport.sendWithResult("/ingest/v1/logs", linkedMapOf("level" to "info", "skip" to null))
        assertEquals("https://api.allstak.sa/ingest/v1/logs", sender.lastUrl)
        assertEquals("secret-key", sender.lastApiKey)
        // null fields are omitted in the body
        assertEquals("""{"level":"info"}""", sender.lastBody)
    }

    @Test
    fun `401 disables the sdk and is permanent`() {
        val sender = RecordingSender(listOf(HttpSendOutcome(401)))
        val transport = HttpTransport("https://api.allstak.sa", "bad", sender, sleeper = {})
        val result = transport.sendWithResult("/ingest/v1/errors", linkedMapOf("a" to 1))
        assertEquals(SendResult.PERMANENT, result)
        assertTrue(transport.isDisabled)
        // A subsequent send is dropped without a network call.
        val before = sender.calls.get()
        val r2 = transport.sendWithResult("/ingest/v1/errors", linkedMapOf("a" to 1))
        assertEquals(SendResult.PERMANENT, r2)
        assertEquals(before, sender.calls.get())
    }

    @Test
    fun `400 client error is permanent without retries`() {
        val sender = RecordingSender(listOf(HttpSendOutcome(400)))
        val transport = HttpTransport("https://api.allstak.sa", "key", sender, sleeper = {})
        val result = transport.sendWithResult("/ingest/v1/errors", linkedMapOf("a" to 1))
        assertEquals(SendResult.PERMANENT, result)
        assertEquals(1, sender.calls.get())
        assertFalse(transport.isDisabled)
    }

    @Test
    fun `5xx retries then succeeds`() {
        val sender = RecordingSender(
            listOf(HttpSendOutcome(503), HttpSendOutcome(503), HttpSendOutcome(202)),
        )
        val transport = HttpTransport("https://api.allstak.sa", "key", sender, sleeper = {})
        val result = transport.sendWithResult("/ingest/v1/errors", linkedMapOf("a" to 1))
        assertEquals(SendResult.ACCEPTED, result)
        assertEquals(3, sender.calls.get())
    }

    @Test
    fun `exhausted retries is transient for spooling`() {
        val sender = RecordingSender(listOf(HttpSendOutcome(500)))
        val transport = HttpTransport("https://api.allstak.sa", "key", sender, sleeper = {})
        val result = transport.sendWithResult("/ingest/v1/errors", linkedMapOf("a" to 1))
        assertEquals(SendResult.TRANSIENT, result)
        assertEquals(5, sender.calls.get())
    }

    @Test
    fun `network error retries and is transient`() {
        val sender = RecordingSender(listOf(HttpSendOutcome(-1)))
        val transport = HttpTransport("https://api.allstak.sa", "key", sender, sleeper = {})
        val result = transport.sendWithResult("/ingest/v1/errors", linkedMapOf("a" to 1))
        assertEquals(SendResult.TRANSIENT, result)
        assertEquals(5, sender.calls.get())
    }

    @Test
    fun `raw json is replayed byte for byte`() {
        val sender = RecordingSender(listOf(HttpSendOutcome(202)))
        val transport = HttpTransport("https://api.allstak.sa", "key", sender, sleeper = {})
        val body = """{"already":"scrubbed"}"""
        transport.sendRawJson("/ingest/v1/errors", body)
        assertEquals(body, sender.lastBody)
    }
}
