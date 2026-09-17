package keiyoushi.utils

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Opt-in recovery for site requests. CDN requests and explicitly selected hosts are untouched. */
class AutomaticDomainInterceptor(
    private val resolver: () -> DynamicBaseUrlResolver,
    private val manualBaseUrl: () -> String?,
    private val isAutomaticHost: (String) -> Boolean,
    private val rewrite: (Request) -> Request,
    private val migrationTarget: (Response) -> String? = { null },
    private val onRedirect: () -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val request = rewrite(original)
        if (manualBaseUrl() != null || !isAutomaticHost(request.url.host)) return chain.proceed(request)
        val from = request.url.origin()
        var failure: IOException? = null
        var response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            if (chain.call().isCanceled() || Thread.currentThread().isInterrupted) throw error
            failure = error
            null
        }
        if (chain.call().isCanceled() || manualBaseUrl() != null) return response ?: throw failure!!

        if (response != null && response.isSuccessful) {
            try {
                val redirected = response.request.url.origin()
                val migration = migrationTarget(response)
                if (chain.call().isCanceled() || manualBaseUrl() != null) return response
                if (resolver().recordRedirect(from, migration ?: redirected)) onRedirect()
                if (migration == null || migration == from) return response
            } catch (error: IOException) {
                response.close()
                response = null
                if (chain.call().isCanceled() || Thread.currentThread().isInterrupted) throw error
                failure = error
            }
        } else if (response != null && response.code !in setOf(502, 503, 504)) {
            return response
        }

        val target = try {
            resolver().resolveAfterFailure(from)
        } catch (error: Exception) {
            response?.close()
            throw error
        }
        if (target == from || chain.call().isCanceled() || manualBaseUrl() != null) {
            return response ?: throw failure!!
        }
        response?.close()
        // Rebuild from the original path/query, never replay an external redirect's request.
        val retry = rewrite(original)
        return chain.proceed(retry).also { result ->
            if (result.isSuccessful && !chain.call().isCanceled() && manualBaseUrl() == null &&
                resolver().recordRedirect(retry.url.origin(), result.request.url.origin())
            ) {
                onRedirect()
            }
        }
    }
}

private fun HttpUrl.origin(): String = newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
