package eu.kanade.tachiyomi.extension.ko.jjaptoon

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class Jjaptoon :
    HttpSource(),
    ConfigurableSource {

    override val name = "짭툰"

    private val defaultBaseUrl = "https://www.jjaptoon003.com"

    private val baseUrlPref = "overrideBaseUrl_v${AppInfo.getVersionName()}"

    override val baseUrl by lazy { getPrefBaseUrl().trimEnd('/') }

    override val lang = "ko"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")

    override fun popularMangaRequest(page: Int): Request = GET(
        homeUrl(page, mapOf("selectedSort" to SORT_POPULAR)),
        headers,
    )

    override fun popularMangaParse(response: Response): MangasPage = mangaPageParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(homeUrl(page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = mangaPageParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (filters.selectedSort == SORT_POPULAR) {
            if (query.isNotBlank()) {
                throw UnsupportedOperationException("인기순은 검색어와 함께 사용할 수 없습니다.")
            }
            if (filters.selectedStatus != FILTER_ALL) {
                throw UnsupportedOperationException("인기순은 상태 필터와 함께 사용할 수 없습니다.")
            }

            return client.newCall(
                GET(homeUrl(page, filters.selectedParameters() + ("selectedSort" to SORT_POPULAR)), headers),
            )
                .asObservableSuccess()
                .map(::searchMangaParse)
        }

        return client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map(::searchMangaParse)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET(
        comicsUrl(page, query, filters.selectedParameters()),
        headers,
    )

    override fun searchMangaParse(response: Response): MangasPage = mangaPageParse(response)

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        ScheduleFilter(),
        CategoryFilter(),
        PublisherFilter(),
    )

    private fun comicsUrl(
        page: Int = 1,
        query: String = "",
        parameters: Map<String, String> = emptyMap(),
    ): String = "$baseUrl/comics".toHttpUrl().newBuilder().apply {
        if (query.isNotBlank()) {
            addQueryParameter("search", query.trim())
        }
        parameters.forEach { (key, value) ->
            addQueryParameter(key, value)
        }
        if (page > 1) {
            addQueryParameter("page", page.toString())
        }
    }.build().toString()

    private fun homeUrl(
        page: Int = 1,
        parameters: Map<String, String> = emptyMap(),
    ): String = baseUrl.toHttpUrl().newBuilder().apply {
        parameters.forEach { (key, value) ->
            addQueryParameter(key, value)
        }
        if (page > 1) {
            addQueryParameter(HOME_PAGINATOR, page.toString())
        }
    }.build().toString()

    private fun FilterList.selectedParameters(): Map<String, String> = filterIsInstance<QuerySelectFilter>()
        .mapNotNull { filter ->
            filter.selectedValue
                .takeUnless { it == FILTER_ALL }
                ?.let { filter.queryParameter to it }
        }
        .toMap()

    private val FilterList.selectedSort: String
        get() = filterIsInstance<SortFilter>().firstOrNull()?.selectedValue ?: SORT_LATEST

    private val FilterList.selectedStatus: String
        get() = filterIsInstance<StatusFilter>().firstOrNull()?.selectedValue ?: FILTER_ALL

    private fun mangaPageParse(response: Response): MangasPage = mangaPageParse(response.asJsoup())

    private fun mangaPageParse(document: Document): MangasPage {
        val mangas = document.select("div.grid a[href^=/comics/]:has(img)")
            .distinctBy { it.attr("href") }
            .mapNotNull(::mangaFromElement)

        val hasNextPage = document.select("a[aria-label='Next page']").isNotEmpty() ||
            document.select("button").any {
                it.attr("wire:click").startsWith("nextPage") && !it.hasAttr("disabled")
            }

        return MangasPage(mangas, hasNextPage)
    }

    private abstract class QuerySelectFilter(
        name: String,
        val queryParameter: String,
        private val options: Array<Pair<String, String>>,
    ) : Filter.Select<String>(
        name,
        options.map { it.first }.toTypedArray(),
    ) {
        val selectedValue: String
            get() = options[state].second
    }

    private class SortFilter :
        Filter.Select<String>(
            "정렬",
            arrayOf("최신순", "인기순"),
        ) {
        val selectedValue: String
            get() = when (state) {
                1 -> SORT_POPULAR
                else -> SORT_LATEST
            }
    }

    private class TypeFilter :
        QuerySelectFilter(
            "분류",
            "selectedType",
            arrayOf(
                "전체" to FILTER_ALL,
                "일반" to "general",
                "성인" to "adult",
                "BL" to "bl",
            ),
        )

    private class StatusFilter :
        QuerySelectFilter(
            "상태",
            "selectedStatus",
            arrayOf(
                "전체" to FILTER_ALL,
                "연재" to "ongoing",
                "완결" to "completed",
                "휴재" to "paused",
            ),
        )

    private class ScheduleFilter :
        QuerySelectFilter(
            "요일",
            "selectedSchedule",
            arrayOf(
                "전체" to FILTER_ALL,
                "월" to "monday",
                "화" to "tuesday",
                "수" to "wednesday",
                "목" to "thursday",
                "금" to "friday",
                "토" to "saturday",
                "일" to "sunday",
            ),
        )

    private class CategoryFilter :
        QuerySelectFilter(
            "장르",
            "selectedCategory",
            arrayOf(
                "전체" to FILTER_ALL,
                "액션" to "1",
                "일상" to "3",
                "BL/백합" to "10",
                "로맨스" to "4",
                "SF/판타지" to "2",
                "개그" to "5",
                "학원" to "6",
                "SF" to "7",
                "스토리" to "8",
                "판타지" to "9",
                "개그/코미디" to "11",
                "연애/순정" to "12",
                "드라마" to "13",
                "시대극" to "14",
                "스포츠" to "15",
                "추리/미스터리" to "16",
                "공포/스릴러" to "17",
                "성인" to "18",
                "옴니버스" to "19",
                "에피소드" to "20",
                "무협" to "21",
                "소년" to "22",
                "기타" to "23",
            ),
        )

    private class PublisherFilter :
        QuerySelectFilter(
            "플랫폼",
            "selectedPublisher",
            arrayOf(
                "전체" to FILTER_ALL,
                "Naver Webtoon" to "naver",
                "Daum" to "daum",
                "Kakao" to "kakao",
                "Lezhin" to "lezhin",
                "Toomics" to "toomics",
                "Manta" to "manta",
                "Toptoon" to "toptoon",
                "Comica" to "comica",
                "OneStory" to "onestory",
                "Battle Comic" to "battle-comic",
                "MrBlue" to "mrblue",
                "Tappytoon" to "tappytoon",
                "Ktoon" to "ktoon",
                "Ridi" to "ridi",
                "Anytoon" to "anytoon",
                "Delitoon" to "delitoon",
                "Foxtoon" to "foxtoon",
                "Peanutoon" to "peanutoon",
                "Bomtoon" to "bomtoon",
                "Comico" to "comico",
                "Mutoon" to "mutoon",
                "Other" to "other",
            ),
        )

    private fun mangaFromElement(element: Element): SManga? {
        val title = element.selectFirst("h2")?.text()?.trim()
            ?: element.selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: return null

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(element.absUrl("href"))
            thumbnail_url = element.selectFirst("img")?.imageUrl()
            author = element.selectFirst("p")?.text()?.removePrefix("작가")?.trim()
            status = parseStatus(element.text())
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val badges = document.select("section div.flex.flex-wrap.justify-center.gap-2 span").eachText()

        return SManga.create().apply {
            this.title = title
            thumbnail_url = document.select("section img[alt]").firstOrNull { it.attr("alt") == title }?.imageUrl()
                ?: document.selectFirst("section img[alt]")?.imageUrl()
            description = document.selectFirst("section:has(h2:contains(작품 소개)) p")?.text()?.trim()
            author = document.selectFirst("p:contains(작가:) span")?.text()?.trim()
            status = parseStatus(badges.joinToString(" "))
            genre = badges
                .filterNot { it in ignoredBadges || it.length == 1 }
                .joinToString()
                .takeIf { it.isNotBlank() }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("a[href^=/chapters/]")
            .filter { it.attr("wire:key").startsWith("comic-") }
            .map { element ->
                SChapter.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    name = element.selectFirst("p")?.text()?.trim().orEmpty()
                    date_upload = dateFormat.tryParse(element.selectFirst("p + p")?.text().orEmpty())
                }
            }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(
        baseUrl + chapter.url,
        headersBuilder().set("Referer", baseUrl + chapter.url).build(),
    )

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        return document.select("main img")
            .mapNotNull { it.imageUrl() ?: imageSrcRegex.find(it.attr(":src"))?.groupValues?.get(1) }
            .distinct()
            .mapIndexed { index, imageUrl ->
                Page(index, response.request.url.toString(), imageUrl.replace(" ", "%20"))
            }
    }

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl!!,
        headersBuilder().set("Referer", page.url).build(),
    )

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        getPrefBaseUrl()

        EditTextPreference(screen.context).apply {
            key = baseUrlPref
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"
        }.also(screen::addPreference)
    }

    private fun getPrefBaseUrl(): String {
        val savedBaseUrl = preferences.getString(baseUrlPref, null)?.trimEnd('/')
        if (savedBaseUrl.isNullOrBlank()) {
            return defaultBaseUrl
        }

        if (savedBaseUrl in OLD_DEFAULT_BASE_URLS) {
            preferences.edit()
                .putString(baseUrlPref, defaultBaseUrl)
                .apply()

            return defaultBaseUrl
        }

        return savedBaseUrl
    }

    private fun parseStatus(text: String): Int = when {
        "완결" in text -> SManga.COMPLETED
        "연재" in text -> SManga.ONGOING
        else -> SManga.UNKNOWN
    }

    private fun Element.imageUrl(): String? = when {
        hasAttr("data-original") -> absUrl("data-original")
        hasAttr("data-src") -> absUrl("data-src")
        else -> absUrl("src")
    }.takeIf { it.isNotBlank() }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Override BaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Override default domain with a different one"
        private const val FILTER_ALL = "all"
        private const val SORT_LATEST = "latest"
        private const val SORT_POPULAR = "popular"
        private const val HOME_PAGINATOR = "comicsPage"

        private val OLD_DEFAULT_BASE_URLS = setOf(
            "https://jjaptoon003.com",
            "https://jjabtoon003.com",
            "https://www.jjabtoon003.com",
        )

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }

        private val imageSrcRegex = """loaded\s*\?\s*'([^']+)'""".toRegex()

        private val ignoredBadges = setOf("완결", "연재", "월", "화", "수", "목", "금", "토", "일")
    }
}
