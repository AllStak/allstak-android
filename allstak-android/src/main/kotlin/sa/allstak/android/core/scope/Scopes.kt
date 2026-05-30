package sa.allstak.android.core.scope

import sa.allstak.android.core.model.Breadcrumb
import sa.allstak.android.core.model.UserContext

/**
 * The merged read-only view a capture sees: global scope overlaid with the
 * current scope. Scalars (user/level) take the more-specific value; tags,
 * contexts, extras union, and breadcrumbs concatenate in chronological order.
 */
class MergedScope(
    val user: UserContext?,
    val level: String?,
    val tags: Map<String, String>,
    val contexts: Map<String, Any?>,
    val extras: Map<String, Any?>,
    val breadcrumbs: List<Breadcrumb>,
)

/**
 * Global + per-thread scope registry. On Android there is no per-request
 * isolation, so a global scope plus a thread-local current scope is enough to
 * mirror the JVM SDK's merge semantics for the dashboard.
 */
object Scopes {

    private val global = Scope()

    // ThreadLocal.withInitial requires API 26; an anonymous subclass keeps
    // minSdk 21 working without desugaring.
    private val currentScope = object : ThreadLocal<Scope>() {
        override fun initialValue(): Scope = Scope()
    }

    fun global(): Scope = global

    // initialValue() guarantees a non-null Scope per thread.
    fun current(): Scope = currentScope.get()!!

    /** Reset all scopes — used by tests and re-init. */
    fun clearAll() {
        global.clear()
        current().clear()
    }

    fun mergedForCapture(): MergedScope {
        val g = global
        val c = current()

        val tags = LinkedHashMap<String, String>()
        tags.putAll(g.getTags())
        tags.putAll(c.getTags())

        val contexts = LinkedHashMap<String, Any?>()
        contexts.putAll(g.getContexts())
        contexts.putAll(c.getContexts())

        val extras = LinkedHashMap<String, Any?>()
        extras.putAll(g.getExtras())
        extras.putAll(c.getExtras())

        val crumbs = ArrayList<Breadcrumb>()
        crumbs.addAll(g.getBreadcrumbs())
        crumbs.addAll(c.getBreadcrumbs())

        val mergedUser = c.user ?: g.user
        val mergedLevel = c.level ?: g.level
        return MergedScope(
            user = mergedUser,
            level = mergedLevel,
            tags = tags,
            contexts = contexts,
            extras = extras,
            breadcrumbs = crumbs,
        )
    }
}
