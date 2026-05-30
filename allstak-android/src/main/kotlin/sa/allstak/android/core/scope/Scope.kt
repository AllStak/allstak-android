package sa.allstak.android.core.scope

import sa.allstak.android.core.model.Breadcrumb
import sa.allstak.android.core.model.UserContext
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A single layer of context. Owns a user, tags, contexts, extras, a bounded
 * breadcrumb ring, and scalar overrides (level/transaction/fingerprint).
 * Captured events see the merged Global + Current view. All mutation is
 * read/write-lock guarded so background threads can read while the UI thread
 * updates scope.
 */
class Scope(private val maxBreadcrumbs: Int = DEFAULT_MAX_BREADCRUMBS) {

    private val lock = ReentrantReadWriteLock()

    private var userField: UserContext? = null
    private var levelField: String? = null
    private var transactionField: String? = null
    private var fingerprintField: List<String>? = null

    private val tags = LinkedHashMap<String, String>()
    private val contexts = LinkedHashMap<String, Any?>()
    private val extras = LinkedHashMap<String, Any?>()
    private val breadcrumbs = ArrayList<Breadcrumb>()

    var user: UserContext?
        get() = lock.read { userField }
        set(value) = lock.write { userField = value }

    var level: String?
        get() = lock.read { levelField }
        set(value) = lock.write { levelField = value }

    var transaction: String?
        get() = lock.read { transactionField }
        set(value) = lock.write { transactionField = value }

    var fingerprint: List<String>?
        get() = lock.read { fingerprintField?.let { ArrayList(it) } }
        set(value) = lock.write { fingerprintField = value?.let { ArrayList(it) } }

    fun setTag(key: String?, value: String?) {
        if (key == null) return
        lock.write { if (value == null) tags.remove(key) else tags[key] = value }
    }

    fun removeTag(key: String?) {
        if (key == null) return
        lock.write { tags.remove(key) }
    }

    fun getTags(): Map<String, String> = lock.read { LinkedHashMap(tags) }

    fun setContext(key: String?, value: Any?) {
        if (key == null) return
        lock.write { contexts[key] = value }
    }

    fun getContexts(): Map<String, Any?> = lock.read { LinkedHashMap(contexts) }

    fun setExtra(key: String?, value: Any?) {
        if (key == null) return
        lock.write { extras[key] = value }
    }

    fun getExtras(): Map<String, Any?> = lock.read { LinkedHashMap(extras) }

    fun addBreadcrumb(crumb: Breadcrumb?) {
        if (crumb == null) return
        lock.write {
            breadcrumbs.add(crumb)
            while (breadcrumbs.size > maxBreadcrumbs) breadcrumbs.removeAt(0)
        }
    }

    fun clearBreadcrumbs() = lock.write { breadcrumbs.clear() }

    fun getBreadcrumbs(): List<Breadcrumb> = lock.read { ArrayList(breadcrumbs) }

    fun clear() = lock.write {
        userField = null
        levelField = null
        transactionField = null
        fingerprintField = null
        tags.clear()
        contexts.clear()
        extras.clear()
        breadcrumbs.clear()
    }

    fun copy(): Scope = lock.read {
        val c = Scope(maxBreadcrumbs)
        c.userField = userField
        c.levelField = levelField
        c.transactionField = transactionField
        c.fingerprintField = fingerprintField?.let { ArrayList(it) }
        c.tags.putAll(tags)
        c.contexts.putAll(contexts)
        c.extras.putAll(extras)
        c.breadcrumbs.addAll(breadcrumbs)
        c
    }

    companion object {
        const val DEFAULT_MAX_BREADCRUMBS = 100
    }
}
