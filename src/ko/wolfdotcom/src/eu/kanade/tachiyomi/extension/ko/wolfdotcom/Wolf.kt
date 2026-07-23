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
import keiyoushi.utils.BaseUrlCacheKeys
import keiyoushi.utils.DynamicBaseUrlResolver
import keiyoushi.utils.SharedPreferencesBaseUrlStorage
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.normalizeBaseUrl
import keiyoushi.utils.parseAs
import keiyoushi.utils.rewriteBaseUrl
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        .addInterceptor(::domainNumberInterceptor)
        .addNetworkInterceptor(::refererInterceptor)
        .build()

    private val preference: SharedPreferences by getPreferencesLazy()

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
        migrateLegacyDomainCache()
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

    private lateinit var browseCache: List<List<BrowseItem>>

    class BrowseItem(
        val id: Int,
        val title: String,
        val cover: String?,
    )

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.isNotBlank()) {
            return querySearch(query)
        }

        return if (page == 1) {
            client.newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map {
                    parseBrowsePage(it)
                    paginatedBrowsePage(0)
                }
        } else {
            Observable.just(
                paginatedBrowsePage(page - 1),
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

    private fun parseBrowsePage(response: Response) {
        val document = response.asJsoup()

        browseCache = document.select("a.t-card[href*=$entryPath]").mapNotNull {
            val id = it.absUrl("href").toHttpUrl()
                .queryParameter("toon")?.toIntOrNull()
                ?: return@mapNotNull null

            BrowseItem(
                id = id,
                title = it.selectFirst(".t-title")?.text()?.trim()
                    ?: return@mapNotNull null,
                cover = it.selectFirst(".t-img img")?.absUrl("src"),
            )
        }.chunked(20).ifEmpty { listOf(emptyList()) }
    }

    private fun paginatedBrowsePage(index: Int): MangasPage = MangasPage(
        browseCache[index].map {
            SManga.create().apply {
                url = it.id.toString()
                title = it.title
                thumbnail_url = it.cover
            }
        },
        browseCache.lastIndex > index,
    )

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
            dialogMessage = "비워두면 $LATEST_DOMAIN_ENDPOINT 에서 최신 주소를 자동 확인합니다."
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
        ?: "현재 자동 주소: ${latestBaseUrlResolver.cachedBaseUrl() ?: "https://${domainHost(domainNumber)}"}\n" +
        "비워두면 공식 안내 사이트에서 최신 주소를 자동 확인합니다."

    private var domainNumber = ""
        get() {
            val currentValue = field
            if (currentValue.isNotEmpty()) return currentValue

            val prefValue = preference.getString(PREF_DOMAIN_NUM, "")!!
            val prefDefaultValue = preference.getString(PREF_DOMAIN_NUM_DEFAULT, "")!!

            if (prefDefaultValue != DEFAULT_DOMAIN_NUMBER) {
                preference.edit()
                    .putString(PREF_DOMAIN_NUM_DEFAULT, DEFAULT_DOMAIN_NUMBER)
                    .putString(PREF_DOMAIN_NUM, DEFAULT_DOMAIN_NUMBER)
                    .apply()

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

    private fun domainNumberInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val manualBaseUrl = getManualBaseUrl()?.toHttpUrl()
        val isAutomaticHost = request.url.host.matches(domainHostRegex)
        val isManualHost = manualBaseUrl != null && request.url.host == manualBaseUrl.host
        if (!isAutomaticHost && !isManualHost) return chain.proceed(request)

        val resolvedBaseUrl = manualBaseUrl?.toString() ?: latestBaseUrlResolver.resolve()
        val rewrittenRequest = request.rewriteBaseUrl(resolvedBaseUrl) { host ->
            host.matches(domainHostRegex) || host == manualBaseUrl?.host
        }

        return chain.proceed(rewrittenRequest)
    }

    private fun fetchLatestBaseUrl(): String? = runCatching {
        domainLookupClient.newCall(
            GET(
                LATEST_DOMAIN_ENDPOINT,
                headersBuilder()
                    .set("Accept", "text/html,application/xhtml+xml")
                    .set("Cache-Control", "no-cache")
                    .build(),
            ),
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null

            response.asJsoup()
                .select("a[href]")
                .asSequence()
                .mapNotNull { it.attr("href").toHttpUrlOrNull() }
                .firstOrNull(::isValidDiscoveredDomain)
                ?.toString()
                ?.trimEnd('/')
        }
    }.getOrNull()

    private fun resolveRedirectBaseUrl(): String? = runCatching {
        noRedirectClient.newCall(GET("https://${domainHost(domainNumber)}", headers)).execute().use { response ->
            response.header("Location")
                ?.toHttpUrlOrNull()
                ?.takeIf(::isValidDiscoveredDomain)
                ?.toString()
                ?.trimEnd('/')
                ?: response.request.url
                    .takeIf(::isValidDiscoveredDomain)
                    ?.toString()
                    ?.trimEnd('/')
        }
    }.getOrNull()

    private fun isValidDiscoveredDomain(url: okhttp3.HttpUrl): Boolean = url.scheme == "https" &&
        url.host.matches(domainHostRegex) &&
        url.port == 443 &&
        url.encodedPath == "/" &&
        url.query == null &&
        url.username.isEmpty() &&
        url.password.isEmpty()

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
        private val domainHostRegex = Regex("""^wfwf\d+\.com$""")
        private val domainNumberRegex = Regex("""^wfwf(\d+)\.com$""")
    }
}

private const val PREF_DOMAIN_NUM = "domain_number"
private const val PREF_DOMAIN_NUM_DEFAULT = "domain_number_default"
private const val PREF_MANUAL_BASE_URL = "manual_base_url"
private const val PREF_LATEST_DOMAIN_NUM = "latest_domain_number"
private const val PREF_LATEST_DOMAIN_URL = "latest_domain_url"
private const val PREF_LATEST_DOMAIN_FETCHED_AT = "latest_domain_fetched_at"
private const val PREF_LATEST_DOMAIN_ATTEMPTED_AT = "latest_domain_attempted_at"
private const val LATEST_DOMAIN_ENDPOINT = "https://a14c.com/"
private const val DOMAIN_LOOKUP_TIMEOUT_SECONDS = 8L
