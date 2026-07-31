package eu.kanade.tachiyomi.extension.ko.jjaptoon

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
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
import keiyoushi.utils.BaseUrlCacheKeys
import keiyoushi.utils.DynamicBaseUrlResolver
import keiyoushi.utils.SharedPreferencesBaseUrlStorage
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.normalizeBaseUrl
import keiyoushi.utils.rewriteBaseUrl
import keiyoushi.utils.shouldInvalidateNumberedDomainCache
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class Jjaptoon :
    HttpSource(),
    ConfigurableSource {

    override val name = "짭툰"

    private val fallbackBaseUrl = "https://www.jjaptoon004.com"

    override val baseUrl: String
        get() = getActiveBaseUrl()

    override val lang = "ko"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val noRedirectClient by lazy {
        network.client.newBuilder()
            .followRedirects(false)
            .connectTimeout(DOMAIN_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DOMAIN_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val latestBaseUrlResolver by lazy {
        migrateAutomaticBaseUrlCache()
        DynamicBaseUrlResolver(
            storage = SharedPreferencesBaseUrlStorage(preferences),
            keys = BaseUrlCacheKeys(
                cachedUrl = LATEST_DOMAIN_URL_PREF,
                fetchedAt = LATEST_DOMAIN_FETCHED_AT_PREF,
                attemptedAt = LATEST_DOMAIN_ATTEMPTED_AT_PREF,
            ),
            fallbackBaseUrl = { fallbackBaseUrl },
            isAllowedAutomaticUrl = { it.host.matches(JJAPTOON_HOST_REGEX) },
            discoverBaseUrl = ::fetchLatestBaseUrl,
            redirectBaseUrl = ::resolveRedirectBaseUrl,
        )
    }

    private val latestDomainInterceptor = Interceptor { chain ->
        chain.proceed(rewriteRequestToLatestDomain(chain.request()))
    }

    override val client by lazy {
        network.cloudflareClient.newBuilder()
            .apply {
                interceptors().add(0, latestDomainInterceptor)
            }
            .build()
    }

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
                    date_upload = parseJjaptoonChapterDate(element.select("p").eachText())
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
        migrateLegacyBaseUrl()

        EditTextPreference(screen.context).apply {
            key = PREF_MANUAL_BASE_URL
            title = BASE_URL_PREF_TITLE
            summary = baseUrlPreferenceSummary()
            setDefaultValue("")
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "비워두면 $LATEST_DOMAIN_PORTAL 에서 최신 주소를 자동 확인합니다."
            setOnPreferenceChangeListener { preference, newValue ->
                val value = (newValue as? String).orEmpty().trim()
                if (value.isEmpty()) {
                    preferences.edit().remove(PREF_MANUAL_BASE_URL).apply()
                    (preference as EditTextPreference).text = ""
                    preference.summary = baseUrlPreferenceSummary()
                    return@setOnPreferenceChangeListener false
                }

                val normalized = normalizeManualBaseUrl(value)
                    ?: return@setOnPreferenceChangeListener false
                preferences.edit().putString(PREF_MANUAL_BASE_URL, normalized).apply()
                (preference as EditTextPreference).text = normalized
                preference.summary = "현재 수동 주소: $normalized"
                false
            }
        }.also(screen::addPreference)
    }

    private fun getActiveBaseUrl(): String = getManualBaseUrl()
        ?: getCachedLatestBaseUrl()
        ?: fallbackBaseUrl

    private fun getManualBaseUrl(): String? {
        migrateLegacyBaseUrl()
        return preferences.getString(PREF_MANUAL_BASE_URL, null)
            ?.let(::normalizeManualBaseUrl)
    }

    private fun migrateLegacyBaseUrl() {
        if (preferences.getBoolean(PREF_MANUAL_BASE_URL_MIGRATED, false)) return

        val existingManualBaseUrl = preferences.getString(PREF_MANUAL_BASE_URL, null)
            ?.let(::normalizeManualBaseUrl)
        val legacyManualBaseUrl = preferences.all.asSequence()
            .filter { (key, _) ->
                key.startsWith(LEGACY_BASE_URL_PREF_PREFIX) && !key.endsWith(LEGACY_MANUAL_PREF_SUFFIX)
            }
            .sortedByDescending { (key, _) -> legacyVersionSortKey(key) }
            .mapNotNull { (key, value) ->
                val normalized = (value as? String)?.let(::normalizeManualBaseUrl) ?: return@mapNotNull null
                val manualFlagKey = key + LEGACY_MANUAL_PREF_SUFFIX
                val hasManualFlag = preferences.contains(manualFlagKey)
                val isExplicitManual = preferences.getBoolean(manualFlagKey, false) ||
                    (!hasManualFlag && normalized !in LEGACY_DEFAULT_BASE_URLS)
                normalized.takeIf { isExplicitManual }
            }
            .firstOrNull()

        preferences.edit().apply {
            (existingManualBaseUrl ?: legacyManualBaseUrl)?.let { putString(PREF_MANUAL_BASE_URL, it) }
            putBoolean(PREF_MANUAL_BASE_URL_MIGRATED, true)
        }.apply()
    }

    private fun legacyVersionSortKey(key: String): Long = key
        .removePrefix(LEGACY_BASE_URL_PREF_PREFIX)
        .split('.')
        .fold(0L) { result, part -> result * 1_000L + (part.toLongOrNull() ?: 0L).coerceAtMost(999L) }

    private fun rewriteRequestToLatestDomain(request: Request): Request {
        if (getManualBaseUrl() != null || !request.url.host.matches(JJAPTOON_HOST_REGEX)) {
            return request
        }

        return request.rewriteBaseUrl(latestBaseUrlResolver.resolve()) { it.matches(JJAPTOON_HOST_REGEX) }
    }

    private fun fetchLatestBaseUrl(): String? = runCatching(::fetchLatestBaseUrlFromJson).getOrNull()
        ?: runCatching(::fetchLatestBaseUrlFromPortal).getOrNull()

    private fun fetchLatestBaseUrlFromJson(): String? = noRedirectClient.newCall(
        GET(
            LATEST_DOMAIN_JSON_ENDPOINT,
            Headers.Builder()
                .set("Accept", "application/json")
                .set("Cache-Control", "no-cache")
                .build(),
        ),
    ).execute().use { response ->
        automaticRedirectLocation(response)
            ?.also { saveAutomaticBaseUrlSource(SOURCE_OFFICIAL_JSON_REDIRECT) }
            ?.let { return@use it }
        if (!response.isSuccessful) return@use null
        val body = response.body.string()
        runCatching { json.decodeFromString<LatestDomainResponse>(body).domain }
            .getOrNull()
            ?.let { normalizeBaseUrl(it, ::isAllowedAutomaticUrl) }
            ?.also { saveAutomaticBaseUrlSource(SOURCE_OFFICIAL_JSON) }
    }

    private fun fetchLatestBaseUrlFromPortal(): String? = noRedirectClient.newCall(
        GET(
            LATEST_DOMAIN_PORTAL,
            Headers.Builder()
                .set("Accept", "text/html,application/xhtml+xml")
                .set("Cache-Control", "no-cache")
                .build(),
        ),
    ).execute().use { response ->
        automaticRedirectLocation(response)
            ?.also { saveAutomaticBaseUrlSource(SOURCE_OFFICIAL_PORTAL_REDIRECT) }
            ?.let { return@use it }
        if (!response.isSuccessful) return@use null

        response.asJsoup()
            .select("a[href]")
            .asSequence()
            .mapNotNull { it.absUrl("href").toHttpUrlOrNull() }
            .mapNotNull { normalizeBaseUrl(it.toString(), ::isAllowedAutomaticUrl) }
            .firstOrNull()
            ?.also { saveAutomaticBaseUrlSource(SOURCE_OFFICIAL_PORTAL) }
    }

    private fun automaticRedirectLocation(response: Response): String? {
        if (response.code !in 300..399) return null
        val redirectUrl = response.header("Location")
            ?.let(response.request.url::resolve)
            ?: return null
        return normalizeBaseUrl(redirectUrl.toString(), ::isAllowedAutomaticUrl)
    }

    private fun resolveRedirectBaseUrl(): String? = runCatching {
        val probeBaseUrl = preferences.getString(LATEST_DOMAIN_URL_PREF, null)
            ?.let { normalizeBaseUrl(it, ::isAllowedAutomaticUrl) }
            ?: fallbackBaseUrl

        noRedirectClient.newCall(GET(probeBaseUrl)).execute().use { response ->
            val requestBaseUrl = response.request.url.takeIf(::isValidAutomaticBaseUrl)
                ?: return@use null

            if (response.code in 300..399) {
                val redirectUrl = response.header("Location")
                    ?.let(response.request.url::resolve)
                    ?: return@use null
                val redirectedBaseUrl = normalizeBaseUrl(redirectUrl.toString(), ::isAllowedAutomaticUrl)
                if (redirectedBaseUrl != null) return@use redirectedBaseUrl

                return@use requestBaseUrl
                    .takeIf { redirectUrl.host == it.host && isAllowedAutomaticRedirect(redirectUrl) }
                    ?.toString()
                    ?.trimEnd('/')
            }

            requestBaseUrl
                .takeIf { response.isSuccessful }
                ?.toString()
                ?.trimEnd('/')
        }?.also { saveAutomaticBaseUrlSource(SOURCE_NUMBERED_PROBE) }
    }.getOrNull()

    private fun migrateAutomaticBaseUrlCache() {
        if (preferences.getString(PREF_DEFAULT_BASE_URL, null) == fallbackBaseUrl) return

        val shouldInvalidateCache = shouldInvalidateNumberedDomainCache(
            cachedBaseUrl = preferences.getString(LATEST_DOMAIN_URL_PREF, null),
            legacyDomainNumber = null,
            minimumDomainNumber = DEFAULT_DOMAIN_NUMBER,
            hostNumberRegex = JJAPTOON_HOST_NUMBER_REGEX,
        )
        preferences.edit().apply {
            putString(PREF_DEFAULT_BASE_URL, fallbackBaseUrl)
            if (shouldInvalidateCache) {
                remove(LATEST_DOMAIN_URL_PREF)
                remove(LATEST_DOMAIN_FETCHED_AT_PREF)
                remove(LATEST_DOMAIN_ATTEMPTED_AT_PREF)
                remove(PREF_LATEST_DOMAIN_SOURCE)
            }
        }.apply()
    }

    private fun isAllowedAutomaticUrl(url: HttpUrl): Boolean = url.host.matches(JJAPTOON_HOST_REGEX)

    private fun isAllowedAutomaticRedirect(url: HttpUrl): Boolean = isAllowedAutomaticUrl(url) &&
        url.scheme == "https" &&
        url.port == 443 &&
        url.username.isEmpty() &&
        url.password.isEmpty() &&
        url.query == null &&
        url.fragment == null

    private fun isValidAutomaticBaseUrl(url: HttpUrl): Boolean = normalizeBaseUrl(
        url.toString(),
        ::isAllowedAutomaticUrl,
    ) != null

    private fun getCachedLatestBaseUrl(): String? = latestBaseUrlResolver.cachedBaseUrl()

    private fun normalizeManualBaseUrl(value: String): String? = normalizeBaseUrl(value)

    private fun saveAutomaticBaseUrlSource(source: String) {
        preferences.edit().putString(PREF_LATEST_DOMAIN_SOURCE, source).apply()
    }

    private fun baseUrlPreferenceSummary(): String {
        getManualBaseUrl()?.let { return "현재 수동 주소: $it" }

        val cachedBaseUrl = getCachedLatestBaseUrl()
        val source = preferences.getString(PREF_LATEST_DOMAIN_SOURCE, null)
        val fetchedAt = preferences.getLong(LATEST_DOMAIN_FETCHED_AT_PREF, 0L)
        val sourceSummary = when {
            cachedBaseUrl == null -> SOURCE_BUILD_DEFAULT
            System.currentTimeMillis() - fetchedAt < DynamicBaseUrlResolver.DEFAULT_CACHE_DURATION_MS ->
                "유효 자동 캐시 (${source ?: SOURCE_LEGACY_CACHE})"
            else -> "마지막 정상 캐시 (${source ?: SOURCE_LEGACY_CACHE})"
        }

        return "현재 자동 주소: ${cachedBaseUrl ?: fallbackBaseUrl}\n탐색 출처: $sourceSummary\n$BASE_URL_PREF_SUMMARY"
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
        private const val BASE_URL_PREF_SUMMARY = "비워두면 공식 포털에서 최신 주소를 자동 확인합니다."
        private const val LATEST_DOMAIN_PORTAL = "https://www.jjaptoon.com/"
        private const val LATEST_DOMAIN_JSON_ENDPOINT = "${LATEST_DOMAIN_PORTAL}data/domain.json"
        private const val PREF_MANUAL_BASE_URL = "manual_base_url"
        private const val PREF_MANUAL_BASE_URL_MIGRATED = "manual_base_url_migrated_v2"
        private const val LEGACY_BASE_URL_PREF_PREFIX = "overrideBaseUrl_v"
        private const val LEGACY_MANUAL_PREF_SUFFIX = "_manual"
        private const val PREF_DEFAULT_BASE_URL = "automatic_base_url_default"
        private const val LATEST_DOMAIN_URL_PREF = "latest_domain_url"
        private const val LATEST_DOMAIN_FETCHED_AT_PREF = "latest_domain_fetched_at"
        private const val LATEST_DOMAIN_ATTEMPTED_AT_PREF = "latest_domain_attempted_at"
        private const val PREF_LATEST_DOMAIN_SOURCE = "latest_domain_source"
        private const val DOMAIN_LOOKUP_TIMEOUT_SECONDS = 8L
        private const val FILTER_ALL = "all"
        private const val SORT_LATEST = "latest"
        private const val SORT_POPULAR = "popular"
        private const val HOME_PAGINATOR = "comicsPage"
        private const val SOURCE_OFFICIAL_JSON = "공식 JSON"
        private const val SOURCE_OFFICIAL_JSON_REDIRECT = "공식 JSON 리다이렉트"
        private const val SOURCE_OFFICIAL_PORTAL = "공식 포털"
        private const val SOURCE_OFFICIAL_PORTAL_REDIRECT = "공식 포털 리다이렉트"
        private const val SOURCE_NUMBERED_PROBE = "번호형 주소 확인"
        private const val SOURCE_LEGACY_CACHE = "기존 자동 캐시"
        private const val SOURCE_BUILD_DEFAULT = "빌드 기본 주소"

        private const val DEFAULT_DOMAIN_NUMBER = 4
        private val LEGACY_DEFAULT_BASE_URLS = setOf(
            "https://www.jjaptoon003.com",
            "https://jjaptoon003.com",
            "https://jjabtoon003.com",
            "https://www.jjabtoon003.com",
            "https://www.jjaptoon004.com",
            "https://jjaptoon004.com",
        )

        private val JJAPTOON_HOST_REGEX = Regex("^(?:www\\.)?jjaptoon\\d{3}\\.com$")
        private val JJAPTOON_HOST_NUMBER_REGEX = Regex("^(?:www\\.)?jjaptoon(\\d{3})\\.com$")
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class LatestDomainResponse(
            val domain: String,
        )

        private val imageSrcRegex = """loaded\s*\?\s*'([^']+)'""".toRegex()

        private val ignoredBadges = setOf("완결", "연재", "월", "화", "수", "목", "금", "토", "일")
    }
}

private const val CHAPTER_DATE_LENGTH = 10
private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("Asia/Seoul")
    isLenient = false
}
private val chapterDateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("Asia/Seoul")
    isLenient = false
}
private val chapterDateRegex = Regex("""\d{4}-\d{2}-\d{2}(?:\s+\d{2}:\d{2})?""")

internal fun parseJjaptoonChapterDate(paragraphTexts: List<String>): Long = paragraphTexts
    .asSequence()
    .drop(1)
    .map(::parseJjaptoonChapterDateText)
    .firstOrNull { it > 0L }
    ?: 0L

private fun parseJjaptoonChapterDateText(text: String): Long {
    val date = chapterDateRegex.find(text)?.value ?: return 0L
    val format = if (date.length > CHAPTER_DATE_LENGTH) chapterDateTimeFormat else chapterDateFormat
    return synchronized(format) { format.tryParse(date) }
}
