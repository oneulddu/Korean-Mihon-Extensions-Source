package eu.kanade.tachiyomi.extension.ko.rawdex

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class RawDEX :
    Madara(
        "RawDEX",
        "https://rawdex.net",
        "ko",
        SimpleDateFormat("dd.MM.yyyy", Locale.ROOT),
    ) {

    // The Nova theme retains Madara's manga/chapter URLs but replaces its HTML.
    // Keep the source identity and absolute chapter URLs used by existing libraries.
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val fetchGenres = false
    override val sendViewCount = false
    override val chapterUrlSuffix = ""

    override fun popularMangaSelector() = RawDEXParser.CARD
    override fun searchMangaSelector() = RawDEXParser.CARD
    override fun popularMangaNextPageSelector() = RawDEXParser.NEXT_PAGE

    override fun popularMangaFromElement(element: Element): SManga = RawDEXParser.manga(element).let { entry ->
        SManga.create().apply {
            setUrlWithoutDomain(entry.url)
            title = entry.title
            thumbnail_url = entry.cover?.let(::imageFromElement)
        }
    }

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/manga/${searchPage(page)}?m_orderby=modified", headers)

    override fun mangaDetailsParse(document: Document): SManga = RawDEXParser.details(document).let { details ->
        SManga.create().apply {
            title = details.title
            author = details.author
            artist = details.artist
            description = details.description
            genre = details.genres
            thumbnail_url = details.cover?.let(::imageFromElement)
            status = when (details.status.lowercase(Locale.ROOT)) {
                "end", "completed" -> SManga.COMPLETED
                "on-going", "ongoing" -> SManga.ONGOING
                "on-hold" -> SManga.ON_HIATUS
                "canceled", "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = RawDEXParser.chapters(response.asJsoup()).map { entry ->
        SChapter.create().apply {
            url = entry.url
            name = entry.name
            date_upload = parseChapterDate(entry.date)
        }
    }

    // Delegate image resolution to Madara, including lazy loading and srcset support.
    override val pageListParseSelector = RawDEXParser.PAGE_IMAGES

    override fun getFilterList() = FilterList(
        AuthorFilter("작가"),
        ArtistFilter("그림 작가"),
        NovaStatusFilter(),
        OrderByFilter(
            "정렬",
            listOf("관련도" to "", "최근 업데이트" to "modified", "이름순" to "alphabet", "평점" to "rating", "인기 급상승" to "trending", "조회수" to "views", "신작" to "new-manga"),
        ),
    )

    override fun searchRequest(page: Int, query: String, filters: FilterList) = GET(
        "$baseUrl/${searchPage(page)}".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .apply {
                filters.forEach { filter ->
                    when (filter) {
                        is AuthorFilter -> if (filter.state.isNotBlank()) addQueryParameter("author", filter.state)
                        is ArtistFilter -> if (filter.state.isNotBlank()) addQueryParameter("artist", filter.state)
                        is NovaStatusFilter -> if (filter.state != 0) addQueryParameter("status", filter.toUriPart())
                        is OrderByFilter -> if (filter.state != 0) addQueryParameter("m_orderby", filter.toUriPart())
                        else -> Unit
                    }
                }
            }.build(),
        headers,
    )

    private class NovaStatusFilter :
        UriPartFilter(
            "연재 상태",
            arrayOf("전체" to "", "연재 중" to "on-going", "완결" to "end", "중단" to "canceled", "휴재" to "on-hold", "연재 예정" to "upcoming"),
        )
}
