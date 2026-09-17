package eu.kanade.tachiyomi.extension.ko.xtoon

import keiyoushi.utils.normalizeBaseUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val XTOON_GUIDE_URL = "https://xn--vg1b002axlc8ts.com/"

// The content site's own address-guide navigation links to this public channel.
internal const val XTOON_CHANNEL_URL = "https://t.me/s/newxtoon"

internal fun parseXtoonOfficialChannel(html: String): String? = Jsoup.parse(html, XTOON_CHANNEL_URL)
    .select(".tgme_widget_message[data-post]")
    .mapNotNull { message ->
        val post = Regex("""^newxtoon/(\d+)$""").matchEntire(message.attr("data-post"))
            ?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
        if (message.selectFirst(".tgme_widget_message_forwarded_from") != null) return@mapNotNull null
        val text = message.selectFirst(".tgme_widget_message_text") ?: return@mapNotNull null
        post to text
    }
    .sortedByDescending { it.first }
    .firstNotNullOfOrNull { (_, text) -> parseXtoonGuide(text.outerHtml(), XTOON_CHANNEL_URL) }

internal data class XtoonDiscoveredDomain(val baseUrl: String, val source: String)

internal class XtoonDomainDiscovery(
    client: OkHttpClient,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun discover(): XtoonDiscoveredDomain? {
        val deadline = nanoTime() + TimeUnit.SECONDS.toNanos(8)
        val visited = mutableSetOf<HttpUrl>()
        for (guide in listOf(XTOON_GUIDE_URL, XTOON_CHANNEL_URL)) {
            var url = guide.toHttpUrl()
            for (attempt in 0 until 4) {
                val remaining = deadline - nanoTime()
                if (remaining <= 0) return null
                if (!visited.add(url)) break
                val call = client.newCall(
                    Request.Builder().url(url)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .header("Cache-Control", "no-cache")
                        .build(),
                )
                call.timeout().timeout(minOf(remaining, TimeUnit.SECONDS.toNanos(3)), TimeUnit.NANOSECONDS)
                try {
                    call.execute().use { response ->
                        if (response.code in 300..399) {
                            val target = response.header("Location")?.let(url::resolve) ?: return@use
                            normalizeBaseUrl(target.toString()) { isXtoonAutomaticHost(it.host) }
                                ?.let { return XtoonDiscoveredDomain(it, "공식 안내 리다이렉트") }
                            if (target.toString() in listOf(XTOON_GUIDE_URL, XTOON_CHANNEL_URL)) url = target
                        } else if (response.isSuccessful) {
                            val body = response.body.string()
                            val isChannel = url.toString() == XTOON_CHANNEL_URL
                            val domain = if (isChannel) {
                                parseXtoonOfficialChannel(body)
                            } else {
                                parseXtoonGuide(body, url.toString())
                            }
                            domain?.let {
                                return XtoonDiscoveredDomain(it, if (isChannel) "공식 텔레그램 주소 안내" else "공식 안내 사이트")
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Preserve the resolver's last good cache if every guide fails.
                }
            }
        }
        return null
    }
}

// The old numeric host remains eligible only for stale-cache/redirect recovery.
internal fun isXtoonAutomaticHost(host: String): Boolean = host.matches(Regex("""^(?:newxtoon\d+\.com|t\d+\.xtoon365\.com)$"""))

internal fun parseXtoonGuide(html: String, location: String): String? = Jsoup.parse(html, location)
    .select("a[href]").asSequence()
    .mapNotNull { location.toHttpUrl().resolve(it.attr("href"))?.let { candidate -> normalizeBaseUrl(candidate.toString()) { url -> url.host.matches(Regex("""^newxtoon\d+\.com$""")) } } }
    .firstOrNull()
