package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.session.SessionStateStore
import sa.allstak.android.core.session.SessionStatus
import sa.allstak.android.core.session.SessionTracker

class SessionTrackerTest {

    private class Recorder {
        val sent = mutableListOf<Pair<String, Map<String, Any?>>>()
        val send: (String, Map<String, Any?>) -> Unit = { p, m -> sent.add(p to m) }
    }

    private class MemoryStore(initial: Map<String, Any?>? = null) : SessionStateStore {
        var state: Map<String, Any?>? = initial
        override fun read(): Map<String, Any?>? = state
        override fun write(state: Map<String, Any?>) {
            this.state = LinkedHashMap(state)
        }
        override fun clear() {
            state = null
        }
    }

    @Test
    fun `start posts a session start envelope with sdk identity`() {
        val rec = Recorder()
        val tracker = SessionTracker("production", "1.0.0+1", transport = null, send = rec.send)
        val session = tracker.start("user-1")

        assertNotNull(tracker.currentSessionId())
        assertEquals(1, rec.sent.size)
        val (path, payload) = rec.sent[0]
        assertEquals(SessionTracker.PATH_START, path)
        assertEquals(session.id, payload["sessionId"])
        assertEquals("production", payload["environment"])
        assertEquals("1.0.0+1", payload["release"])
        assertEquals("user-1", payload["userId"])
        assertEquals("allstak-android", payload["sdkName"])
        assertEquals("android", payload["platform"])
    }

    @Test
    fun `errored transition reflected in end status`() {
        val rec = Recorder()
        val tracker = SessionTracker("production", "1.0.0", transport = null, send = rec.send)
        tracker.start(null)
        tracker.recordError()
        tracker.end()

        val endCall = rec.sent.last()
        assertEquals(SessionTracker.PATH_END, endCall.first)
        assertEquals(SessionStatus.ERRORED.wireValue(), endCall.second["status"])
    }

    @Test
    fun `crash transition reflected in end status`() {
        val rec = Recorder()
        val tracker = SessionTracker("production", "1.0.0", transport = null, send = rec.send)
        tracker.start(null)
        tracker.recordCrash()
        tracker.end()
        assertEquals(SessionStatus.CRASHED.wireValue(), rec.sent.last().second["status"])
    }

    @Test
    fun `start is idempotent for the active session`() {
        val rec = Recorder()
        val tracker = SessionTracker("production", "1.0.0", transport = null, send = rec.send)
        val s1 = tracker.start(null)
        val s2 = tracker.start(null)
        assertEquals(s1.id, s2.id)
        // Only one start envelope.
        assertEquals(1, rec.sent.count { it.first == SessionTracker.PATH_START })
    }

    @Test
    fun `end clears the active session`() {
        val rec = Recorder()
        val tracker = SessionTracker("production", "1.0.0", transport = null, send = rec.send)
        tracker.start(null)
        tracker.end()
        assertNull(tracker.currentSessionId())
        // end is idempotent — a second call sends nothing more.
        val countAfterFirstEnd = rec.sent.size
        tracker.end()
        assertEquals(countAfterFirstEnd, rec.sent.size)
    }

    @Test
    fun `falls back to sdk version when no release`() {
        val rec = Recorder()
        val tracker = SessionTracker("production", null, transport = null, send = rec.send)
        tracker.start(null)
        assertTrue((rec.sent[0].second["release"] as String).isNotBlank())
    }

    @Test
    fun `clean shutdown does not recover abnormal session on next start`() {
        val store = MemoryStore()
        val first = Recorder()
        SessionTracker("production", "1.0.0", transport = null, stateStore = store, send = first.send)
            .also {
                it.start(null)
                it.end()
            }

        val second = Recorder()
        SessionTracker("production", "1.0.0", transport = null, stateStore = store, send = second.send)
            .start(null)

        assertEquals(0, second.sent.count { it.first == SessionTracker.PATH_END })
        assertEquals(1, second.sent.count { it.first == SessionTracker.PATH_START })
    }

    @Test
    fun `open session is reported abnormal on next start`() {
        val store = MemoryStore()
        val session = SessionTracker("production", "1.0.0", transport = null, stateStore = store, send = Recorder().send)
            .start(null)

        val second = Recorder()
        SessionTracker("production", "1.0.0", transport = null, stateStore = store, send = second.send)
            .start(null)

        val recovered = second.sent.first { it.first == SessionTracker.PATH_END }.second
        assertEquals(session.id, recovered["sessionId"])
        assertEquals(SessionStatus.ABNORMAL.wireValue(), recovered["status"])
    }

    @Test
    fun `crashed open session is reported crashed on next start`() {
        val store = MemoryStore()
        val tracker = SessionTracker("production", "1.0.0", transport = null, stateStore = store, send = Recorder().send)
        val session = tracker.start(null)
        tracker.recordCrash()

        val second = Recorder()
        SessionTracker("production", "1.0.0", transport = null, stateStore = store, send = second.send)
            .start(null)

        val recovered = second.sent.first { it.first == SessionTracker.PATH_END }.second
        assertEquals(session.id, recovered["sessionId"])
        assertEquals(SessionStatus.CRASHED.wireValue(), recovered["status"])
    }

    @Test
    fun `corrupt session state is cleared safely`() {
        val store = MemoryStore(mapOf("version" to 1, "bad" to "shape"))
        val rec = Recorder()
        SessionTracker("production", "1.0.0", transport = null, stateStore = store, send = rec.send)
            .start(null)

        assertEquals(0, rec.sent.count { it.first == SessionTracker.PATH_END })
        assertEquals(1, rec.sent.count { it.first == SessionTracker.PATH_START })
    }

    @Test
    fun `recovered abnormal session is not reported twice`() {
        val store = MemoryStore()
        SessionTracker("production", "1.0.0", transport = null, stateStore = store, send = Recorder().send)
            .start(null)

        val second = Recorder()
        val secondTracker = SessionTracker("production", "1.0.0", transport = null, stateStore = store, send = second.send)
        secondTracker.start(null)
        secondTracker.end()

        val third = Recorder()
        SessionTracker("production", "1.0.0", transport = null, stateStore = store, send = third.send)
            .start(null)

        assertEquals(1, second.sent.count {
            it.first == SessionTracker.PATH_END && it.second["status"] == SessionStatus.ABNORMAL.wireValue()
        })
        assertEquals(0, third.sent.count {
            it.first == SessionTracker.PATH_END && it.second["status"] == SessionStatus.ABNORMAL.wireValue()
        })
    }
}
