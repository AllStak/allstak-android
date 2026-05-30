package sa.allstak.android.timber

import android.util.Log
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Host-JVM checks for the tree's level gating. Shipping logs requires an
 * initialized SDK + Android runtime, so this focuses on the pure gating logic
 * (no device needed). `android.util.Log` constants are plain ints under the
 * unit-test stub.
 */
class AllStakTreeTest {

    @Test
    fun `default min level excludes debug and verbose`() {
        val tree = AllStakTree()
        assertFalse(tree.isLoggable(null, Log.VERBOSE))
        assertFalse(tree.isLoggable(null, Log.DEBUG))
        assertTrue(tree.isLoggable(null, Log.INFO))
        assertTrue(tree.isLoggable(null, Log.WARN))
        assertTrue(tree.isLoggable(null, Log.ERROR))
    }

    @Test
    fun `lowered min level includes debug`() {
        val tree = AllStakTree(minLevel = Log.DEBUG)
        assertTrue(tree.isLoggable(null, Log.DEBUG))
        assertFalse(tree.isLoggable(null, Log.VERBOSE))
    }

    @Test
    fun `logging without an initialized sdk does not throw`() {
        val tree = AllStakTree()
        // Routes to AllStak.captureLog which is a no-op before init.
        tree.log(Log.INFO, "tag", "hello", null)
        tree.log(Log.ERROR, "tag", "boom", RuntimeException("x"))
    }
}
