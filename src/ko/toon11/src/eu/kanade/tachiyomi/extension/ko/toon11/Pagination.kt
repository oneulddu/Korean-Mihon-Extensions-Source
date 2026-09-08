package eu.kanade.tachiyomi.extension.ko.toon11

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

internal fun toon11PopularUrl(baseUrl: String, page: Int) = "$baseUrl/bbs/board.php".toHttpUrl().newBuilder()
    .addQueryParameter("bo_table", "toon_c")
    .addQueryParameter("is_over", "0")
    .addQueryParameter("page", page.toString())
    .build()

internal fun toon11SearchUrl(baseUrl: String, query: String, page: Int) = "$baseUrl/bbs/search_stx.php".toHttpUrl().newBuilder()
    .addQueryParameter("stx", query)
    .addQueryParameter("page", page.toString())
    .build()

internal fun hasToon11NextPage(document: Document, requestUrl: HttpUrl): Boolean {
    val currentPage = document.selectFirst(".pg_current")?.ownText()?.trim()?.toIntOrNull()
        ?: requestUrl.queryParameter("page")?.toIntOrNull()
        ?: 1

    return document.select("a.pg_page[href], a.pg_next[href], a.pg_end[href]").any { link ->
        if (link.hasClass("disabled") || link.attr("aria-disabled") == "true") return@any false
        val nextUrl = requestUrl.resolve(link.attr("href")) ?: return@any false
        val nextPage = nextUrl.queryParameter("page")?.toIntOrNull() ?: return@any false
        nextPage > currentPage
    }
}
