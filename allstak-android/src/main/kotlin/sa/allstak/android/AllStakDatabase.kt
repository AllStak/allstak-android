package sa.allstak.android

import sa.allstak.android.core.internal.SqlNormalizer
import sa.allstak.android.core.model.DatabaseQueryItem

/**
 * Guarded SQLite / Room query-timing helper. Wrap a query execution and the
 * SDK records its normalized shape, type, duration, and status as a DB query
 * telemetry record. Opt-in (the SDK cannot auto-hook arbitrary SQLite calls
 * without a content provider hook), but ergonomic:
 *
 * ```
 * val users = AllStakDatabase.trace("SELECT * FROM users WHERE id = ?", "main.db") {
 *     dao.findById(id)
 * }
 * ```
 *
 * Failures are recorded with status "error" and re-thrown so the caller's
 * control flow is unchanged.
 */
object AllStakDatabase {

    @JvmStatic
    @JvmOverloads
    fun <T> trace(
        sql: String,
        databaseName: String? = null,
        databaseType: String = "sqlite",
        block: () -> T,
    ): T {
        val start = System.currentTimeMillis()
        var status = "success"
        var errorMessage: String? = null
        try {
            return block()
        } catch (t: Throwable) {
            status = "error"
            errorMessage = t.message
            throw t
        } finally {
            try {
                val normalized = SqlNormalizer.normalize(sql) ?: sql
                AllStak.captureDbQuery(
                    DatabaseQueryItem(
                        normalizedQuery = normalized,
                        queryHash = SqlNormalizer.hash(normalized),
                        queryType = SqlNormalizer.detectQueryType(sql),
                        durationMs = System.currentTimeMillis() - start,
                        timestampMillis = start,
                        status = status,
                        errorMessage = errorMessage,
                        databaseName = databaseName,
                        databaseType = databaseType,
                    )
                )
            } catch (ignored: Throwable) {
                // Telemetry must never alter the host query's outcome.
            }
        }
    }
}
