package eu.kanade.tachiyomi.extension.ko.goodtoon

import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal data class GoodToonManga(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val author: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Int = SManga.UNKNOWN,
)
internal data class GoodToonChapter(val url: String, val name: String, val date: Long, val number: Float)
internal data class GoodToonCatalogue(val mangas: List<GoodToonManga>, val hasNext: Boolean)

internal object GoodToonParser {
    private fun isPathSegment(value: String) = value.isNotBlank() && value != "." && value != ".." &&
        value.none { it == '/' || it == '\\' || it.isISOControl() }

    // New works use title slugs, while existing gt-ID URLs remain unchanged.
    private fun isMangaPath(segments: List<String>) = segments.size == 3 &&
        segments[0] == "manga" && isPathSegment(segments[1]) && segments[2].isEmpty()

    private fun HttpUrl.parts() = pathSegments.let { if (it.last().isEmpty()) it.dropLast(1) else it }

    // The site mixes upper/lower percent escapes. Stable stored paths prevent a
    // refresh from creating duplicate manga/chapters and losing read-state links.
    private fun HttpUrl.storedPath() = newBuilder().encodedPath("/").apply {
        pathSegments.forEach { addPathSegment(it) }
    }.build().encodedPath

    private fun contentUrl(raw: String, current: HttpUrl): HttpUrl? = current.resolve(raw)?.takeIf {
        it.scheme == "https" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            it.query == null && it.fragment == null && (it.host == current.host || isGoodToonHost(it.host))
    }

    private fun imageUrl(element: Element, attribute: String, current: HttpUrl): String? = element.attr(attribute)
        .takeIf(String::isNotBlank)?.let(current::resolve)?.takeIf {
            it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty()
        }?.toString()

    fun catalogue(document: Document): GoodToonCatalogue {
        val current = document.location().toHttpUrl()
        val grid = document.selectFirst(".card-grid") ?: throw IOException("굿툰 작품 목록을 읽을 수 없습니다.")
        val cards = grid.select("a.card[href]")
        val mangas = cards.map { card ->
            val url = contentUrl(card.attr("href"), current)?.takeIf { isMangaPath(it.pathSegments) }
                ?: throw IOException("굿툰 작품 주소가 올바르지 않습니다.")
            val title = card.selectFirst(".subject")?.text().orEmpty()
            if (title.isBlank()) throw IOException("굿툰 작품 제목이 없습니다.")
            GoodToonManga(url.storedPath(), title, card.selectFirst(".thumb img:not(.platform-icon)")?.let { imageUrl(it, "src", current) })
        }.distinctBy { it.url }
        val nextPage = (current.queryParameter("pg")?.toIntOrNull() ?: 1) + 1
        val next = mangas.isNotEmpty() && document.select(".pagination a.page-numbers[href]").any { link ->
            if (!link.text().contains("다음")) return@any false
            val url = current.resolve(link.attr("href")) ?: return@any false
            url.scheme == current.scheme && url.host == current.host && url.port == current.port &&
                url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
                url.encodedPath == current.encodedPath && url.queryParameter("pg")?.toIntOrNull() == nextPage &&
                queryWithoutPage(url) == queryWithoutPage(current)
        }
        return GoodToonCatalogue(mangas, next)
    }

    private fun queryWithoutPage(url: HttpUrl) = url.queryParameterNames.filter { it != "pg" }
        .associateWith { url.queryParameterValues(it).sortedBy { value -> value.orEmpty() } }

    fun details(document: Document): GoodToonManga {
        val current = document.location().toHttpUrl()
        val info = document.selectFirst(".manga-summary-info") ?: throw IOException("굿툰 작품 상세를 읽을 수 없습니다.")
        val title = info.selectFirst(".summary-title")?.text().orEmpty()
        if (title.isBlank() || !isMangaPath(current.pathSegments)) throw IOException("굿툰 작품 상세가 올바르지 않습니다.")
        val status = info.select(".summary-meta-row .meta-value").map { it.text() }
        return GoodToonManga(
            current.storedPath(),
            title,
            document.selectFirst(".manga-summary-cover img")?.let { imageUrl(it, "src", current) },
            info.selectFirst(".author-text")?.text(),
            info.selectFirst(".manga-summary-desc")?.text(),
            info.selectFirst(".manga-summary-genres")?.text()?.replace("/", ", "),
            when {
                "완결" in status -> SManga.COMPLETED
                "연재중" in status -> SManga.ONGOING
                else -> SManga.UNKNOWN
            },
        )
    }

    fun chapters(document: Document): List<GoodToonChapter> {
        val current = document.location().toHttpUrl()
        val requestParts = current.parts()
        val parent = requestParts.dropLast(2)
        if (requestParts.takeLast(2) != listOf("ajax", "chapters") || !isMangaPath(parent + "")) {
            throw IOException("굿툰 회차 요청 주소가 올바르지 않습니다.")
        }
        val rows = document.select("li.wp-manga-chapter")
        if (rows.isEmpty()) throw IOException("굿툰 회차 목록을 읽을 수 없습니다. 다시 시도해 주세요.")
        val dateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }
        return rows.map { row ->
            val anchor = row.selectFirst("a[href]") ?: throw IOException("굿툰 회차 링크가 없습니다.")
            val url = contentUrl(anchor.attr("href"), current)?.takeIf {
                val parts = it.parts()
                parts.size > parent.size && parts.take(parent.size) == parent &&
                    parts[parent.size] != "ajax" && parts.all(::isPathSegment)
            } ?: throw IOException("다른 작품이거나 올바르지 않은 굿툰 회차 주소입니다.")
            val title = anchor.clone().apply { select(".up-badge-inline").remove() }.text()
            if (title.isBlank()) throw IOException("굿툰 회차 제목이 없습니다.")
            val date = row.selectFirst(".chapter-release-date")?.text().orEmpty()
            val fullDate = if (Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}").matches(date)) "20$date" else date
            // Some titles carry an unrelated leading number (e.g. 0427 ... 426화).
            // Use the explicit episode suffix rather than Mihon's first-number guess.
            val number = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*화(?:\\s|$)").findAll(title).lastOrNull()
                ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            GoodToonChapter(url.storedPath(), title, runCatching { dateFormat.parse(fullDate)?.time }.getOrNull() ?: 0L, number)
        }.also { chapters ->
            if (chapters.map { it.url }.distinct().size != chapters.size) throw IOException("굿툰 회차 목록에 중복 주소가 있습니다.")
        }
    }

    fun images(document: Document): List<String> {
        val current = document.location().toHttpUrl()
        val images = document.select(".reading-content img.wp-manga-chapter-img")
        if (images.isEmpty()) throw IOException("굿툰 뷰어 이미지를 읽을 수 없습니다.")
        return images.map {
            imageUrl(it, "data-src", current) ?: imageUrl(it, "src", current)
                ?: throw IOException("굿툰 뷰어 이미지 주소가 없습니다.")
        }
    }
}
