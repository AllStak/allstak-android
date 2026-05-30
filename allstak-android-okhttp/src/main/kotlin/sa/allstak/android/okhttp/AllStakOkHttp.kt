package sa.allstak.android.okhttp

import okhttp3.OkHttpClient

/**
 * Ergonomic, near-zero-config entry points for OkHttp instrumentation.
 *
 * OkHttp interceptors must be attached when a client is built, so the most
 * frictionless automatic path is to build clients through the SDK or add a
 * single call to your existing builder:
 *
 * ```
 * // One-liner factory — fully instrumented client:
 * val client = AllStakOkHttp.client()
 *
 * // Or instrument an existing builder in place:
 * val client = OkHttpClient.Builder()
 *     .installAllStak()
 *     .build()
 * ```
 *
 * Both attach a single [AllStakOkHttpInterceptor] (idempotently — never twice on
 * the same builder), so every call emits an HTTP-request record, a client span,
 * and a breadcrumb, with trace-propagation headers gated by the allowlist.
 */
object AllStakOkHttp {

    /** A ready-to-use, fully instrumented [OkHttpClient]. */
    @JvmStatic
    fun client(): OkHttpClient = builder().build()

    /** A new builder with AllStak instrumentation already installed. */
    @JvmStatic
    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder().installAllStak()

    /** Instrument an existing client, returning a new instrumented client. */
    @JvmStatic
    fun instrument(client: OkHttpClient): OkHttpClient =
        client.newBuilder().installAllStak().build()
}

/**
 * Attach the AllStak interceptor to this builder if not already present.
 * Idempotent — calling it more than once never double-instruments.
 */
fun OkHttpClient.Builder.installAllStak(): OkHttpClient.Builder {
    if (interceptors().none { it is AllStakOkHttpInterceptor }) {
        addInterceptor(AllStakOkHttpInterceptor())
    }
    return this
}
