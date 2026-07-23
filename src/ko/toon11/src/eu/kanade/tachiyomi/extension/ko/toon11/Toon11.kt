package eu.kanade.tachiyomi.extension.ko.toon11

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.normalizeBaseUrl
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class Toon11 :
    HttpSource(),
    ConfigurableSource {

    override val name = "11toon"

    private val defaultBaseUrl = "https://www.spotv148.com"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val lang = "ko"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/bbs/board.php?bo_table=toon_c&is_over=0", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("li[data-id]").mapNotNull(::popularMangaFromElement)
        val hasNextPage = document.selectFirst(".pg_end") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/bbs/board.php?bo_table=toon_c&sord=&type=upd&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("li[data-id]").mapNotNull(::popularMangaFromElement)
        val hasNextPage = document.selectFirst(".pg_end") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = if (query.isNotBlank()) {
        val url = "$baseUrl/bbs/search_stx.php".toHttpUrl().newBuilder().apply {
            addQueryParameter("stx", query)
        }.build()
        GET(url, headers)
    } else {
        val sortFilter = filters.firstInstanceOrNull<SortFilter>()
        val statusFilter = filters.firstInstanceOrNull<StatusFilter>()
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()

        val urlString = sortFilter?.selected ?: sortList[0].value
        val isOver = statusFilter?.selected ?: ""
        val genre = genreFilter?.selected ?: ""

        val url = (baseUrl + urlString).toHttpUrl().newBuilder().apply {
            addQueryParameter("is_over", isOver)
            if (page > 1) addQueryParameter("page", page.toString())
            if (genre.isNotEmpty()) addQueryParameter("sca", genre)
        }.build()

        GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("li[data-id]").mapNotNull { element ->
            val title = element.selectFirst(".homelist-title")?.text()?.trim().orEmpty()
            val dataId = element.attr("data-id")
            if (title.isBlank() || dataId.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                url = "/bbs/board.php?bo_table=toons&stx=${URLEncoder.encode(title, "UTF-8")}&is=$dataId"
                thumbnail_url = element.thumbnailUrl()
            }
        }
        val hasNextPage = document.selectFirst(".pg_end") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h2.title")?.text()?.trim()
                ?.takeIf(String::isNotBlank)
                ?: throw IOException("작품 제목을 찾을 수 없습니다.")
            thumbnail_url = document.selectFirst("img.banner")?.absUrl("src")
            document.selectFirst("span:contains(분류) + span")?.also { status = parseStatus(it.text()) }
            document.selectFirst("span:contains(작가) + span")?.also { author = it.text() }
            document.selectFirst("span:contains(소개) + span")?.also { description = it.text() }
            document.selectFirst("span:contains(장르) + span")?.also { genre = it.text().split(",").joinToString { s -> s.trim() } }
        }
    }

    private fun parseStatus(element: String): Int = when {
        "완결" in element -> SManga.COMPLETED
        "주간" in element || "월간" in element || "연재" in element || "격주" in element -> SManga.ONGOING
        else -> SManga.UNKNOWN
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select("#comic-episode-list > li").mapNotNull(::parseChapter).toMutableList()
        val nextUrl = document.selectFirst("span.pg .pg_current ~ .pg_page")?.absUrl("href")
        if (!nextUrl.isNullOrBlank()) parseRemainingChapters(nextUrl, chapters)
        return chapters.distinctBy { it.url }
    }

    private fun parseRemainingChapters(initialUrl: String, chapters: MutableList<SChapter>) {
        val visitedUrls = mutableSetOf<String>()
        var nextUrl: String? = initialUrl
        while (!nextUrl.isNullOrBlank() && visitedUrls.add(nextUrl)) {
            val page = client.newCall(GET(nextUrl, headers)).execute().asJsoup()
            chapters += page.select("#comic-episode-list > li").mapNotNull(::parseChapter)
            nextUrl = page.selectFirst(".pg_current ~ .pg_page")?.absUrl("href")
        }
    }

    private fun parseChapter(element: Element): SChapter? {
        val button = element.selectFirst("button") ?: return null
        val chapterUrl = button.attr("onclick").substringAfter("location.href='.", "").substringBefore("'")
        val chapterName = button.selectFirst(".episode-title")?.text()?.trim().orEmpty()
        if (chapterUrl.isBlank() || chapterName.isBlank()) return null
        val dateEl = element.selectFirst(".free-date")

        return SChapter.create().apply {
            url = chapterUrl
            name = chapterName
            dateEl?.also { date_upload = dateFormat.tryParse(it.text()) }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + "/bbs" + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val rawImageLinks = document.selectFirst("script + script[type^=text/javascript]:not([src])")?.data()
            ?: throw IOException("이미지 목록 스크립트를 찾을 수 없습니다.")
        val imgList = extractList(rawImageLinks)

        return imgList.mapIndexed { i, img ->
            Page(i, imageUrl = "https:$img")
        }
    }

    private fun extractList(jsString: String): List<String> {
        val matchResult = imgListRegex.find(jsString)
        val listString = matchResult?.groupValues?.get(1) ?: return emptyList()
        return listString.parseAs<List<String>>()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        migrateLegacyBaseUrl()
        EditTextPreference(screen.context).apply {
            key = PREF_MANUAL_BASE_URL
            title = BASE_URL_PREF_TITLE
            summary = baseUrlPreferenceSummary()
            setDefaultValue("")
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "비워두면 기본 주소 $defaultBaseUrl 을 사용합니다."
            setOnPreferenceChangeListener { preference, newValue ->
                val value = (newValue as? String).orEmpty().trim()
                if (value.isEmpty()) {
                    preferences.edit().remove(PREF_MANUAL_BASE_URL).apply()
                    (preference as EditTextPreference).text = ""
                    preference.summary = baseUrlPreferenceSummary()
                    return@setOnPreferenceChangeListener false
                }

                val normalized = normalizeBaseUrl(value) ?: return@setOnPreferenceChangeListener false
                preferences.edit().putString(PREF_MANUAL_BASE_URL, normalized).apply()
                (preference as EditTextPreference).text = normalized
                preference.summary = "현재 수동 주소: $normalized"
                false
            }
        }.also(screen::addPreference)
    }

    private fun getPrefBaseUrl(): String {
        migrateLegacyBaseUrl()
        return preferences.getString(PREF_MANUAL_BASE_URL, null)
            ?.let(::normalizeBaseUrl)
            ?: defaultBaseUrl
    }

    private fun migrateLegacyBaseUrl() {
        if (preferences.contains(PREF_MIGRATED_BASE_URL)) return
        val legacyManualUrl = preferences.all.asSequence()
            .filter { (key, _) -> key.startsWith(LEGACY_BASE_URL_PREF_PREFIX) }
            .mapNotNull { (_, value) -> (value as? String)?.let(::normalizeBaseUrl) }
            .firstOrNull { it !in LEGACY_DEFAULT_BASE_URLS }
        preferences.edit().apply {
            legacyManualUrl?.let { putString(PREF_MANUAL_BASE_URL, it) }
            putBoolean(PREF_MIGRATED_BASE_URL, true)
        }.apply()
    }

    private fun baseUrlPreferenceSummary(): String = preferences.getString(PREF_MANUAL_BASE_URL, null)
        ?.let(::normalizeBaseUrl)
        ?.let { "현재 수동 주소: $it" }
        ?: "현재 기본 주소: $defaultBaseUrl"

    private fun popularMangaFromElement(element: Element): SManga? {
        val link = element.selectFirst("a") ?: return null
        val title = element.selectFirst(".homelist-title")?.text()?.trim().orEmpty()
        val url = link.absUrl("href")
        if (title.isBlank() || url.isBlank()) return null
        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(url)
            thumbnail_url = element.thumbnailUrl()
        }
    }

    private fun Element.thumbnailUrl(): String? {
        val thumbnail = selectFirst(".homelist-thumb") ?: return null
        return thumbnail.absUrl("data-mobile-image").takeIf(String::isNotBlank)
            ?: thumbnail.attr("style")
                .substringAfter("url('", "")
                .substringBefore("')")
                .takeIf(String::isNotBlank)
                ?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    override fun getFilterList() = FilterList(
        Filter.Header("Note: can't combine search query with filters, status filter only has effect in 인기만화"),
        Filter.Separator(),
        SortFilter(sortList, 0),
        StatusFilter(statusList, 0),
        GenreFilter(genreList, 0),
    )

    companion object {
        private const val PREF_MANUAL_BASE_URL = "manual_base_url"
        private const val PREF_MIGRATED_BASE_URL = "manual_base_url_migrated_v1"
        private const val LEGACY_BASE_URL_PREF_PREFIX = "overrideBaseUrl_v"
        private const val BASE_URL_PREF_TITLE = "Override BaseUrl"

        private val LEGACY_DEFAULT_BASE_URLS = setOf(
            "https://www.11toon.com",
            "https://www.spotv148.com",
        )

        private val dateFormat = SimpleDateFormat("yy.MM.dd", Locale.ENGLISH)
        private val imgListRegex = """img_list\s*=\s*(\[.*?])""".toRegex(RegexOption.DOT_MATCHES_ALL)
    }
}
