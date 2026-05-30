package sa.allstak.android

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import sa.allstak.android.core.AllStakClient
import sa.allstak.android.core.model.RequestContext
import sa.allstak.android.core.model.UserContext
import sa.allstak.android.core.scope.Scopes
import sa.allstak.android.core.transport.HttpTransport
import sa.allstak.android.core.transport.UrlConnectionSender
import java.io.File

/**
 * Env-driven certification probe used by `allstak certify --live-backend`.
 *
 * The test is skipped during normal unit-test runs. When enabled it uses the
 * actual SDK client, transport, sanitizer, W3C span capture, and persistent
 * spool against a live disposable backend.
 */
class LiveCertificationProbeTest {

    @Test
    fun liveCertificationProbe() {
        val probe = System.getenv("ALLSTAK_CERTIFICATION_ANDROID_PROBE") ?: ""
        assumeTrue(probe.isNotBlank(), "live Android certification probe disabled")
        when (probe) {
            "event" -> sendCertificationEvent()
            "offline" -> sendOfflineReplayEvent()
            else -> error("unknown Android certification probe: $probe")
        }
    }

    private fun sendCertificationEvent() {
        Scopes.clearAll()
        val client = makeClient(host = env("ALLSTAK_HOST"))
        val runId = env("ALLSTAK_CERTIFICATION_RUN_ID")
        val fixtureId = env("ALLSTAK_CERTIFICATION_FIXTURE_ID")
        val traceId = env("ALLSTAK_CERTIFICATION_TRACE_ID")
        val parentSpanId = env("ALLSTAK_CERTIFICATION_PARENT_SPAN_ID")
        val rootSpanId = "1111111111111111"
        val sentinels = sentinels()
        val now = System.currentTimeMillis()

        client.setUser(UserContext(id = "cert-user-$runId", email = "sdk-certification@example.invalid"))
        Scopes.global().setTag("allstak.certification", "true")
        Scopes.global().setTag("certification.tag", "certification-tag-ok")
        Scopes.global().setExtra("context.certification", mapOf(
            "runId" to runId,
            "fixture" to fixtureId,
            "marker" to "certification-context-ok",
            "password" to sentinels["password"],
            "nested" to mapOf("apiKey" to sentinels["apiKey"]),
        ))
        client.addBreadcrumb("default", "AllStak certification breadcrumb", "info", mapOf(
            "runId" to runId,
            "fixture" to fixtureId,
            "token" to sentinels["token"],
            "authorization" to sentinels["authorization"],
            "cookie" to sentinels["cookie"],
        ))

        client.captureSpan(
            traceId = traceId,
            spanId = rootSpanId,
            parentSpanId = parentSpanId,
            operation = "http.server",
            description = "POST /api/certification/trace",
            status = "error",
            durationMs = 5,
            startTimeMillis = now,
            endTimeMillis = now + 5,
            service = "certification-android",
            tags = mapOf(
                "http.method" to "POST",
                "http.route" to "/api/certification/trace",
                "certification.fixture" to fixtureId,
                "allstak.certification" to "true",
            ),
            preSampled = true,
        )
        client.captureSpan(
            traceId = traceId,
            spanId = "2222222222222222",
            parentSpanId = rootSpanId,
            operation = "db.sqlite.query",
            description = "SELECT 1",
            status = "ok",
            durationMs = 1,
            startTimeMillis = now + 1,
            endTimeMillis = now + 2,
            service = "certification-android",
            tags = mapOf("db.system" to "sqlite", "db.operation" to "select"),
            preSampled = true,
        )
        client.captureSpan(
            traceId = traceId,
            spanId = "3333333333333333",
            parentSpanId = rootSpanId,
            operation = "http.client",
            description = "GET https://example.invalid/certification",
            status = "ok",
            durationMs = 1,
            startTimeMillis = now + 2,
            endTimeMillis = now + 3,
            service = "certification-android",
            tags = mapOf("http.method" to "GET", "http.url" to "https://example.invalid/certification"),
            preSampled = true,
        )

        client.captureException(
            RuntimeException(env("ALLSTAK_CERTIFICATION_MESSAGE")),
            metadata = certificationMetadata(runId, fixtureId, traceId, rootSpanId, parentSpanId, sentinels),
            requestContext = RequestContext(
                method = "POST",
                path = "/api/certification/trace",
                host = "certification.local",
                statusCode = 500,
                userAgent = "allstak-certification-android",
                traceId = traceId,
            ),
        )

        check(client.flush(10_000)) { "Android certification event did not flush before timeout" }
        client.shutdown()
    }

