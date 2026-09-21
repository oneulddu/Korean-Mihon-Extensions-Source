package eu.kanade.tachiyomi.extension.ko.goodtoon

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

// Retired content origins redirect to the official channel, sometimes as a raw
// redirect and sometimes followed to a 200 page. Let the outer domain interceptor
// invalidate even a fresh cache and retry the original content request once.
internal class GoodToonGuideRedirectInterceptor(private val manualBaseUrl: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (manualBaseUrl() != null || !isGoodToonHost(request.url.host)) return response
        val target = when {
            response.code in 300..399 -> response.header("Location")?.let(response.request.url::resolve)
            response.isSuccessful -> response.request.url
            else -> null
        }
        if (target?.isOfficialGuide() == true) {
            response.close()
            throw IOException("굿툰 접속 주소가 변경되었습니다. 잠시 후 다시 시도해 주세요.")
        }
        return response
    }

    private fun HttpUrl.isOfficialGuide() = scheme == "https" && host == "t.me" && port == 443 &&
        username.isEmpty() && password.isEmpty() && query == null && fragment == null &&
        encodedPath.trimEnd('/') in setOf("/goodtoon_url", "/s/goodtoon_url")
}
