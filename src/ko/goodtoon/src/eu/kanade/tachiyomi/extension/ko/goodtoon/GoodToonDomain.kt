package eu.kanade.tachiyomi.extension.ko.goodtoon

import keiyoushi.utils.normalizeBaseUrl
import keiyoushi.utils.rewriteBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup

internal const val GOODTOON_CHANNEL = "https://t.me/s/goodtoon_url"
internal fun isGoodToonHost(host: String) = Regex("""^(?:www\.)?goodtoon\d+\.com$""").matches(host)

// Called only after the interceptor checks the request's site/manual host. A cached
// header may still refer to a previous manual origin that is no longer in that set.
internal fun Request.rewriteGoodToonOrigin(target: String): Request {
    val rewritten = rewriteBaseUrl(target) { true }
    return rewritten.newBuilder().header("Origin", target)
        .header("Referer", rewritten.header("Referer") ?: "$target/").build()
}

// The content site's latest-address navigation identifies this official channel.
internal fun parseGoodToonChannel(html: String): String? = Jsoup.parse(html, GOODTOON_CHANNEL)
    .select(".tgme_widget_message[data-post]").mapNotNull { message ->
        val number = Regex("goodtoon_url/([0-9]+)").matchEntire(message.attr("data-post"))
            ?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
        if (message.selectFirst(".tgme_widget_message_forwarded_from") != null) return@mapNotNull null
        number to message.select(".tgme_widget_message_text a[href]")
    }.sortedByDescending { it.first }.firstNotNullOfOrNull { (_, links) ->
        links.firstNotNullOfOrNull { link ->
            val raw = GOODTOON_CHANNEL.toHttpUrl().resolve(link.attr("href")) ?: return@firstNotNullOfOrNull null
            // Official posts currently use HTTP links. Only a structurally valid content
            // origin may be upgraded; never fetch HTTP or silently discard a custom port.
            if (raw.port != (if (raw.scheme == "https") 443 else 80)) return@firstNotNullOfOrNull null
            normalizeBaseUrl(raw.newBuilder().scheme("https").port(443).build().toString()) { isGoodToonHost(it.host) }
        }
    }
