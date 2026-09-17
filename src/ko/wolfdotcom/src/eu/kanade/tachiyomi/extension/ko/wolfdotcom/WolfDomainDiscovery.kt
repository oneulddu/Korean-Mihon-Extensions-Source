package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import keiyoushi.utils.normalizeBaseUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val WOLF_GUIDE_URL = "https://a14c.com/"

// The content site's own address-guide navigation links to this public channel.
internal const val WOLF_CHANNEL_URL = "https://t.me/s/wftoon"

internal fun parseWolfOfficialChannel(html: String): String? = Jsoup.parse(html, WOLF_CHANNEL_URL)
    .select(".tgme_widget_message[data-post]")
    .mapNotNull { message ->
        val post = Regex("""^wftoon/(\d+)$""").matchEntire(message.attr("data-post"))
            ?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
        if (message.selectFirst(".tgme_widget_message_forwarded_from") != null) return@mapNotNull null
        val text = message.selectFirst(".tgme_widget_message_text") ?: return@mapNotNull null
        post to text
    }
    .sortedByDescending { it.first }
    .firstNotNullOfOrNull { (_, text) -> parseWolfLatestBaseUrl(text.outerHtml(), WOLF_CHANNEL_URL) }

internal data class WolfDiscoveredDomain(val baseUrl: String, val source: String)

internal class WolfDomainDiscovery(
    client: OkHttpClient,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun discover(): WolfDiscoveredDomain? {
        val deadline = nanoTime() + TimeUnit.SECONDS.toNanos(8)
        val visited = mutableSetOf<HttpUrl>()
        for (guide in listOf(WOLF_GUIDE_URL, WOLF_CHANNEL_URL)) {
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
                            normalizeBaseUrl(target.toString()) { it.host.matches(Regex("""^wfwf\d+\.com$""")) }
                                ?.let { return WolfDiscoveredDomain(it, "공식 안내 리다이렉트") }
                            if (target.toString() in listOf(WOLF_GUIDE_URL, WOLF_CHANNEL_URL)) url = target
                        } else if (response.isSuccessful) {
                            val body = response.body.string()
                            val isChannel = url.toString() == WOLF_CHANNEL_URL
                            val domain = if (isChannel) {
                                parseWolfOfficialChannel(body)
                            } else {
                                parseWolfLatestBaseUrl(body, url.toString())
                            }
                            domain?.let {
                                return WolfDiscoveredDomain(it, if (isChannel) "공식 텔레그램 주소 안내" else "공식 안내 사이트")
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
