package sa.allstak.android.core.internal

/**
 * Compile-time SDK identity. Mirrors the JVM SDK's `AllStakConfig.SDK_*`
 * constants and is sent on the wire as `sdkName` / `sdkVersion` and in the
 * `User-Agent` (`allstak-android/<version>`).
 */
object AllStakVersion {
    const val SDK_NAME: String = "allstak-android"
    const val SDK_VERSION: String = "0.2.0"

    /** The `platform` field stamped on error/session payloads. */
    const val PLATFORM: String = "android"

    /** Default ingest host. Override via [sa.allstak.android.AllStakOptions]. */
    const val DEFAULT_HOST: String = "https://api.allstak.sa"

    fun userAgent(): String = "$SDK_NAME/$SDK_VERSION"
}
