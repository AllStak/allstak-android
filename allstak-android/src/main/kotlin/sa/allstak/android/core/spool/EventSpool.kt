package sa.allstak.android.core.spool

import sa.allstak.android.core.internal.SdkLogger
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Filesystem spool for un-delivered telemetry so events survive an outage or
 * process death and replay on the next reconnect / init. Persists exactly the
 * already-scrubbed JSON that would have gone on the wire (one file per
 * envelope). Fail-open: a non-writable dir leaves the spool [isAvailable] =
 * false and all ops become silent no-ops. Session lifecycle calls are never
 * spooled.
 *
 * Each entry file is `<seq>-<sanitizedPath>.json` and its first line is the
 * ingest path, the remainder the JSON body — so replay needs no parsing of the
 * body itself.
 */
class EventSpool(
    private val dir: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    val isAvailable: Boolean
    private val seq = AtomicLong(System.currentTimeMillis())

    init {
        isAvailable = try {
            dir.mkdirs()
            dir.isDirectory && dir.canWrite()
        } catch (t: Throwable) {
            SdkLogger.debug("Spool dir not writable: ${t.message}")
            false
        }
    }

    /** A persisted envelope on disk. */
    class Handle(val file: File, val path: String, val body: String)

    fun persist(path: String, wireJson: String) {
        if (!isAvailable) return
        try {
            pruneIfNeeded()
            val name = "${seq.incrementAndGet()}-${sanitize(path)}.json"
            val f = File(dir, name)
            f.writeText(path + "\n" + wireJson, Charsets.UTF_8)
        } catch (t: Throwable) {
            SdkLogger.debug("Spool persist skipped for $path: ${t.message}")
        }
    }

    fun load(): List<Handle> {
        if (!isAvailable) return emptyList()
        return try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedBy { it.name } ?: return emptyList()
            val now = System.currentTimeMillis()
            val out = ArrayList<Handle>()
            for (f in files) {
                if (now - f.lastModified() > maxAgeMs) {
                    f.delete()
                    continue
                }
                val text = f.readText(Charsets.UTF_8)
                val nl = text.indexOf('\n')
                if (nl <= 0) {
                    f.delete()
                    continue
                }
                out.add(Handle(f, text.substring(0, nl), text.substring(nl + 1)))
            }
            out
        } catch (t: Throwable) {
            SdkLogger.debug("Spool load failed: ${t.message}")
            emptyList()
        }
    }

    fun remove(handle: Handle) {
        try {
            handle.file.delete()
        } catch (ignored: Throwable) {
        }
    }

    private fun pruneIfNeeded() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name } ?: return
        var count = files.size
        var bytes = files.sumOf { it.length() }
        val now = System.currentTimeMillis()
        var i = 0
        while (i < files.size && (count >= maxEntries || bytes > maxBytes ||
                now - files[i].lastModified() > maxAgeMs)) {
            val f = files[i]
            bytes -= f.length()
            count--
            f.delete()
            i++
        }
    }

    private fun sanitize(path: String): String =
        path.replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-').take(40)

    companion object {
        const val DEFAULT_MAX_ENTRIES = 200
        const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024
        const val DEFAULT_MAX_AGE_MS = 48L * 60 * 60 * 1000
    }
}
