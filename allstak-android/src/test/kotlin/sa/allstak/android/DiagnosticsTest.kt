package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.AllStakClient
import sa.allstak.android.core.transport.HttpSendOutcome
import sa.allstak.android.core.transport.HttpSender
import sa.allstak.android.core.transport.HttpTransport

private class DiagnosticsSender : HttpSender {
    override fun post(url: String, apiKey: String, body: String): HttpSendOutcome =
        HttpSendOutcome(202)
}

class DiagnosticsTest {

    @Test
    fun `client diagnostics include counters and no sensitive payload data`() {
        val options = AllStakOptions().apply {
            apiKey = "ask_test"
            host = "https://h.test"
            environment = "test"
            release = "1.0.0"
            enableAutoSessionTracking = false
            enableOfflineQueue = false
        }
        val client = AllStakClient(
            options,
            HttpTransport("https://h.test", "ask_test", DiagnosticsSender(), sleeper = {}),
            spoolDir = null,
            randomSource = { 0.0 },
        )

        client.addBreadcrumb(
            "log",
            "password is secret-value",
            "info",
            linkedMapOf("Authorization" to "Bearer secret-value"),
        )
        client.captureLog("info", "secret-value", linkedMapOf("token" to "secret-value"))
        val diagnostics = client.getDiagnostics()

        val raw = diagnostics.toString()
        assertFalse(raw.contains("secret-value"))
        assertFalse(raw.contains("Authorization"))
        assertEquals(1, diagnostics.breadcrumbCount)
        assertEquals(1, diagnostics.queueSize)
        assertEquals(0L, diagnostics.eventsCaptured)
        assertFalse(diagnostics.disabled)
        client.shutdown()
    }

    @Test
    fun `empty public diagnostics before init`() {
        val diagnostics = AllStak.getDiagnostics()
        assertEquals(0L, diagnostics.eventsCaptured)
        assertEquals(0, diagnostics.queueSize)
        assertTrue(diagnostics.sanitizerRedactionCount == null)
    }
}
