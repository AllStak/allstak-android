package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sa.allstak.android.core.spool.EventSpool
import java.io.File

class EventSpoolTest {

    @Test
    fun `persist and load round-trips path and body`(@TempDir dir: File) {
        val spool = EventSpool(File(dir, "spool"))
        assertTrue(spool.isAvailable)
        spool.persist("/ingest/v1/errors", """{"a":1}""")
        spool.persist("/ingest/v1/logs", """{"level":"info"}""")

        val handles = spool.load()
        assertEquals(2, handles.size)
        // Ordering is by sequence (filename) so it is FIFO.
        assertEquals("/ingest/v1/errors", handles[0].path)
        assertEquals("""{"a":1}""", handles[0].body)
        assertEquals("/ingest/v1/logs", handles[1].path)
        assertEquals("""{"level":"info"}""", handles[1].body)
    }

    @Test
    fun `remove deletes the entry`(@TempDir dir: File) {
        val spool = EventSpool(File(dir, "spool"))
        spool.persist("/ingest/v1/errors", """{"a":1}""")
        val handles = spool.load()
        assertEquals(1, handles.size)
        spool.remove(handles[0])
        assertEquals(0, spool.load().size)
    }

    @Test
    fun `entry count cap evicts oldest`(@TempDir dir: File) {
        val spool = EventSpool(File(dir, "spool"), maxEntries = 3)
        repeat(6) { i -> spool.persist("/ingest/v1/errors", """{"i":$i}""") }
        val handles = spool.load()
        assertTrue(handles.size <= 3, "expected <=3 entries but was ${handles.size}")
    }
}
