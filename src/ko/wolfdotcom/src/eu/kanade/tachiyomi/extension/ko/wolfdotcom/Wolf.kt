package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AutomaticDomainInterceptor
import keiyoushi.utils.BaseUrlCacheKeys
import keiyoushi.utils.DynamicBaseUrlResolver
import keiyoushi.utils.SharedPreferencesBaseUrlStorage
import keiyoushi.utils.findDiscoveredBaseUrl
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.normalizeBaseUrl
import keiyoushi.utils.normalizeDiscoveredBaseUrl
import keiyoushi.utils.parseAs
import keiyoushi.utils.rewriteBaseUrl
import keiyoushi.utils.shouldInvalidateNumberedDomainCache
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

open class Wolf(
    name: String,
    private val browsePath: String,
    private val entryPath: String,
    private val readerPath: String,
    private val filters: () -> FilterList,
) : HttpSource(),
    ConfigurableSource {

    override val name = "늑대닷컴 - $name"

    override val lang = "ko"

    override val baseUrl: String
        get() = getManualBaseUrl() ?: "https://wfwf$domainNumber.com"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(
            AutomaticDomainInterceptor(
                resolver = { latestBaseUrlResolver },
                manualBaseUrl = ::getManualBaseUrl,
                isAutomaticHost = { it.matches(domainHostRegex) },
                rewrite = ::rewriteDomainRequest,
                migrationTarget = { parseWolfMigrationPage(it.peekBody(128 * 1024).string(), it.request.url.toString()) },
                onRedirect = { saveAutomaticBaseUrlSource(SOURCE_NUMBERED_PROBE) },
            ),
        )
        .addNetworkInterceptor(::refererInterceptor)
        .build()

    private val preference: SharedPreferences by getPreferencesLazy()

    private val noRedirectClient = network.client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(DOMAIN_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(DOMAIN_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DOMAIN_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val latestBaseUrlResolver by lazy {
        migrateLegacyDomainCache()
        if (!preference.getBoolean("official_guide_discovery_v2", false)) {
            preference.edit()
                .remove(PREF_LATEST_DOMAIN_FETCHED_AT)
                .remove(PREF_LATEST_DOMAIN_ATTEMPTED_AT)
                .putBoolean("official_guide_discovery_v2", true)
                .apply()
        }
        DynamicBaseUrlResolver(
            storage = SharedPreferencesBaseUrlStorage(preference),
            keys = BaseUrlCacheKeys(
                cachedUrl = PREF_LATEST_DOMAIN_URL,
                fetchedAt = PREF_LATEST_DOMAIN_FETCHED_AT,
                attemptedAt = PREF_LATEST_DOMAIN_ATTEMPTED_AT,
            ),
            fallbackBaseUrl = { "https://${domainHost(domainNumber)}" },
            isAllowedAutomaticUrl = { it.host.matches(domainHostRegex) },
            discoverBaseUrl = ::fetchLatestBaseUrl,
            redirectBaseUrl = ::resolveRedirectBaseUrl,
            onAutomaticUrlResolved = { resolvedBaseUrl ->
                domainNumberRegex.matchEntire(resolvedBaseUrl.toHttpUrl().host)
                    ?.groupValues
                    ?.get(1)
                    ?.let { domainNumber = it }
            },
        )
    }

    private fun migrateLegacyDomainCache() {
        if (preference.getString(PREF_LATEST_DOMAIN_URL, null) != null) return
        preference.getString(PREF_LATEST_DOMAIN_NUM, null)
            ?.takeIf { domainHost(it).matches(domainHostRegex) }
            ?.let { preference.edit().putString(PREF_LATEST_DOMAIN_URL, "https://${domainHost(it)}").apply() }
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = fetchSearchManga(page, "", FilterList(SortFilter(1)))

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = fetchSearchManga(page, "", FilterList(SortFilter()))

    override fun getFilterList(): FilterList = filters()

    private val browseCache = WolfBrowseCache<BrowseItem>()

    class BrowseItem(
        val id: Int,
        val title: String,
        val cover: String?,
    )

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.isNotBlank()) {
            return querySearch(query)
        }

        // Capture the complete request before subscribing so filter edits cannot change its key.
        val request = searchMangaRequest(page, query, filters)
        val key = request.url.encodedPath + "?" + request.url.encodedQuery.orEmpty()
        return cancellableWolfCall({ client.newCall(request) }) { call ->
            val (items, hasNextPage) = browseCache.page(key, page) {
                call.execute().use { response ->
                    check(response.isSuccessful) { "browse request failed: ${response.code}" }
                    parseBrowsePage(response)
                }
            }
            MangasPage(
                items.map {
                    SManga.create().apply {
                        url = it.id.toString()
                        title = it.title
                        thumbnail_url = it.cover
                    }
                },
                hasNextPage,
            )
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/$browsePath".toHttpUrl().newBuilder().apply {
            filters.filterIsInstance<UrlPartFilter>().forEach { filter ->
                filter.addToUrl(this)
            }
        }.build()

        return GET(url, headers)
    }

    private fun parseBrowsePage(response: Response): List<BrowseItem> {
        val document = response.asJsoup()

        return document.select("a.t-card[href*=$entryPath]").mapNotNull {
            val id = it.absUrl("href").toHttpUrl()
                .queryParameter("toon")?.toIntOrNull()
                ?: return@mapNotNull null

            BrowseItem(
                id = id,
                title = it.selectFirst(".t-title")?.text()?.trim()
                    ?: return@mapNotNull null,
                cover = it.selectFirst(".t-img img")?.absUrl("src"),
            )
        }
    }

    private fun querySearch(query: String): Observable<MangasPage> {
        if (query.length < 2) {
            throw Exception("두 글자 이상 입력 해주세요.")
        }
        val searchUrl = "$baseUrl/sh?q=${URLEncoder.encode(query.trim(), "EUC-KR")}"

        return client.newCall(GET(searchUrl, headers))
            .asObservableSuccess()
            .map { response ->
                val document = Jsoup.parseBodyFragment(response.body.string(), searchUrl)
                val entries = document.select("a.t-card[href*=$entryPath]").mapNotNull { element ->
                    val mangaUrl = element.absUrl("href").toHttpUrl()
                    val id = mangaUrl.queryParameter("toon") ?: return@mapNotNull null
                    val title = element.selectFirst(".t-title")?.text()?.trim() ?: return@mapNotNull null

                    SManga.create().apply {
                        url = id
                        this.title = title
                        thumbnail_url = element.selectFirst(".t-img img")?.absUrl("src")
                    }
                }

                MangasPage(entries, false)
            }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment(entryPath)
        .addQueryParameter("toon", manga.url)
        .toString()

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst(".w-title")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst(".thumb-wrap img")?.absUrl("src")
            description = document.selectFirst("#summary")?.text()?.trim()
            genre = document.select(".genre-tags .gtag")
                .eachText()
                .joinToString()
                .takeIf { it.isNotBlank() }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    @Serializable
    class ChapterUrl(
        val toon: String,
        val num: String,
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val chapters = document.select("a.ep-item[href*=$readerPath]").mapNotNull { el ->
            val chapUrl = el.absUrl("href").toHttpUrl()
            val toon = chapUrl.queryParameter("toon") ?: return@mapNotNull null
            val num = chapUrl.queryParameter("num") ?: return@mapNotNull null
            SChapter.create().apply {
                url = ChapterUrl(
                    toon,
                    num,
                ).toJsonString()
                name = el.selectFirst(".ep-title")?.text()?.trim().orEmpty()
                chapter_number = num.toFloatOrNull() ?: -1f
                date_upload = dateFormat.tryParse(el.selectFirst(".ep-date")?.text())
            }
        }

        if (chapters.isEmpty()) return chapters

        val maxChapterNumber = chapters.maxOf { it.chapter_number.toInt() }
        if (maxChapterNumber <= chapters.size || maxChapterNumber > MAX_SYNTHETIC_CHAPTERS) {
            return chapters
        }

        val toon = response.request.url.queryParameter("toon") ?: return chapters
        val chaptersByNumber = chapters.associateBy { it.chapter_number.toInt() }

        return (maxChapterNumber downTo 1).map { number ->
            chaptersByNumber[number] ?: SChapter.create().apply {
                url = ChapterUrl(toon, number.toString()).toJsonString()
                name = "회차 $number"
                chapter_number = number.toFloat()
            }
        }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

    override fun getChapterUrl(chapter: SChapter): String {
        val chapUrl = chapter.url.parseAs<ChapterUrl>()

        return baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(readerPath)
            .addQueryParameter("toon", chapUrl.toon)
            .addQueryParameter("num", chapUrl.num)
            .toString()
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        return document.select(".viewer-wrap img[data-src]").mapIndexed { idx, img ->
            Page(idx, imageUrl = img.absUrl("data-src"))
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_MANUAL_BASE_URL
            title = "Override BaseUrl"
            summary = baseUrlPreferenceSummary()
            setDefaultValue("")
            dialogMessage = "비워두면 공식 안내 사이트와 공식 텔레그램 채널에서 최신 주소를 자동 확인합니다."
            setOnPreferenceChangeListener { preference, newValue ->
                val value = (newValue as? String).orEmpty().trim()
                if (value.isEmpty()) {
                    this@Wolf.preference.edit().remove(PREF_MANUAL_BASE_URL).apply()
                    (preference as EditTextPreference).text = ""
                    preference.summary = baseUrlPreferenceSummary()
                    return@setOnPreferenceChangeListener false
                }

                val normalized = normalizeManualBaseUrl(value)
                    ?: return@setOnPreferenceChangeListener false
                this@Wolf.preference.edit().putString(PREF_MANUAL_BASE_URL, normalized).apply()
                (preference as EditTextPreference).text = normalized
                preference.summary = "현재 수동 주소: $normalized"
                false
            }
        }.also(screen::addPreference)
    }

    private fun getManualBaseUrl(): String? = preference
        .getString(PREF_MANUAL_BASE_URL, null)
        ?.let(::normalizeManualBaseUrl)

    private fun normalizeManualBaseUrl(value: String): String? = normalizeBaseUrl(value)

    private fun baseUrlPreferenceSummary(): String = getManualBaseUrl()
        ?.let { "현재 수동 주소: $it" }
        ?: run {
            val cachedBaseUrl = latestBaseUrlResolver.cachedBaseUrl()
            val source = preference.getString(PREF_LATEST_DOMAIN_SOURCE, null)
            val fetchedAt = preference.getLong(PREF_LATEST_DOMAIN_FETCHED_AT, 0L)
            val sourceSummary = when {
                cachedBaseUrl == null -> SOURCE_BUILD_DEFAULT
                System.currentTimeMillis() - fetchedAt < DynamicBaseUrlResolver.DEFAULT_CACHE_DURATION_MS ->
                    "유효 자동 캐시 (${source ?: SOURCE_LEGACY_CACHE})"
                else -> "마지막 정상 캐시 (${source ?: SOURCE_LEGACY_CACHE})"
            }

            "현재 자동 주소: ${cachedBaseUrl ?: "https://${domainHost(domainNumber)}"}\n" +
                "탐색 출처: $sourceSummary\n" +
                "비워두면 공식 안내 사이트에서 최신 주소를 자동 확인합니다."
        }

    private var domainNumber = ""
        get() {
            val currentValue = field
            if (currentValue.isNotEmpty()) return currentValue

            val prefValue = preference.getString(PREF_DOMAIN_NUM, "")!!
            val prefDefaultValue = preference.getString(PREF_DOMAIN_NUM_DEFAULT, "")!!

            if (prefDefaultValue != DEFAULT_DOMAIN_NUMBER) {
                val shouldInvalidateCache = shouldInvalidateNumberedDomainCache(
                    cachedBaseUrl = preference.getString(PREF_LATEST_DOMAIN_URL, null),
                    legacyDomainNumber = preference.getString(PREF_LATEST_DOMAIN_NUM, null),
                    minimumDomainNumber = DEFAULT_DOMAIN_NUMBER.toInt(),
                    hostNumberRegex = domainNumberRegex,
                )
                preference.edit().apply {
                    putString(PREF_DOMAIN_NUM_DEFAULT, DEFAULT_DOMAIN_NUMBER)
                    putString(PREF_DOMAIN_NUM, DEFAULT_DOMAIN_NUMBER)
                    if (shouldInvalidateCache) {
                        remove(PREF_LATEST_DOMAIN_NUM)
                        remove(PREF_LATEST_DOMAIN_URL)
                        remove(PREF_LATEST_DOMAIN_FETCHED_AT)
                        remove(PREF_LATEST_DOMAIN_ATTEMPTED_AT)
                        remove(PREF_LATEST_DOMAIN_SOURCE)
                    }
                }.apply()

                field = DEFAULT_DOMAIN_NUMBER
                return DEFAULT_DOMAIN_NUMBER
            }

            if (prefValue.isNotEmpty()) {
                field = prefValue
                return prefValue
            }

            return DEFAULT_DOMAIN_NUMBER
        }
        set(value) {
            preference.edit().putString(PREF_DOMAIN_NUM, value).apply()

            field = value
        }

    private fun rewriteDomainRequest(request: Request): Request {
        val manualBaseUrl = getManualBaseUrl()?.toHttpUrl()
        val isAutomaticHost = request.url.host.matches(domainHostRegex)
        val isManualHost = manualBaseUrl != null && request.url.host == manualBaseUrl.host
        if (!isAutomaticHost && !isManualHost) return request

        val resolvedBaseUrl = manualBaseUrl?.toString() ?: latestBaseUrlResolver.resolve()
        return request.rewriteBaseUrl(getManualBaseUrl() ?: resolvedBaseUrl) { host ->
            host.matches(domainHostRegex) || host == manualBaseUrl?.host
        }
    }

    private val domainDiscovery by lazy { WolfDomainDiscovery(noRedirectClient) }

    private fun fetchLatestBaseUrl(): String? = domainDiscovery.discover()?.let {
        saveAutomaticBaseUrlSource(it.source)
        it.baseUrl
    }

    private fun resolveRedirectBaseUrl(): String? = runCatching {
        val current = latestBaseUrlResolver.cachedBaseUrl() ?: "https://${domainHost(domainNumber)}"
        noRedirectClient.newCall(GET(current, headers)).execute().use { response ->
            val resolvedBaseUrl = if (response.code in 300..399) {
                response.header("Location")
                    ?.let(response.request.url::resolve)
                    ?.takeIf(::isValidDiscoveredDomain)
                    ?.toString()
                    ?.trimEnd('/')
            } else if (response.isSuccessful) {
                val body = response.body.string()
                parseWolfMigrationPage(body, current) ?: current.takeIf {
                    // A 200 migration/parking/error page is not a healthy catalogue.
                    Jsoup.parse(body).selectFirst("a.t-card, .w-title") != null
                }
            } else {
                null
            }

            resolvedBaseUrl?.also { saveAutomaticBaseUrlSource(SOURCE_NUMBERED_PROBE) }
        }
    }.getOrNull()

    private fun isValidDiscoveredDomain(url: okhttp3.HttpUrl): Boolean = url.scheme == "https" &&
        url.host.matches(domainHostRegex) &&
        url.port == 443 &&
        url.encodedPath == "/" &&
        url.query == null &&
        url.fragment == null &&
        url.username.isEmpty() &&
        url.password.isEmpty()

    private fun saveAutomaticBaseUrlSource(source: String) {
        preference.edit().putString(PREF_LATEST_DOMAIN_SOURCE, source).apply()
    }

    private fun domainHost(number: String) = "wfwf$number.com"

    private fun refererInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Referer", "$baseUrl/")
            .build()

        return chain.proceed(request)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    companion object {
        private const val MAX_SYNTHETIC_CHAPTERS = 5_000
        private val domainHostRegex = wolfDomainHostRegex
        private val domainNumberRegex = wolfDomainNumberRegex
    }
}

private const val PREF_DOMAIN_NUM = "domain_number"
private const val PREF_DOMAIN_NUM_DEFAULT = "domain_number_default"
private const val DEFAULT_DOMAIN_NUMBER = "435"
private const val PREF_MANUAL_BASE_URL = "manual_base_url"
private const val PREF_LATEST_DOMAIN_NUM = "latest_domain_number"
private const val PREF_LATEST_DOMAIN_URL = "latest_domain_url"
private const val PREF_LATEST_DOMAIN_FETCHED_AT = "latest_domain_fetched_at"
private const val PREF_LATEST_DOMAIN_ATTEMPTED_AT = "latest_domain_attempted_at"
private const val PREF_LATEST_DOMAIN_SOURCE = "latest_domain_source"
private const val DOMAIN_LOOKUP_TIMEOUT_SECONDS = 8L
private const val SOURCE_NUMBERED_PROBE = "번호형 주소 확인"
private const val SOURCE_LEGACY_CACHE = "기존 자동 캐시"
private const val SOURCE_BUILD_DEFAULT = "빌드 기본 주소"

private val wolfDomainHostRegex = Regex("""^wfwf\d+\.com$""")
private val wolfDomainNumberRegex = Regex("""^wfwf(\d+)\.com$""")

internal fun parseWolfLatestBaseUrl(html: String, portalUrl: String): String? {
    val document = Jsoup.parse(html, portalUrl)
    val candidates = sequence {
        yieldAll(document.select("a[href]").asSequence().map { it.attr("href") })
        yield(document.outerHtml())
    }

    return candidates
        .mapNotNull { findDiscoveredBaseUrl(it) { host -> host.matches(wolfDomainHostRegex) } }
        .firstOrNull()
}

internal fun parseWolfMigrationPage(html: String, current: String): String? = Jsoup.parse(html, current)
    .select("a.main-btn[href]").firstNotNullOfOrNull {
        normalizeDiscoveredBaseUrl(it.attr("href")) { host -> host.matches(wolfDomainHostRegex) }
    }
