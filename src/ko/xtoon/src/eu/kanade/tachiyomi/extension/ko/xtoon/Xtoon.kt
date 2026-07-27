package eu.kanade.tachiyomi.extension.ko.xtoon

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
import keiyoushi.utils.BaseUrlCacheKeys
import keiyoushi.utils.DynamicBaseUrlResolver
import keiyoushi.utils.SharedPreferencesBaseUrlStorage
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.normalizeBaseUrl
import keiyoushi.utils.rewriteBaseUrl
import keiyoushi.utils.shouldInvalidateNumberedDomainCache
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class Xtoon :
    HttpSource(),
    ConfigurableSource {

    override val name = "Xtoon"

    override val lang = "ko"

    private val defaultBaseUrl = "https://t4.xtoon365.com"

    override val baseUrl: String
        get() = getManualBaseUrl() ?: defaultBaseUrl

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val cdnRewrittenRequest = if (originalRequest.url.host == SOURCE_CDN_HOST) {
                originalRequest.newBuilder()
                    .url(originalRequest.url.newBuilder().host(WORKING_CDN_HOST).build())
                    .build()
            } else {
                originalRequest
            }
            val manualBaseUrl = getManualBaseUrl()?.toHttpUrl()
            val isAutomaticHost = cdnRewrittenRequest.url.host.matches(AUTOMATIC_HOST_REGEX)
            val isManualHost = manualBaseUrl != null && cdnRewrittenRequest.url.host == manualBaseUrl.host
            val resolvedBaseUrl = when {
                !isAutomaticHost && !isManualHost -> null
                manualBaseUrl != null -> manualBaseUrl.toString()
                else -> latestBaseUrlResolver.resolve()
            }
            val request = if (resolvedBaseUrl != null) {
                cdnRewrittenRequest.rewriteBaseUrl(resolvedBaseUrl) { host ->
                    host.matches(AUTOMATIC_HOST_REGEX) || host == manualBaseUrl?.host
                }
            } else {
                cdnRewrittenRequest
            }
            val requestBuilder = request.newBuilder()
                .removeHeader("rsc")
                .removeHeader("next-router-state-tree")
                .removeHeader("next-url")

            if (request.header("Accept") == null) {
                requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            }

            chain.proceed(requestBuilder.build())
        }
        .build()

    private val domainLookupClient = network.client.newBuilder()
        .connectTimeout(DOMAIN_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DOMAIN_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val noRedirectClient = network.client.newBuilder()
        .followRedirects(false)
        .connectTimeout(DOMAIN_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DOMAIN_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val latestBaseUrlResolver by lazy {
        migrateAutomaticBaseUrlCache()
        DynamicBaseUrlResolver(
            storage = SharedPreferencesBaseUrlStorage(preferences),
            keys = BaseUrlCacheKeys(
                cachedUrl = PREF_LATEST_BASE_URL,
                fetchedAt = PREF_LATEST_BASE_URL_FETCHED_AT,
                attemptedAt = PREF_LATEST_BASE_URL_ATTEMPTED_AT,
            ),
            fallbackBaseUrl = { defaultBaseUrl },
            isAllowedAutomaticUrl = ::isAllowedAutomaticUrl,
            discoverBaseUrl = ::fetchLatestBaseUrl,
            redirectBaseUrl = ::resolveRedirectBaseUrl,
        )
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }

    override fun popularMangaRequest(page: Int): Request = GET(categoryUrl(page, order = "hits"), headers)

    override fun popularMangaParse(response: Response): MangasPage = mangaPageParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(categoryUrl(page, order = "addtime"), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = mangaPageParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("index.php/search")
                .addQueryParameter("key", query.trim())
                .build()
                .toString()
        } else {
            val theme = filters.filterIsInstance<ThemeFilter>().firstOrNull()?.selectedValue ?: "300"
            val order = filters.filterIsInstance<SortFilter>().firstOrNull()?.selectedValue ?: "addtime"
            val finish = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selectedValue.orEmpty()
            val weekday = filters.filterIsInstance<WeekdayFilter>().firstOrNull()?.selectedValue.orEmpty()
            val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedValue.orEmpty()
            val tag = genre.ifBlank { weekday }
            categoryUrl(page, theme, order, finish, tag)
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaPageParse(response)

    private fun categoryUrl(page: Int, theme: String = "300", order: String = "addtime", finish: String = "", tag: String = ""): String = buildString {
        append(baseUrl)
        append("/category/theme/")
        append(theme)
        if (finish.isNotBlank()) {
            append("/finish/")
            append(finish)
        }
        if (tag.isNotBlank()) {
            append("/tags/")
            append(tag)
        }
        append("/order/")
        append(order)
        if (page > 1) {
            append("/page/")
            append(page)
            append("?ajax=1")
        }
    }

    private fun mangaPageParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a[href^=/comic/]:has(img)")
            .distinctBy { it.attr("href") }
            .mapNotNull { element ->
                val href = element.attr("href")
                val title = element.selectFirst("img")?.attr("alt")
                    ?: element.selectFirst("h6")?.ownText()
                    ?: element.text()
                if (title.isBlank()) return@mapNotNull null

                SManga.create().apply {
                    this.title = title.trim()
                    setUrlWithoutDomain(element.absUrl("href"))
                    thumbnail_url = element.selectFirst("img")?.imageUrl()
                }
            }

        val hasNextPage = if (response.request.url.encodedPath.contains("/index.php/search")) {
            false
        } else if (response.request.url.queryParameter("ajax") == "1") {
            mangas.isNotEmpty()
        } else {
            val maxPage = Regex("""maxpage\s*=\s*(\d+)""").find(document.html())?.groupValues?.get(1)?.toIntOrNull()
            maxPage == null || response.request.url.pathSegments.lastOrNull()?.toIntOrNull()?.let { it < maxPage } ?: mangas.isNotEmpty()
        }

        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val info = document.selectFirst(".katoon-info")

        return SManga.create().apply {
            title = info?.selectFirst("h4")?.ownText()?.trim().orEmpty()
            author = info?.selectFirst("h4 + .small")?.text()?.trim().orEmpty()
            description = info?.selectFirst("small")?.text()?.trim()
            genre = info?.select(".tags a")?.joinToString { it.text().removePrefix("#") }
            thumbnail_url = info?.selectFirst("#toon-img img")?.imageUrl()
            status = when {
                document.select(".chapter-list-item strong").any { it.text().contains("최종화") } -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(".chapter-list a[href^=/chapter/]").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = element.selectFirst("strong")?.ownText()?.trim()?.ifBlank { null }
                    ?: element.selectFirst("strong")?.text()?.substringBefore("P")?.trim()
                    ?: element.text().trim()
                date_upload = dateFormat.tryParse(element.selectFirst("small")?.text().orEmpty())
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headersBuilder().set("Referer", baseUrl + chapter.url).build())

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("img.lazy-read[data-original]").mapIndexed { index, element ->
            Page(index, imageUrl = element.absUrl("data-original").toWorkingImageUrl())
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_MANUAL_BASE_URL
            title = "Override BaseUrl"
            summary = baseUrlPreferenceSummary()
            setDefaultValue("")
            dialogMessage = "비워두면 공식 최신주소 페이지와 번호형 주소 리다이렉트에서 자동 확인합니다."
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

    override fun getFilterList(): FilterList = FilterList(
        ThemeFilter(),
        SortFilter(),
        StatusFilter(),
        WeekdayFilter(),
        GenreFilter(),
        Filter.Header("요일과 장르를 동시에 고르면 장르가 우선 적용됩니다."),
    )

    private fun Element.imageUrl(): String? = when {
        hasAttr("data-original") -> absUrl("data-original")
        hasAttr("data-src") -> absUrl("data-src")
        else -> absUrl("src")
    }.takeIf { it.isNotBlank() && !it.contains("/packs/mccms/empty.png") }
        ?.toWorkingImageUrl()

    private fun String.toWorkingImageUrl(): String {
        val url = toHttpUrlOrNull() ?: return this
        if (url.host != SOURCE_CDN_HOST) return this

        return url.newBuilder()
            .host(WORKING_CDN_HOST)
            .build()
            .toString()
    }

    private fun getManualBaseUrl(): String? = preferences.getString(PREF_MANUAL_BASE_URL, null)
        ?.let(::normalizeBaseUrl)

    private fun baseUrlPreferenceSummary(): String = getManualBaseUrl()
        ?.let { "현재 수동 주소: $it" }
        ?: "현재 자동 주소: ${latestBaseUrlResolver.cachedBaseUrl() ?: defaultBaseUrl}\n" +
        "탐색 출처: 공식 최신주소 페이지 → 번호형 주소 리다이렉트"

    private fun migrateAutomaticBaseUrlCache() {
        if (preferences.getString(PREF_DEFAULT_BASE_URL, null) == defaultBaseUrl) return

        val shouldInvalidateCache = shouldInvalidateNumberedDomainCache(
            cachedBaseUrl = preferences.getString(PREF_LATEST_BASE_URL, null),
            legacyDomainNumber = null,
            minimumDomainNumber = DEFAULT_DOMAIN_NUMBER,
            hostNumberRegex = AUTOMATIC_HOST_NUMBER_REGEX,
        )
        preferences.edit().apply {
            putString(PREF_DEFAULT_BASE_URL, defaultBaseUrl)
            if (shouldInvalidateCache) {
                remove(PREF_LATEST_BASE_URL)
                remove(PREF_LATEST_BASE_URL_FETCHED_AT)
                remove(PREF_LATEST_BASE_URL_ATTEMPTED_AT)
            }
        }.apply()
    }

    private fun fetchLatestBaseUrl(): String? = runCatching {
        domainLookupClient.newCall(
            GET(
                LATEST_DOMAIN_ENDPOINT,
                Headers.Builder()
                    .set("User-Agent", headers["User-Agent"].orEmpty())
                    .set("Accept", "text/html,application/xhtml+xml")
                    .set("Cache-Control", "no-cache")
                    .build(),
            ),
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null

            normalizeBaseUrl(response.request.url.toString(), ::isAllowedAutomaticUrl)
                ?.let { return@use it }

            response.asJsoup()
                .select("a[href]")
                .asSequence()
                .mapNotNull { it.absUrl("href").toHttpUrlOrNull() }
                .mapNotNull { normalizeBaseUrl(it.toString(), ::isAllowedAutomaticUrl) }
                .firstOrNull()
        }
    }.getOrNull()

    private fun resolveRedirectBaseUrl(): String? = runCatching {
        val probeBaseUrl = preferences.getString(PREF_LATEST_BASE_URL, null)
            ?.let { normalizeBaseUrl(it, ::isAllowedAutomaticUrl) }
            ?: defaultBaseUrl

        noRedirectClient.newCall(GET(probeBaseUrl, headers)).execute().use { response ->
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
        }
    }.getOrNull()

    private fun isAllowedAutomaticUrl(url: HttpUrl): Boolean = url.host.matches(AUTOMATIC_HOST_REGEX)

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

    private companion object {
        const val DEFAULT_DOMAIN_NUMBER = 4
        val AUTOMATIC_HOST_REGEX = Regex("""^t\d+\.xtoon365\.com$""")
        val AUTOMATIC_HOST_NUMBER_REGEX = Regex("""^t(\d+)\.xtoon365\.com$""")
        const val LATEST_DOMAIN_ENDPOINT = "https://xn--9t4b31dr7o.com/"
        const val PREF_MANUAL_BASE_URL = "manual_base_url"
        const val PREF_DEFAULT_BASE_URL = "automatic_base_url_default"
        const val PREF_LATEST_BASE_URL = "latest_base_url"
        const val PREF_LATEST_BASE_URL_FETCHED_AT = "latest_base_url_fetched_at"
        const val PREF_LATEST_BASE_URL_ATTEMPTED_AT = "latest_base_url_attempted_at"
        const val DOMAIN_LOOKUP_TIMEOUT_SECONDS = 8L
        const val SOURCE_CDN_HOST = "cdn.xtoon33.com"
        const val WORKING_CDN_HOST = "xtoon2.b-cdn.net"
    }

    private class ThemeFilter :
        UriPartFilter(
            "분류",
            arrayOf(
                Pair("일반웹툰", "300"),
                Pair("BL&GL", "301"),
                Pair("성인웹툰", "302"),
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "정렬",
            arrayOf(
                Pair("최신", "addtime"),
                Pair("인기", "hits"),
            ),
        )

    private class StatusFilter :
        UriPartFilter(
            "상태",
            arrayOf(
                Pair("전체", ""),
                Pair("연재중", "1"),
                Pair("완결", "2"),
            ),
        )

    private class WeekdayFilter :
        UriPartFilter(
            "요일",
            arrayOf(
                Pair("전체", ""),
                Pair("월", "400"),
                Pair("화", "401"),
                Pair("수", "402"),
                Pair("목", "403"),
                Pair("금", "404"),
                Pair("토", "405"),
                Pair("일", "406"),
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "장르",
            arrayOf(
                Pair("전체", ""),
                Pair("동양풍", "503"),
                Pair("액션", "504"),
                Pair("판타지", "500"),
                Pair("드라마", "6542"),
                Pair("로맨스", "6545"),
                Pair("재회물", "6547"),
                Pair("인외존재", "6548"),
                Pair("다정남", "6549"),
                Pair("순정남", "6550"),
                Pair("짝사랑남", "6551"),
                Pair("엉뚱발랄녀", "6552"),
                Pair("털털녀", "6553"),
                Pair("달달물", "6554"),
                Pair("로맨틱코미디", "6555"),
                Pair("학원", "6557"),
                Pair("트라우마", "6558"),
                Pair("계약관계", "6560"),
                Pair("일상", "6561"),
                Pair("아이돌", "6564"),
                Pair("배우", "6565"),
                Pair("감성", "6568"),
                Pair("전쟁", "6569"),
                Pair("생존", "6570"),
                Pair("회귀", "6571"),
                Pair("영지", "6572"),
                Pair("노력", "6573"),
                Pair("성장", "6574"),
                Pair("아티팩트", "6575"),
                Pair("용병", "6576"),
                Pair("왕족/귀족", "6577"),
                Pair("무공", "6578"),
                Pair("군인", "6579"),
                Pair("창", "6580"),
                Pair("만능", "6581"),
                Pair("2021 지상최대공모전", "6582"),
                Pair("까칠남", "6583"),
                Pair("스릴러", "6584"),
                Pair("서스펜스", "6585"),
                Pair("BL", "6588"),
                Pair("로판", "6594"),
                Pair("개그", "6595"),
                Pair("정통", "6597"),
                Pair("퓨전", "6598"),
                Pair("먼치킨", "6599"),
                Pair("시스템", "6600"),
                Pair("조력자", "6601"),
                Pair("초능력", "6602"),
                Pair("성인", "6603"),
                Pair("해외 순정", "6604"),
                Pair("성인웹툰", "6605"),
                Pair("해외웹툰", "6606"),
                Pair("완결", "6607"),
                Pair("아포칼립스", "6623"),
                Pair("게임", "6625"),
                Pair("공포/스릴러", "6631"),
                Pair("긴장감 있는", "6632"),
                Pair("궁금하게 하는", "6633"),
                Pair("범죄스릴러물", "6634"),
                Pair("옴니버스", "6636"),
                Pair("집착", "6638"),
                Pair("애증", "6639"),
                Pair("느와르", "6640"),
                Pair("GL", "6641"),
                Pair("4차원", "6642"),
                Pair("떡대수", "6644"),
                Pair("직진수", "6645"),
                Pair("짝사랑", "6646"),
                Pair("사내연애", "6647"),
                Pair("선후배", "6648"),
                Pair("레드스트링", "6649"),
                Pair("능욕", "6650"),
                Pair("폭력", "6651"),
                Pair("실눈공", "6652"),
                Pair("강공", "6653"),
                Pair("난폭공", "6654"),
                Pair("굴림수", "6655"),
                Pair("소시오패스", "6656"),
                Pair("조폭", "6657"),
                Pair("재벌", "6658"),
                Pair("후회물", "6659"),
                Pair("선결혼후연애", "6660"),
                Pair("머니게임", "6661"),
                Pair("집착공", "6662"),
                Pair("순정공", "6663"),
                Pair("다정수", "6664"),
                Pair("우정", "6665"),
                Pair("헌신공", "6666"),
                Pair("단정수", "6667"),
                Pair("적극수", "6668"),
                Pair("순애", "6669"),
                Pair("연애", "6670"),
                Pair("일편단심", "6671"),
                Pair("현대물", "6672"),
                Pair("잔망수", "6673"),
                Pair("환생", "6674"),
                Pair("정령", "6675"),
                Pair("마계", "6676"),
                Pair("마법사", "6677"),
                Pair("정령술사", "6678"),
                Pair("망나니", "6679"),
            ),
        )
    private abstract class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        val selectedValue: String
            get() = vals[state].second
    }
}
