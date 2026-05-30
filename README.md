# AllStak Android SDK

Official native Kotlin/Android SDK for [AllStak](https://allstak.sa). One line to
install, one API key to configure — and you automatically get errors, crashes,
ANRs, HTTP, logs, traces, breadcrumbs, and release-health sessions.

- **Near-zero config.** Auto-initializes before your `Application` runs.
- **Everything on by default**, each piece individually toggleable.
- **Privacy-first.** PII (email, IP, request bodies) is off by default; built-in
  scrubbers redact cards, SSNs, emails, and IPs from free text.
- **Offline-resilient.** Un-delivered telemetry is spooled to disk and replayed
  on reconnect.
- `minSdk 21`, `compileSdk 36`, Kotlin 2.x, coroutines-based async transport.

## Modules

| Module | Artifact | Purpose |
| --- | --- | --- |
| Core | `sa.allstak:allstak-android` | SDK engine + automatic instrumentation |
| OkHttp | `sa.allstak:allstak-android-okhttp` | OkHttp interceptor (HTTP records + spans + trace headers) |
| Timber | `sa.allstak:allstak-android-timber` | `Timber.Tree` that ships logs and promotes errors |

## Install

```kotlin
dependencies {
    implementation("sa.allstak:allstak-android:0.2.0")
    // Optional integrations:
    implementation("sa.allstak:allstak-android-okhttp:0.2.0")
    implementation("sa.allstak:allstak-android-timber:0.2.0")
}
```

### Zero-code setup (recommended)

Add your API key to `AndroidManifest.xml` and you are done — the SDK auto-inits
via `androidx.startup` before your `Application.onCreate`:

```xml
<application>
    <meta-data
        android:name="sa.allstak.android.API_KEY"
        android:value="ask_live_xxx" />
    <!-- optional -->
    <meta-data android:name="sa.allstak.android.ENVIRONMENT" android:value="production" />
</application>
```

### Explicit setup

```kotlin
AllStak.init(this) {
    apiKey = "ask_live_xxx"
    environment = "production"
    release = "1.4.0+42"   // auto-detected from versionName+versionCode if omitted
}
```

To turn off auto-init and call `init` yourself, remove the initializer in your
manifest:

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    tools:node="merge">
    <meta-data
        android:name="sa.allstak.android.AllStakInitializer"
        tools:node="remove" />
</provider>
```

## What you get automatically

| Feature | How it works | Default | Option |
| --- | --- | --- | --- |
| Uncaught/crash capture | `Thread.setDefaultUncaughtExceptionHandler`, chains the prior handler, flushes before exit | on | `enableUncaughtExceptionHandler` |
| ANR detection | Watchdog posts to the main `Looper`; >5s blocked → ANR event with the main-thread stack | on | `enableAnrDetection`, `anrThresholdMs` |
| Screen breadcrumbs + spans | `ActivityLifecycleCallbacks` per Activity | on | `enableActivityLifecycleBreadcrumbs` |
| App start span | Cold-start time to first resumed Activity | on | `enableAppLifecycleTracking` |
| Foreground/background sessions | Release-health session per foreground lifetime | on | `enableAutoSessionTracking` |
| Connectivity breadcrumbs | Network available/lost + transport type; reconnect drains the spool | on | `enableConnectivityBreadcrumbs` |
| Offline spool | Disk-persist un-sent events, replay on reconnect | on | `enableOfflineQueue` |
| Logs (logcat) | Reads the app's own logcat stream — no logging facade needed | on | `enableAutoLogCapture`, `logCaptureMinPriority` |
| Logs (Timber) | Auto-plants the Timber tree when the integration + Timber are present | on | `enableTimberAutoInstall` |
| Outbound HTTP + traces | One-line OkHttp builder install (separate module) | one-line | `OkHttpClient.Builder().installAllStak()` |
| DB query timing | One-line Room/SQLite open-helper wrap | one-line | `AllStakSQLite.wrap(...)` |

Errors, crashes, ANRs, breadcrumbs, sessions, spans, connectivity, and **logs**
require **zero developer code** — just install + key. Outbound HTTP and DB query
timing are a single line each (OkHttp interceptors and the SQLite open-helper
must be attached where the client/database is built; the SDK cannot retroactively
wrap objects your app already constructed).

## Logs — automatic

Out of the box the SDK captures your app's own `Log.w` / `Log.e` (and above)
from logcat and ships them as log events — **no code, no logging library
required**. Tune the threshold:

```kotlin
AllStak.init(this) {
    apiKey = "ask_live_xxx"
    logCaptureMinPriority = android.util.Log.INFO   // widen from WARN
    // enableAutoLogCapture = false                 // or turn it off
}
```

If you use Timber, add the `allstak-android-timber` dependency and the tree is
**auto-planted** for you — structured logs and throwable promotion with no
`Timber.plant` call. To plant it yourself instead, set
`enableTimberAutoInstall = false` and call `Timber.plant(AllStakTree())`.

## OkHttp

Build instrumented clients with one line — every call then emits an HTTP-request
record, a client span, and a breadcrumb, and injects trace-propagation headers
(`x-allstak-trace-id`, `x-allstak-span-id`, `traceparent`), gated by the
propagation allowlist so trace context never leaks to third-party hosts.

```kotlin
// Instrument your existing builder:
val client = OkHttpClient.Builder()
    .installAllStak()
    .build()

// Or get a ready-made instrumented client:
val client = AllStakOkHttp.client()

// Or wrap a client you already built:
val instrumented = AllStakOkHttp.instrument(existingClient)
```

`installAllStak()` is idempotent and the raw `AllStakOkHttpInterceptor()` is
still available if you prefer to add it manually.

## Database (Room / SQLite)

Wrap your open-helper factory once and **every** query the framework runs is
timed and shipped automatically — no per-call wrappers:

```kotlin
Room.databaseBuilder(context, AppDb::class.java, "app.db")
    .openHelperFactory(AllStakSQLite.wrap(FrameworkSQLiteOpenHelperFactory(), "app.db"))
    .build()
```

For ad-hoc timing of a single call, `AllStakDatabase.trace { }` is still
available (see the Manual API below).

## Manual API

```kotlin
AllStak.captureException(e, metadata = mapOf("orderId" to "ORD-123"))
AllStak.captureMessage("Checkout started", level = "info")
AllStak.addBreadcrumb("ui", "Tapped checkout")
AllStak.setUser(id = "user-42")
AllStak.setTag("plan", "pro")
AllStak.captureHeartbeat("nightly-sync", "ok", durationMs = 4200)
AllStak.captureSpan(
    traceId = traceId,
    spanId = spanId,
    parentSpanId = parentSpanId,
    operation = "db.sqlite.query",
    description = "SELECT * FROM users WHERE id = ?",
    durationMs = 42,
)
AllStak.flush()
val flushed = AllStak.flush(timeoutMs = 5_000)
AllStak.close()
val closed = AllStak.close(timeoutMs = 5_000)

AllStakDatabase.trace("SELECT * FROM users WHERE id = ?", "main.db") {
    dao.findById(id)
}
```

`captureSpan` is the low-level API for custom completed spans when automatic
OkHttp/SQLite instrumentation is not the right hook. IDs are normalized to W3C
widths: 32 lowercase hex characters for `traceId`, 16 for `spanId` and
`parentSpanId`.

`AllStak.getDiagnostics()` returns privacy-safe counters only: captured/sent/
failed/dropped/persisted/replayed events, queue size, retry/rate-limit counts,
compression counters, breadcrumb count, and session recovery count. It never
includes event payloads, headers, breadcrumbs, user data, or secrets.

## Privacy

`sendDefaultPii` is `false` by default. The SDK strips `user.email`/`user.ip`,
and the scrubbers redact Luhn-valid card numbers, hyphenated SSNs, emails, and
IPv4 literals from free-text values. Flip `sendDefaultPii = true` only after
auditing your data — and pair it with a `beforeSend` hook for domain-specific
scrubbing.

## Build, test & publish

```bash
./gradlew assembleRelease       # builds the three release AARs
./gradlew testDebugUnitTest     # host-JVM unit tests (no device)
./gradlew lint
./gradlew publishToMavenLocal   # publish the three artifacts to ~/.m2
```

Each module publishes a real Maven artifact (AAR + sources jar + complete POM)
via `maven-publish`. A GitHub Packages repository target is wired when
`ALLSTAK_GH_USER` + `ALLSTAK_GH_TOKEN` are set; artifacts are signed when a
`SIGNING_KEY` (in-memory ASCII-armored) is supplied, and published unsigned
otherwise so local builds work without keys.

## License

MIT. See [LICENSE](LICENSE).
