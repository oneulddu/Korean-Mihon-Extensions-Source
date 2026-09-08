package eu.kanade.tachiyomi.extension.ko.jjaptoon

import keiyoushi.utils.normalizeBaseUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

// These guide hosts were linked by the official portal's redirects/canonical URL.
// A guide is never a content base URL, and arbitrary redirects are not followed.
internal val jjaptoonGuideUrls = listOf(
    "https://xn--kd6b44m.net/",
    "https://xn--kd6b44m.live/",
    "https://xn--kd6b44m.cc/",
)
private val guideHosts = jjaptoonGuideUrls.map { it.toHttpUrl().host }.toSet()
internal val jjaptoonContentHostRegex = Regex("""^(?:www\.)?jjaptoon\d{3}\.com$""")

internal fun parseJjaptoonDomainJson(body: String): String? = runCatching {
    val data = Json.parseToJsonElement(body) as? JsonObject ?: return null
    val domain = data["domain"] as? JsonPrimitive ?: return null
    if (!domain.isString) return null
    normalizeBaseUrl(domain.content) { it.host.matches(jjaptoonContentHostRegex) }
}.getOrNull()

internal class JjaptoonDomainDiscovery(
    client: OkHttpClient,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun discover(): String? {
        val deadline = nanoTime() + TimeUnit.SECONDS.toNanos(8)
        val visited = mutableSetOf<HttpUrl>()
        for (portal in jjaptoonGuideUrls) {
            var url = portal.toHttpUrl()
            var json = false
            var legacyBaseUrl: String? = null
            for (attempt in 0 until 6) {
                val remaining = deadline - nanoTime()
                if (remaining <= 0) return legacyBaseUrl
                if (!visited.add(url)) break
                val request = Request.Builder().url(url)
                    .header("Accept", if (json) "application/json" else "text/html,application/xhtml+xml")
                    .header("Cache-Control", "no-cache")
                    .build()
                val call = client.newCall(request)
                // Leave time for a backup guide if a single host stalls.
                call.timeout().timeout(minOf(remaining, TimeUnit.SECONDS.toNanos(3)), TimeUnit.NANOSECONDS)
                try {
                    call.execute().use { response ->
                        if (response.code in 300..399) {
                            val target = response.header("Location")?.let(url::resolve) ?: return@use
                            normalizeBaseUrl(target.toString()) { it.host.matches(jjaptoonContentHostRegex) }
                                ?.let { return it }
                            if (isGuideUrl(target, json)) url = target
                        } else if (response.isSuccessful) {
                            val body = response.body.string()
                            if (json) {
                                parseJjaptoonDomainJson(body)?.let { return it }
                            } else {
                                legacyBaseUrl = parseJjaptoonLatestBaseUrl(body, url.toString())
                                // js/config.js on the current official guide declares this API.
                                url = url.resolve("data/domain.json")!!
                                json = true
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Try another confirmed guide within the shared lookup budget.
                }
            }
            legacyBaseUrl?.let { return it }
        }
        return null
    }

    private fun isGuideUrl(url: HttpUrl, json: Boolean): Boolean = url.scheme == "https" && url.port == 443 && url.host in guideHosts &&
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
        url.encodedPath == if (json) "/data/domain.json" else "/"
}
