package eu.kanade.tachiyomi.extension.ko.rawdex

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object RawDEXParser {
    const val CARD = ".rdx-library-card"
    const val NEXT_PAGE = "a.next.page-numbers[href]"
    const val PAGE_IMAGES = ".rdx-reader-page img"

    data class Manga(val url: String, val title: String, val cover: Element?)

    data class Details(
        val title: String,
        val author: String?,
        val artist: String?,
        val description: String,
        val genres: String,
        val status: String,
        val cover: Element?,
    )

    data class Chapter(val url: String, val name: String, val date: String)

    fun manga(element: Element): Manga {
        val link = requireNotNull(element.selectFirst("h2 a[href], h3 a[href]")) { "RawDEX: title link not found" }
        return Manga(link.absUrl("href"), link.text(), element.selectFirst("img"))
    }

    fun details(document: Document): Details {
        fun metadata(label: String) = document.select(".rdx-manga-meta > div")
            .firstOrNull { it.selectFirst("dt")?.text().equals(label, ignoreCase = true) }
            ?.selectFirst("dd")?.text()?.takeIf { it.isNotBlank() }

        val summary = document.selectFirst(".rdx-manga-summary")
        val description = summary?.select("p")?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { it.text() } ?: summary?.text().orEmpty()
        val alternative = document.selectFirst(".rdx-manga-alternative")?.text()?.takeIf { it.isNotBlank() }
        return Details(
            title = requireNotNull(document.selectFirst(".rdx-manga-heading h1")) { "RawDEX: manga details not found" }.text(),
            author = metadata("Author"),
            artist = metadata("Artist"),
            description = listOfNotNull(description.takeIf { it.isNotBlank() }, alternative?.let { "Alternative names: $it" }).joinToString("\n\n"),
            genres = (document.select(".rdx-manga-tags a").eachText() + listOfNotNull(metadata("Type"))).distinct().joinToString(),
            status = document.selectFirst(".rdx-manga-status")?.text().orEmpty(),
            cover = document.selectFirst("img.rdx-manga-cover, .rdx-manga-cover img"),
        )
    }

    fun chapters(document: Document): List<Chapter> = document.select(".rdx-chapter-list a.rdx-chapter-row[href]")
        .map { row ->
            Chapter(
                url = row.absUrl("href"),
                name = requireNotNull(row.selectFirst(".rdx-chapter-row__label")) { "RawDEX: chapter title not found" }.text(),
                date = row.selectFirst(".rdx-chapter-row__date")?.text().orEmpty(),
            )
        }.distinctBy { it.url }
}
