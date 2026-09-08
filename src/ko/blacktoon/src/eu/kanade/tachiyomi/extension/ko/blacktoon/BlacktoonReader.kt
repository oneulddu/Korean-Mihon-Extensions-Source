package eu.kanade.tachiyomi.extension.ko.blacktoon

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

internal fun blacktoonChapters(html: String, pageUrl: String, json: Json, fetch: (String) -> String): List<Chapter> {
    val mangaId = pageUrl.toHttpUrl().pathSegments.last().removeSuffix(".html")
    if (mangaId.toLongOrNull() == null) throw IOException("invalid webtoon ID")
    val scripts = blacktoonPageScripts(html, pageUrl, fetch).urls.filter { it.encodedPath == "/data/toonlist/$mangaId.js" }
    var failure: Exception? = null
    for (script in scripts) {
        try {
            val payload = chapterPayload.matchEntire(fetch(script.toString())) ?: throw IOException("invalid webtoon chapter script")
            val chapters = json.decodeFromString<List<Chapter>>(payload.groupValues[1])
            if (chapters.isEmpty()) throw IOException("webtoon chapter list is empty")
            return chapters.reversed()
        } catch (error: Exception) {
            error.rethrowIfBlacktoonCancelled()
            failure = error
        }
    }
    throw IOException("unable to load webtoon chapter list", failure)
}

internal fun blacktoonPages(
    html: String,
    pageUrl: String,
    fallbackCdn: String,
    fetchConfig: (String) -> String,
    nowMs: Long = System.currentTimeMillis(),
): List<Page> {
    val scripts = blacktoonPageScripts(html, pageUrl, fetchConfig)
    val imageCdn = blacktoonImageCdn(scripts.variables, fallbackCdn, nowMs)
    val siteUrl = pageUrl.toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    return scripts.document.select("#toon_content_imgs img").mapIndexed { index, element ->
        val image = element.attr("data-original").ifBlank { element.attr("o_src") }.ifBlank { element.attr("src") }
        if (image.isBlank()) throw IOException("webtoon image URL is empty")
        Page(index, url = pageUrl, imageUrl = image.toBlacktoonImageUrl(siteUrl, imageCdn))
    }.also { if (it.isEmpty()) throw IOException("webtoon viewer images are empty") }
}

/** Mirrors content.js getImageDomain without loading or running that script (including its ads). */
internal fun blacktoonImageCdn(variables: Map<String, String>, fallback: String, nowMs: Long): String {
    fun domain(name: String): String? = variables[name]?.toHttpUrlOrNull()
        ?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null }
        ?.toString()?.let { it.trimEnd('/') + "/" }

    val x = variables["toonlistid"]?.toLongOrNull()?.rem(100)
    val uptime = variables["uptime"]?.toLongOrNull()
    val delay = variables["img_per8"]?.toDoubleOrNull()
    if (x != null && uptime != null && delay != null) {
        if (nowMs > uptime.toDouble() - 5 * 60 * 60 * 1000 + delay * 60 * 1000) {
            var threshold = 0.0
            for (index in 3..7) {
                threshold += variables["img_per$index"]?.toDoubleOrNull() ?: break
                if (x < threshold) return domain("img_domain$index") ?: domain("img_domain2") ?: fallback
            }
        }
        return domain("img_domain8") ?: domain("img_domain2") ?: fallback
    }
    // Older layouts can omit the distribution fields; img_domain2 is the site's retry CDN.
    return domain("img_domain2") ?: domain("img_domain") ?: fallback
}

private val chapterPayload = Regex("""\s*(?:(?:var|let|const)\s+)?clist\s*=\s*(\[.*])\s*;?\s*""", RegexOption.DOT_MATCHES_ALL)
