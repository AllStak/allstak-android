package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.masking.DataMasker

class DataMaskerTest {

    @Test
    fun `redacts sensitive metadata keys`() {
        val input = linkedMapOf<String, Any?>(
            "username" to "alice",
            "password" to "hunter2",
            "token" to "abc",
            "safe" to "ok",
        )
        val masked = DataMasker.maskMetadata(input)!!
        assertEquals("alice", masked["username"])
        assertEquals("[MASKED]", masked["password"])
        assertEquals("[MASKED]", masked["token"])
        assertEquals("ok", masked["safe"])
    }

    @Test
    fun `scrubs email and ipv4 when pii disabled`() {
        val scrubbed = DataMasker.scrubValue("contact alice@example.com from 192.168.1.10", false)!!
        assertFalse(scrubbed.contains("alice@example.com"))
        assertFalse(scrubbed.contains("192.168.1.10"))
        assertTrue(scrubbed.contains("[REDACTED]"))
    }

    @Test
    fun `keeps email and ipv4 when pii enabled`() {
        val kept = DataMasker.scrubValue("contact alice@example.com from 192.168.1.10", true)!!
        assertTrue(kept.contains("alice@example.com"))
        assertTrue(kept.contains("192.168.1.10"))
    }

    @Test
    fun `redacts a luhn-valid visa even with pii enabled`() {
        // 4111111111111111 is the canonical Visa test number (Luhn-valid).
        val out = DataMasker.scrubValue("card 4111 1111 1111 1111 end", true)!!
        assertFalse(out.contains("4111"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun `leaves non-card numeric ids intact`() {
        // A 16-digit id that is not a valid card / has no issuer prefix.
        val out = DataMasker.scrubValue("order 1234567890123456 placed", false)!!
        assertTrue(out.contains("1234567890123456"))
    }

    @Test
    fun `redacts hyphenated ssn always`() {
        val out = DataMasker.scrubValue("ssn 123-45-6789 noted", true)!!
        assertFalse(out.contains("123-45-6789"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun `strips query string from path`() {
        assertEquals("/v1/users", DataMasker.stripSensitiveQueryParams("/v1/users?token=secret&x=1"))
        assertEquals("/plain", DataMasker.stripSensitiveQueryParams("/plain"))
    }

    @Test
    fun `nested metadata maps and lists are scrubbed`() {
        val input = linkedMapOf<String, Any?>(
            "nested" to linkedMapOf<String, Any?>("email" to "alice@example.com"),
            "list" to listOf("bob@example.com", "fine"),
        )
        val masked = DataMasker.maskMetadata(input, false)!!
        @Suppress("UNCHECKED_CAST")
        val nested = masked["nested"] as Map<String, Any?>
        // key "email" is sensitive -> blanked entirely
        assertEquals("[MASKED]", nested["email"])
        @Suppress("UNCHECKED_CAST")
        val list = masked["list"] as List<Any?>
        assertEquals("[REDACTED]", list[0])
        assertEquals("fine", list[1])
    }

    @Test
    fun `sensitive header detection`() {
        assertTrue(DataMasker.isSensitiveHeader("Authorization"))
        assertTrue(DataMasker.isSensitiveHeader("X-AllStak-Key"))
        assertFalse(DataMasker.isSensitiveHeader("Accept"))
    }

    @Test
    fun `luhn validity`() {
        assertTrue(DataMasker.isLuhnValid("4111111111111111"))
        assertFalse(DataMasker.isLuhnValid("4111111111111112"))
    }
}
