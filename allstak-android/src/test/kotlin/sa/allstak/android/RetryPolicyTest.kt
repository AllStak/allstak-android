package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.transport.RetryPolicy

class RetryPolicyTest {

    @Test
    fun `max attempts is five`() {
        assertEquals(5, RetryPolicy.maxAttempts())
    }

    @Test
    fun `first attempt has no delay`() {
        assertEquals(0, RetryPolicy.delayForAttempt(0))
    }

    @Test
    fun `later attempts include base delay plus bounded jitter`() {
        val d1 = RetryPolicy.delayForAttempt(1)
        assertTrue(d1 in 1000..1500, "expected 1000..1500 but was $d1")
        val d4 = RetryPolicy.delayForAttempt(4)
        assertTrue(d4 in 8000..8500, "expected 8000..8500 but was $d4")
    }

    @Test
    fun `retryable statuses`() {
        assertTrue(RetryPolicy.isRetryable(429))
        assertTrue(RetryPolicy.isRetryable(500))
        assertTrue(RetryPolicy.isRetryable(503))
        assertFalse(RetryPolicy.isRetryable(400))
        assertFalse(RetryPolicy.isRetryable(404))
    }

    @Test
    fun `client and auth errors`() {
        assertTrue(RetryPolicy.isClientError(400))
        assertTrue(RetryPolicy.isClientError(422))
        assertTrue(RetryPolicy.isAuthError(401))
        assertFalse(RetryPolicy.isAuthError(403))
    }

    @Test
    fun `retry-after parsing`() {
        assertEquals(120L, RetryPolicy.parseRetryAfterSeconds("120"))
        assertEquals(0L, RetryPolicy.parseRetryAfterSeconds(null))
        assertEquals(0L, RetryPolicy.parseRetryAfterSeconds("not-a-number"))
        // clamped to 300
        assertEquals(300L, RetryPolicy.parseRetryAfterSeconds("9999"))
    }
}