    private fun sendOfflineReplayEvent() {
        Scopes.clearAll()
        val offlineDir = File(env("ALLSTAK_CERTIFICATION_OFFLINE_DIR"))
        val seed = makeClient(host = "http://127.0.0.1:9", offlineDir = offlineDir)
        val runId = env("ALLSTAK_CERTIFICATION_RUN_ID")
        val fixtureId = env("ALLSTAK_CERTIFICATION_FIXTURE_ID")
        val sentinels = sentinels()
        seed.addBreadcrumb("default", "AllStak certification breadcrumb", "info", mapOf("runId" to runId))
        seed.captureException(
            RuntimeException(env("ALLSTAK_CERTIFICATION_MESSAGE")),
            metadata = certificationMetadata(
                runId,
                fixtureId,
                env("ALLSTAK_CERTIFICATION_TRACE_ID"),
                "4444444444444444",
                env("ALLSTAK_CERTIFICATION_PARENT_SPAN_ID"),
                sentinels,
            ),
            requestContext = RequestContext(traceId = env("ALLSTAK_CERTIFICATION_TRACE_ID")),
        )
        check(seed.flush(15_000)) { "Android offline seed did not finish before timeout" }
        val spooled = offlineDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.size ?: 0
        check(spooled > 0) {
            "Android offline seed did not persist the failed event; diagnostics=${seed.getDiagnostics()}"
        }
        seed.shutdown()

        val replay = makeClient(host = env("ALLSTAK_HOST"), offlineDir = offlineDir)
        replay.drainSpoolNow()
        check(replay.flush(15_000)) { "Android offline replay did not flush before timeout" }
        check(replay.getDiagnostics().eventsReplayed > 0) {
            "Android offline replay flushed but did not record a replay; diagnostics=${replay.getDiagnostics()}"
        }
        replay.shutdown()
    }

    private fun makeClient(host: String, offlineDir: File = File(env("ALLSTAK_CERTIFICATION_OFFLINE_DIR"))): AllStakClient {
        val options = AllStakOptions().apply {
            apiKey = env("ALLSTAK_API_KEY")
            this.host = host
            environment = env("ALLSTAK_ENVIRONMENT")
            release = env("ALLSTAK_RELEASE")
            serviceName = "certification-android"
            enableAutoSessionTracking = false
            enableOfflineQueue = true
            offlineQueueDir = offlineDir.absolutePath
            flushIntervalMs = 60_000
            tracesSampleRate = 1.0
            beforeSend = { event ->
                event
            }
        }
        options.validate()
        return AllStakClient(
            options,
            HttpTransport(options.normalizedHost(), options.apiKey!!, UrlConnectionSender(), sleeper = { }),
            offlineDir,
        )
    }

    private fun certificationMetadata(
        runId: String,
        fixtureId: String,
        traceId: String,
        spanId: String,
        parentSpanId: String,
        sentinels: Map<String, String>,
    ): Map<String, Any?> = linkedMapOf(
        "allstakCertification" to true,
        "runId" to runId,
        "fixture" to fixtureId,
        "framework" to "android",
        "traceId" to traceId,
        "spanId" to spanId,
        "parentSpanId" to parentSpanId,
        "tag.allstak.certification" to "true",
        "certificationTag" to "certification-tag-ok",
        "context.certification" to mapOf(
            "runId" to runId,
            "fixture" to fixtureId,
            "marker" to "certification-context-ok",
            "password" to sentinels["password"],
            "nested" to mapOf("apiKey" to sentinels["apiKey"]),
        ),
        "password" to sentinels["password"],
        "token" to sentinels["token"],
        "apiKey" to sentinels["apiKey"],
        "authorization" to sentinels["authorization"],
        "cookie" to sentinels["cookie"],
        "creditCard" to sentinels["creditCard"],
        "nested" to mapOf("secret" to sentinels["secret"], "jwt" to sentinels["jwt"]),
    )

    private fun sentinels(): Map<String, String> {
        val raw = env("ALLSTAK_CERTIFICATION_SENTINELS")
        val entries = Regex(""""((?:\\.|[^"\\])*)"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .findAll(raw)
            .associate { match ->
                unescapeJsonString(match.groupValues[1]) to unescapeJsonString(match.groupValues[2])
            }
        check(entries.isNotEmpty()) { "ALLSTAK_CERTIFICATION_SENTINELS did not contain a JSON string object" }
        return entries
    }

    private fun unescapeJsonString(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i == value.lastIndex) {
                out.append(c)
                i += 1
                continue
            }
            val escaped = value[i + 1]
            when (escaped) {
                '"', '\\', '/' -> out.append(escaped)
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    check(i + 5 < value.length) { "Invalid JSON unicode escape in sentinels" }
                    out.append(value.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 4
                }
                else -> out.append(escaped)
            }
            i += 2
        }
        return out.toString()
    }

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("missing required env var $name")
}
