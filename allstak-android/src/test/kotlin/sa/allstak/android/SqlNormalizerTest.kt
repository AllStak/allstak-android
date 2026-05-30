package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import sa.allstak.android.core.internal.SqlNormalizer

class SqlNormalizerTest {

    @Test
    fun `replaces string and numeric literals with placeholders`() {
        val sql = "SELECT * FROM users WHERE name = 'alice' AND age = 30"
        assertEquals("SELECT * FROM users WHERE name = ? AND age = ?", SqlNormalizer.normalize(sql))
    }

    @Test
    fun `collapses whitespace`() {
        val sql = "SELECT   *\n FROM\tusers"
        assertEquals("SELECT * FROM users", SqlNormalizer.normalize(sql))
    }

    @Test
    fun `detects query type`() {
        assertEquals("SELECT", SqlNormalizer.detectQueryType("select 1"))
        assertEquals("INSERT", SqlNormalizer.detectQueryType("INSERT INTO t VALUES (1)"))
        assertEquals("OTHER", SqlNormalizer.detectQueryType("CREATE TABLE t (a INT)"))
    }

    @Test
    fun `hash is stable and short`() {
        val normalized = SqlNormalizer.normalize("SELECT * FROM users WHERE id = 5")!!
        val h1 = SqlNormalizer.hash(normalized)
        val h2 = SqlNormalizer.hash(normalized)
        assertEquals(h1, h2)
        assertEquals(16, h1.length)
    }
}
