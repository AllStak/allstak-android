package sa.allstak.android

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import sa.allstak.android.core.internal.SqlNormalizer
import sa.allstak.android.core.model.DatabaseQueryItem

/**
 * Automatic SQLite / Room query-timing instrumentation.
 *
 * Wrap your Room open-helper factory once and **every** query the framework
 * runs is timed and shipped — no per-call wrappers. This is the near-zero-config
 * path: a single line at database-construction time auto-instruments the whole
 * DAO surface.
 *
 * Room:
 * ```
 * Room.databaseBuilder(context, AppDb::class.java, "app.db")
 *     .openHelperFactory(AllStakSQLite.wrap(FrameworkSQLiteOpenHelperFactory(), "app.db"))
 *     .build()
 * ```
 *
 * Plain `SupportSQLiteOpenHelper`:
 * ```
 * val helper = AllStakSQLite.wrap(delegateFactory, "app.db").create(config)
 * ```
 *
 * `androidx.sqlite` is `compileOnly`, so this class only links when the host app
 * already depends on Room / `androidx.sqlite`; otherwise it is simply absent and
 * the rest of the SDK is unaffected.
 */
object AllStakSQLite {

    /** Wrap a delegate [SupportSQLiteOpenHelper.Factory] to auto-time queries. */
    @JvmStatic
    @JvmOverloads
    fun wrap(
        delegate: SupportSQLiteOpenHelper.Factory,
        databaseName: String? = null,
    ): SupportSQLiteOpenHelper.Factory = InstrumentedFactory(delegate, databaseName)

    private class InstrumentedFactory(
        private val delegate: SupportSQLiteOpenHelper.Factory,
        private val databaseName: String?,
    ) : SupportSQLiteOpenHelper.Factory {
        override fun create(
            configuration: SupportSQLiteOpenHelper.Configuration,
        ): SupportSQLiteOpenHelper {
            val name = databaseName ?: configuration.name ?: "sqlite"
            return InstrumentedOpenHelper(delegate.create(configuration), name)
        }
    }

    private class InstrumentedOpenHelper(
        private val delegate: SupportSQLiteOpenHelper,
        private val dbLabel: String,
    ) : SupportSQLiteOpenHelper by delegate {
        override val writableDatabase: SupportSQLiteDatabase
            get() = InstrumentedDatabase(delegate.writableDatabase, dbLabel)
        override val readableDatabase: SupportSQLiteDatabase
            get() = InstrumentedDatabase(delegate.readableDatabase, dbLabel)
    }

    /**
     * Delegates every method to the wrapped [SupportSQLiteDatabase] and times
     * the query-executing ones. Failures are recorded with status `error` and
     * re-thrown so the caller's control flow is unchanged.
     */
    private class InstrumentedDatabase(
        private val delegate: SupportSQLiteDatabase,
        private val databaseName: String,
    ) : SupportSQLiteDatabase by delegate {

        override fun query(query: String) =
            timed(query) { delegate.query(query) }

        override fun query(query: String, bindArgs: Array<out Any?>) =
            timed(query) { delegate.query(query, bindArgs) }

        override fun query(query: SupportSQLiteQuery) =
            timed(query.sql) { delegate.query(query) }

        override fun query(query: SupportSQLiteQuery, cancellationSignal: android.os.CancellationSignal?) =
            timed(query.sql) { delegate.query(query, cancellationSignal) }

        override fun execSQL(sql: String) =
            timed(sql) { delegate.execSQL(sql) }

        override fun execSQL(sql: String, bindArgs: Array<out Any?>) =
            timed(sql) { delegate.execSQL(sql, bindArgs) }

        override fun compileStatement(sql: String): SupportSQLiteStatement =
            InstrumentedStatement(delegate.compileStatement(sql), sql, databaseName)

        private inline fun <T> timed(sql: String, block: () -> T): T {
            val start = System.currentTimeMillis()
            var status = "success"
            var error: String? = null
            try {
                return block()
            } catch (t: Throwable) {
                status = "error"
                error = t.message
                throw t
            } finally {
                recordQuery(sql, databaseName, start, status, error)
            }
        }
    }

    /**
     * Compiled-statement wrapper: SQL is known at compile time, so each
     * execution is timed against that statement's SQL.
     */
    private class InstrumentedStatement(
        private val delegate: SupportSQLiteStatement,
        private val sql: String,
        private val databaseName: String,
    ) : SupportSQLiteStatement by delegate {

        override fun execute() = timed { delegate.execute() }
        override fun executeUpdateDelete(): Int = timed { delegate.executeUpdateDelete() }
        override fun executeInsert(): Long = timed { delegate.executeInsert() }
        override fun simpleQueryForLong(): Long = timed { delegate.simpleQueryForLong() }
        override fun simpleQueryForString(): String? = timed { delegate.simpleQueryForString() }

        private inline fun <T> timed(block: () -> T): T {
            val start = System.currentTimeMillis()
            var status = "success"
            var error: String? = null
            try {
                return block()
            } catch (t: Throwable) {
                status = "error"
                error = t.message
                throw t
            } finally {
                recordQuery(sql, databaseName, start, status, error)
            }
        }
    }

    private fun recordQuery(
        sql: String,
        databaseName: String,
        startMillis: Long,
        status: String,
        errorMessage: String?,
    ) {
        try {
            if (!AllStak.isInitialized) return
            val normalized = SqlNormalizer.normalize(sql) ?: sql
            AllStak.captureDbQuery(
                DatabaseQueryItem(
                    normalizedQuery = normalized,
                    queryHash = SqlNormalizer.hash(normalized),
                    queryType = SqlNormalizer.detectQueryType(sql),
                    durationMs = System.currentTimeMillis() - startMillis,
                    timestampMillis = startMillis,
                    status = status,
                    errorMessage = errorMessage,
                    databaseName = databaseName,
                    databaseType = "sqlite",
                ),
            )
        } catch (ignored: Throwable) {
            // Telemetry must never alter the host query's outcome.
        }
    }
}
