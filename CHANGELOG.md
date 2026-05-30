# Changelog

All notable changes to the AllStak Android SDK are documented here.

## [Unreleased]

### Added

- **Automatic log capture (no logging facade required).** A new logcat reader,
  scoped to the app's own process, ships `WARN`-and-above log lines as AllStak
  log events with zero developer code. On by default; tune with
  `enableAutoLogCapture` / `logCaptureMinPriority`.
- **Auto-planted Timber tree.** When the `allstak-android-timber` integration and
  Timber are both present, the tree is planted automatically during init
  (`enableTimberAutoInstall`, on by default) — no `Timber.plant` call needed.
- **One-line OkHttp instrumentation.** `OkHttpClient.Builder.installAllStak()`,
  `AllStakOkHttp.client()`, `AllStakOkHttp.builder()`, and
  `AllStakOkHttp.instrument(client)` attach the interceptor idempotently. The
  manual `AllStakOkHttpInterceptor()` remains available.
- **One-line Room/SQLite query timing.** `AllStakSQLite.wrap(factory, name)`
  wraps a `SupportSQLiteOpenHelper.Factory` so every framework query is timed and
  shipped automatically — no per-call wrappers. `AllStakDatabase.trace { }` is
  still available for ad-hoc timing.

### Changed

- Lifecycle and connectivity breadcrumbs now use contract-valid breadcrumb types
  with descriptive categories (`navigation`/`app.lifecycle` and
  `default`/`connectivity`) instead of collapsing to the `default` bucket, so the
  dashboard groups them correctly.

### Packaging

- **Real Maven publishing.** Each module now applies `maven-publish` + `signing`
  and defines a complete publication (AAR + sources jar + POM with license, SCM,
  developer, and issue metadata). `publishToMavenLocal` and a GitHub Packages
  repository target are wired; signing activates only when a key is supplied so
  local builds publish unsigned.

## [0.1.0]

Initial release. Native Kotlin/Android SDK with full automatic instrumentation.

### Added

- **Core engine** (`allstak-android`): capture pipeline (sample → beforeSend →
  mask → transport), buffered log/HTTP/DB sinks, immediate error/span sends,
  layered scopes (user/tags/contexts/extras/breadcrumbs), release-health
  sessions, and an on-disk offline spool with replay on reconnect.
- **Automatic instrumentation, on by default:**
  - Uncaught-exception/crash capture (chains the prior handler, flushes before
    exit).
  - ANR watchdog (main-thread block > threshold → ANR event with the
    main-thread stack).
  - Activity lifecycle screen breadcrumbs + screen spans.
  - Cold-start app-start span.
  - Foreground/background release-health sessions.
  - Connectivity breadcrumbs + reconnect-driven spool drain.
- **Auto-init** via an `androidx.startup` initializer reading the API key from
  manifest `meta-data`; explicit `AllStak.init(context) { … }` DSL also
  supported.
- **OkHttp integration** (`allstak-android-okhttp`): per-call HTTP record +
  client span + breadcrumb, with trace-propagation headers gated by an
  allowlist.
- **Timber integration** (`allstak-android-timber`): a `Timber.Tree` that ships
  logs and promotes throwable-carrying errors to captured exceptions.
- **Optional DB query timing** via `AllStakDatabase.trace { }`.
- **Privacy-first** data scrubbing (cards, SSNs, emails, IPv4) with PII off by
  default.
- Wire format identical to the AllStak ingest v1 contract: camelCase payloads,
  null fields omitted, `X-AllStak-Key` auth header, `User-Agent`
  `allstak-android/<version>`.
- Host-JVM unit tests for transport, models/serialization, scopes, breadcrumbs,
  the data scrubber, retry policy, SQL normalizer, the offline spool, sessions,
  and the OkHttp/Timber integrations.
