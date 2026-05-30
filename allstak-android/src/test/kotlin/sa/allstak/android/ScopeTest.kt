package sa.allstak.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.model.Breadcrumb
import sa.allstak.android.core.model.UserContext
import sa.allstak.android.core.scope.Scope
import sa.allstak.android.core.scope.Scopes

class ScopeTest {

    @AfterEach
    fun tearDown() {
        Scopes.clearAll()
    }

    @Test
    fun `current scope overrides global user`() {
        Scopes.global().user = UserContext.ofId("global")
        Scopes.current().user = UserContext.ofId("local")
        assertEquals("local", Scopes.mergedForCapture().user?.id)
    }

    @Test
    fun `merged tags union with current winning`() {
        Scopes.global().setTag("region", "eu")
        Scopes.global().setTag("tier", "free")
        Scopes.current().setTag("tier", "pro")
        val tags = Scopes.mergedForCapture().tags
        assertEquals("eu", tags["region"])
        assertEquals("pro", tags["tier"])
    }

    @Test
    fun `breadcrumbs concatenate global then current`() {
        Scopes.global().addBreadcrumb(Breadcrumb("log", "g1", "info", null))
        Scopes.current().addBreadcrumb(Breadcrumb("ui", "c1", "info", null))
        val crumbs = Scopes.mergedForCapture().breadcrumbs
        assertEquals(2, crumbs.size)
        assertEquals("g1", crumbs[0].message)
        assertEquals("c1", crumbs[1].message)
    }

    @Test
    fun `breadcrumb ring buffer evicts oldest beyond max`() {
        val scope = Scope(maxBreadcrumbs = 3)
        repeat(5) { i -> scope.addBreadcrumb(Breadcrumb("log", "m$i", "info", null)) }
        val crumbs = scope.getBreadcrumbs()
        assertEquals(3, crumbs.size)
        assertEquals("m2", crumbs[0].message)
        assertEquals("m4", crumbs[2].message)
    }

    @Test
    fun `clear resets scope`() {
        val scope = Scope()
        scope.user = UserContext.ofId("x")
        scope.setTag("a", "b")
        scope.addBreadcrumb(Breadcrumb("log", "m", "info", null))
        scope.clear()
        assertNull(scope.user)
        assertTrue(scope.getTags().isEmpty())
        assertTrue(scope.getBreadcrumbs().isEmpty())
    }

    @Test
    fun `copy is independent`() {
        val scope = Scope()
        scope.setTag("a", "1")
        val c = scope.copy()
        c.setTag("a", "2")
        assertEquals("1", scope.getTags()["a"])
        assertEquals("2", c.getTags()["a"])
    }
}
