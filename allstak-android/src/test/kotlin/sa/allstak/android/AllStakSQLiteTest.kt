package sa.allstak.android

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Verifies the auto-instrumenting SQLite open-helper factory delegates calls,
 * preserves return values, and re-throws on failure (so timing telemetry never
 * alters the host query's control flow). The SDK is not initialized here, so the
 * recording path is a guarded no-op — exactly the graceful-degradation contract.
 */
class AllStakSQLiteTest {

    private fun instrumentedWritableDb(db: SupportSQLiteDatabase): SupportSQLiteDatabase {
        val helper: SupportSQLiteOpenHelper = mock()
        whenever(helper.writableDatabase).thenReturn(db)
        val factory: SupportSQLiteOpenHelper.Factory = mock()
        whenever(factory.create(any())).thenReturn(helper)
        return AllStakSQLite.wrap(factory, "app.db").create(mock()).writableDatabase
    }

    @Test
    fun `query delegates and returns the delegate cursor`() {
        val cursor = mock<android.database.Cursor>()
        val db: SupportSQLiteDatabase = mock()
        whenever(db.query("SELECT 1")).thenReturn(cursor)

        val instrumented = instrumentedWritableDb(db)
        assertSame(cursor, instrumented.query("SELECT 1"))
    }

    @Test
    fun `execSQL failure is recorded and re-thrown unchanged`() {
        val boom = RuntimeException("disk full")
        val db: SupportSQLiteDatabase = mock()
        whenever(db.execSQL("INSERT INTO t VALUES(1)")).thenThrow(boom)

        val instrumented = instrumentedWritableDb(db)
        val thrown = assertThrows(RuntimeException::class.java) {
            instrumented.execSQL("INSERT INTO t VALUES(1)")
        }
        assertEquals("disk full", thrown.message)
    }

    @Test
    fun `compileStatement returns an instrumented statement that delegates`() {
        val stmt = mock<androidx.sqlite.db.SupportSQLiteStatement>()
        whenever(stmt.executeInsert()).thenReturn(7L)
        val db: SupportSQLiteDatabase = mock()
        whenever(db.compileStatement("INSERT INTO t VALUES(?)")).thenReturn(stmt)

        val instrumented = instrumentedWritableDb(db)
        val wrapped = instrumented.compileStatement("INSERT INTO t VALUES(?)")
        assertEquals(7L, wrapped.executeInsert())
    }
}
