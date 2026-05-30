package sa.allstak.android.okhttp

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the near-zero-config OkHttp entry points attach exactly one
 * interceptor and never double-instrument.
 */
class AllStakOkHttpInstallTest {

    @Test
    fun `installAllStak attaches the interceptor`() {
        val client = OkHttpClient.Builder().installAllStak().build()
        assertEquals(1, client.interceptors.count { it is AllStakOkHttpInterceptor })
    }

    @Test
    fun `installAllStak is idempotent`() {
        val builder = OkHttpClient.Builder().installAllStak().installAllStak()
        val client = builder.build()
        assertEquals(1, client.interceptors.count { it is AllStakOkHttpInterceptor })
    }

    @Test
    fun `factory client is instrumented`() {
        val client = AllStakOkHttp.client()
        assertTrue(client.interceptors.any { it is AllStakOkHttpInterceptor })
    }

    @Test
    fun `instrument preserves existing interceptors and adds ours once`() {
        val base = OkHttpClient.Builder().installAllStak().build()
        val again = AllStakOkHttp.instrument(base)
        assertEquals(1, again.interceptors.count { it is AllStakOkHttpInterceptor })
    }
}
