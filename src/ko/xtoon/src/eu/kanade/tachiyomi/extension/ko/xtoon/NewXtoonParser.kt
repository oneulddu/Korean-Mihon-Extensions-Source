package eu.kanade.tachiyomi.extension.ko.xtoon

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.io.IOException
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal data class XtoonManga(
    var url: String = "",
    var title: String = "",
    var thumbnail_url: String? = null,
    var author: String? = null,
    var description: String? = null,
    var genre: String? = null,
    var status: Int = SManga.UNKNOWN,
)
internal data class XtoonMangasPage(val mangas: List<XtoonManga>, val hasNextPage: Boolean)
internal data class XtoonChapter(var url: String = "", var name: String = "", var date_upload: Long = 0L)

internal object NewXtoonParser {
    val comicPath = Regex("/comics/[0-9]+")
    val chapterPath = Regex("/comics/[0-9]+/chapters/[0-9]+")
    private val datePattern = Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}")

    fun normalizedTitle(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFC)
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun mangaPage(document: Document): XtoonMangasPage {
        val current = document.location().toHttpUrl()
        val mangas = document.select("a.comic-link[href]").mapNotNull { card ->
            val url = card.absUrl("href").toHttpUrlOrNull()
                ?.takeIf { it.host == current.host && comicPath.matches(it.encodedPath) }
                ?: return@mapNotNull null
            val title = card.selectFirst("h3")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            XtoonManga().apply {
                this.title = title
                this.url = url.encodedPath
                thumbnail_url = card.selectFirst("img.cover-image")?.absUrl("src")?.takeIf(String::isNotEmpty)
            }
        }.distinctBy { it.url }
        val nextPage = (current.queryParameter("page")?.toIntOrNull() ?: 1) + 1
        val hasNext = document.select("a[href]").any { link ->
            val url = link.absUrl("href").toHttpUrlOrNull() ?: return@any false
            url.host == current.host && url.encodedPath == current.encodedPath &&
                url.queryParameter("page")?.toIntOrNull() == nextPage &&
                queryWithoutPage(url) == queryWithoutPage(current)
        }
        return XtoonMangasPage(mangas, hasNext)
    }

    private fun queryWithoutPage(url: HttpUrl) = url.queryParameterNames.filter { it != "page" }
        .associateWith { url.queryParameterValues(it).sortedBy { value -> value.orEmpty() } }

    fun details(document: Document): XtoonManga {
        val heading = document.selectFirst("#comic-title") ?: throw IOException("작품 상세를 읽을 수 없습니다.")
        val info = heading.parent()!!
        return XtoonManga().apply {
            title = heading.text().trim().also { if (it.isBlank()) throw IOException("작품 제목이 없습니다.") }
            author = info.select("a[href*=/search?q=]").joinToString { it.text() }
            description = info.selectFirst("[data-comic-description]")?.text()
            genre = info.select("a[href*=genre=]").map { it.text() }.distinct().joinToString()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.absUrl("content")
            status = when {
                info.select("strong").any { it.text() == "완결" } -> SManga.COMPLETED
                info.select("strong").any { it.text() == "연재중" } -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    data class ChapterBatch(val chapters: List<XtoonChapter>, val nextPage: Int?)
    data class ChapterIndex(val apiUrl: HttpUrl, val expectedCount: Int, val first: ChapterBatch)

    fun chapterIndex(document: Document): ChapterIndex {
        val current = document.location().toHttpUrl()
        if (!comicPath.matches(current.encodedPath)) throw IOException("잘못된 작품 주소입니다.")
        val section = document.selectFirst("section#chapters") ?: throw IOException("회차 목록을 찾을 수 없습니다.")
        val api = section.absUrl("data-chapters-url").toHttpUrlOrNull()
            ?.takeIf { it.scheme == current.scheme && it.host == current.host && it.port == current.port && it.encodedPath == current.encodedPath + "/chapters" && it.query == null }
            ?: throw IOException("회차 API 주소가 올바르지 않습니다.")
        val total = Regex("총\\s*([0-9,]+)\\s*화").find(section.text())?.groupValues?.get(1)
            ?.replace(",", "")?.toIntOrNull() ?: throw IOException("전체 회차 수를 확인할 수 없습니다.")
        val chapters = section.select("a.chapter-item[data-chapter-id]").map { item ->
            val id = item.attr("data-chapter-id")
            val url = item.absUrl("href").toHttpUrlOrNull()
            if (url?.host != current.host || url.encodedPath != api.encodedPath + "/" + id) throw IOException("다른 작품의 회차 주소입니다.")
            val title = item.selectFirst("span.truncate")?.text().orEmpty()
            chapter(api, id, title, datePattern.find(item.text())?.value.orEmpty())
        }
        val next = section.attr("data-chapter-next-page").takeIf(String::isNotBlank)?.toIntOrNull()
        return ChapterIndex(api, total, ChapterBatch(chapters, next))
    }

    fun chapterBatch(json: String, api: HttpUrl): ChapterBatch {
        val root = Json.parseToJsonElement(json).jsonObject
        val chapters = root.getValue("chapters").jsonArray.map { value ->
            val item = value.jsonObject
            chapter(api, item.getValue("id").jsonPrimitive.content, item.getValue("title").jsonPrimitive.content, item["date"]?.jsonPrimitive?.content.orEmpty())
        }
        val more = root.getValue("has_more").jsonPrimitive.boolean
        val next = root["next_page"]?.jsonPrimitive?.intOrNull
        if (more && next == null) throw IOException("다음 회차 페이지 주소가 없습니다.")
        return ChapterBatch(chapters, if (more) next else null)
    }

    fun allChapters(index: ChapterIndex, fetch: (Int) -> ChapterBatch): List<XtoonChapter> {
        val chapters = linkedMapOf<String, XtoonChapter>()
        var batch = index.first
        var page = 1
        while (true) {
            val previousSize = chapters.size
            batch.chapters.forEach { chapters.putIfAbsent(it.url, it) }
            if (chapters.size == previousSize && (page > 1 || batch.nextPage != null)) throw IOException("회차 목록이 반복됩니다. 다시 시도해 주세요.")
            val next = batch.nextPage ?: break
            if (next <= page || next > 1000) throw IOException("회차 페이지 순서가 올바르지 않습니다.")
            page = next
            batch = fetch(page)
        }
        // The site can add chapters while pagination is in flight. Never publish a shortened list.
        if (chapters.size < index.expectedCount) throw IOException("회차 목록이 일부만 수신되었습니다 (${chapters.size}/${index.expectedCount}). 다시 시도해 주세요.")
        return chapters.values.toList()
    }

    fun pages(document: Document): List<Page> {
        val elements = document.select("img[data-reader-image]")
        // The site's error handler carries the full image count, even when a trailing frame is absent.
        val countPattern = Regex("""Number\(canvas\.dataset\.failedPages\)\s*===\s*([0-9]+)""")
        val expectedCounts = elements.mapNotNull { countPattern.find(it.attr("onerror"))?.groupValues?.get(1)?.toIntOrNull() }.distinct()
        val images = elements.map { image ->
            val position = image.parent()?.attr("data-image-position")?.toIntOrNull()
                ?: throw IOException("뷰어 이미지 순서를 확인할 수 없습니다.")
            val url = image.absUrl("src").toHttpUrlOrNull() ?: throw IOException("뷰어 이미지 주소가 없습니다.")
            position to url.toString()
        }.sortedBy { it.first }
        if (images.isEmpty() || images.map { it.first } != (1..images.size).toList()) throw IOException("뷰어 이미지가 누락되었거나 중복되었습니다.")
        if (expectedCounts.size > 1 || expectedCounts.singleOrNull()?.let { it != images.size } == true) throw IOException("뷰어 이미지 일부가 누락되었습니다.")
        return images.mapIndexed { index, (_, url) -> Page(index, document.location(), url) }
    }

    private fun chapter(api: HttpUrl, id: String, title: String, date: String): XtoonChapter {
        if (!id.matches(Regex("[0-9]+")) || title.isBlank()) throw IOException("회차 정보가 올바르지 않습니다.")
        return XtoonChapter().apply {
            url = api.encodedPath + "/" + id
            name = title.trim()
            date_upload = runCatching {
                SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Seoul")
                    isLenient = false
                }.parse(date)?.time ?: 0L
            }.getOrDefault(0L)
        }
    }
}
