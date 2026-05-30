package sa.allstak.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.internal.Json

class JsonTest {

    @Test
    fun `omits null values for NON_NULL parity`() {
        val map = linkedMapOf<String, Any?>(
            "a" to "x",
            "b" to null,
            "c" to 1,
        )
        assertEquals("""{"a":"x","c":1}""", Json.encodeObject(map))
    }

    @Test
    fun `preserves insertion order`() {
        val map = linkedMapOf<String, Any?>(
            "z" to 1, "a" to 2, "m" to 3,
        )
        assertEquals("""{"z":1,"a":2,"m":3}""", Json.encodeObject(map))
    }

    @Test
    fun `escapes control and quote characters`() {
        val map = linkedMapOf<String, Any?>("k" to "a\"b\\c\nd\te")
        assertEquals("""{"k":"a\"b\\c\nd\te"}""", Json.encodeObject(map))
    }

    @Test
    fun `renders scalar types`() {
        val map = linkedMapOf<String, Any?>(
            "i" to 7,
            "l" to 9_000_000_000L,
            "b" to true,
            "d" to 3.5,
            "whole" to 4.0,
        )
        val out = Json.encodeObject(map)
        assertTrue(out.contains("\"i\":7"))
        assertTrue(out.contains("\"l\":9000000000"))
        assertTrue(out.contains("\"b\":true"))
        assertTrue(out.contains("\"d\":3.5"))
        // Whole doubles render without a trailing .0
        assertTrue(out.contains("\"whole\":4"))
    }

    @Test
    fun `nested objects also omit nulls`() {
        val map = linkedMapOf<String, Any?>(
            "outer" to linkedMapOf<String, Any?>("a" to 1, "b" to null),
        )
        assertEquals("""{"outer":{"a":1}}""", Json.encodeObject(map))
    }

    @Test
    fun `arrays keep null elements`() {
        val map = linkedMapOf<String, Any?>("list" to listOf("a", null, "b"))
        assertEquals("""{"list":["a",null,"b"]}""", Json.encodeObject(map))
        assertFalse(Json.encodeObject(map).contains("\"null\""))
    }
}
