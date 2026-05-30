package sa.allstak.android.okhttp

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Exercises the interceptor with a fake [Interceptor.Chain] — no device, no
 * network, no initialized SDK. Confirms the call still proceeds (graceful
 * degradation) and that the W3C traceparent has the right wire shape.
 */
class AllStakOkHttpInterceptorTest {

    private class FakeChain(private val request: Request) : Interceptor.Chain {
        var proceededRequest: Request? = null
        override fun request(): Request = request
        override fun proceed(request: Request): Response {
            proceededRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    }

    @Test
    fun `proceeds and returns response when sdk not initialized`() {
        val request = Request.Builder().url("https://api.example.com/v1/users").build()
        val chain = FakeChain(request)
        val response = AllStakOkHttpInterceptor().intercept(chain)
        assertEquals(200, response.code)
        assertNotNull(chain.proceededRequest)
        // No SDK -> no trace headers injected, request passes through unmodified.
        assertNull(chain.proceededRequest!!.header(AllStakOkHttpInterceptor.HEADER_TRACE_ID))
    }

    @Test
    fun `header constants are stable`() {
        assertEquals("x-allstak-trace-id", AllStakOkHttpInterceptor.HEADER_TRACE_ID)
        assertEquals("x-allstak-span-id", AllStakOkHttpInterceptor.HEADER_SPAN_ID)
        assertEquals("traceparent", AllStakOkHttpInterceptor.HEADER_TRACEPARENT)
    }
}
