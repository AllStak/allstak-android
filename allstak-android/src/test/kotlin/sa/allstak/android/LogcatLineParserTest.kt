package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import sa.allstak.android.instrument.LogcatLineParser

/**
 * Host-JVM coverage for the threadtime logcat line parser that backs the
 * automatic log-capture instrumentation. No device / no reader needed.
 */
class LogcatLineParserTest {

    private val pid = 4242

    @Test
    fun `parses a warn line scoped to our pid`() {
        val line = "05-30 12:00:01.123  4242  4242 W MyTag: something happened"
        val parsed = LogcatLineParser.parse(line, pid)!!
        assertEquals(LogcatLineParser.PRIORITY_WARN, parsed.priority)
        assertEquals("MyTag", parsed.tag)
        assertEquals("something happened", parsed.message)
        assertEquals("warn", LogcatLineParser.levelFor(parsed.priority))
    }

    @Test
    fun `parses an error line and maps level`() {
        val line = "05-30 12:00:01.123  4242  4250 E Net: connection reset"
        val parsed = LogcatLineParser.parse(line, pid)!!
        assertEquals("error", LogcatLineParser.levelFor(parsed.priority))
        assertEquals("Net", parsed.tag)
    }

    @Test
    fun `assert priority maps to fatal`() {
        val line = "05-30 12:00:01.123  4242  4250 A Crash: assert"
        val parsed = LogcatLineParser.parse(line, pid)!!
        assertEquals("fatal", LogcatLineParser.levelFor(parsed.priority))
    }

    @Test
    fun `ignores lines from other processes`() {
        val line = "05-30 12:00:01.123  9999  9999 E Other: not ours"
        assertNull(LogcatLineParser.parse(line, pid))
    }

    @Test
    fun `ignores header and malformed lines`() {
        assertNull(LogcatLineParser.parse("--------- beginning of main", pid))
        assertNull(LogcatLineParser.parse("", pid))
        assertNull(LogcatLineParser.parse("garbage", pid))
    }

    @Test
    fun `handles a message with no tag separator`() {
        val line = "05-30 12:00:01.123  4242  4242 I plain message without colon"
        val parsed = LogcatLineParser.parse(line, pid)!!
        assertEquals("info", LogcatLineParser.levelFor(parsed.priority))
        // No "TAG: " separator -> whole remainder is the message.
        assertEquals("plain message without colon", parsed.message)
    }
}
