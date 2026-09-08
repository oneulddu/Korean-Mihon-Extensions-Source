package eu.kanade.tachiyomi.extension.ko.blacktoon

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.utils.rewriteBaseUrl
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

class BlackToon :
    HttpSource(),
    ConfigurableSource {

    override val name = "블랙툰"

    override val lang = "ko"

    private val preferences: SharedPreferences by getPreferencesLazy()

    @Volatile
    private var currentBaseUrlHost = ""
    override val baseUrl: String
        get() = getManualBaseUrl() ?: "https://blacktoon$domainNumber.com"

    private val cdnUrl = "https://aa3cc9.speedwebgo.com/"
    private val cdnHost = cdnUrl.toHttpUrl().host

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")

    override val client = network.client.newBuilder().addInterceptor { chain ->
        val originalRequest = chain.request()
        val manualBaseUrl = getManualBaseUrl()?.toHttpUrl()
        val isAutomaticHost = originalRequest.url.host.matches(domainHostRegex)
        val isManualHost = manualBaseUrl != null && originalRequest.url.host == manualBaseUrl.host
        val isManagedRequest = isAutomaticHost || isManualHost
        val resolvedBaseUrl = when {
            !isManagedRequest -> null
            manualBaseUrl != null -> manualBaseUrl
            else -> latestBaseUrlResolver.resolve().toHttpUrl()
        }

        if (resolvedBaseUrl != null && manualBaseUrl == null) {
            currentBaseUrlHost = resolvedBaseUrl.host
        }
        val requestHeaderBaseUrl = resolvedBaseUrl ?: if (originalRequest.url.host == cdnHost) {
            activeBaseUrl().toHttpUrl()
        } else {
            null
        }

        val rewrittenRequest = if (resolvedBaseUrl != null) {
            originalRequest.rewriteBaseUrl(resolvedBaseUrl.toString()) { host ->
                host.matches(domainHostRegex) || host == manualBaseUrl?.host
            }
        } else {
            originalRequest
        }
        val request = rewrittenRequest.newBuilder().apply {
            if (requestHeaderBaseUrl != null) {
                header("Referer", requestHeaderBaseUrl.toString())
                header("Origin", requestHeaderBaseUrl.toString().trimEnd('/'))
            }
        }.build()

        return@addInterceptor chain.proceed(request)
    }.build()

    private val noRedirectClient = network.client.newBuilder()
        .followRedirects(false)
        .build()

    private val domainLookupClient = network.client.newBuilder()
        .connectTimeout(DOMAIN_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DOMAIN_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val latestBaseUrlResolver by lazy {
        migrateLegacyDomainCache()
        DynamicBaseUrlResolver(
            storage = SharedPreferencesBaseUrlStorage(preferences),
            keys = BaseUrlCacheKeys(
                cachedUrl = LATEST_DOMAIN_URL_PREF,
                fetchedAt = LATEST_DOMAIN_FETCHED_AT_PREF,
                attemptedAt = LATEST_DOMAIN_ATTEMPTED_AT_PREF,
            ),
            fallbackBaseUrl = { "https://${currentBaseUrlHost.ifBlank { domainHost(domainNumber) }}" },
            isAllowedAutomaticUrl = { it.host.matches(domainHostRegex) },
            discoverBaseUrl = ::fetchLatestBaseUrl,
            redirectBaseUrl = ::resolveRedirectBaseUrl,
            onAutomaticUrlResolved = { resolvedBaseUrl ->
                val host = resolvedBaseUrl.toHttpUrl().host
                currentBaseUrlHost = host
                updateDomainNumberFromHost(host)
            },
        )
    }

    private fun migrateLegacyDomainCache() {
        if (preferences.getString(LATEST_DOMAIN_URL_PREF, null) != null) return
        preferences.getString(LEGACY_LATEST_DOMAIN_HOST_PREF, null)
            ?.takeIf { it.matches(domainHostRegex) }
            ?.let { preferences.edit().putString(LATEST_DOMAIN_URL_PREF, "https://$it").apply() }
    }

    private val json by injectLazy<Json>()

    private val catalog = BlacktoonCatalog(::loadDb)

    private fun activeBaseUrl(): String = getManualBaseUrl()
        ?: latestBaseUrlResolver.cachedBaseUrl()
        ?: "https://${currentBaseUrlHost.ifBlank { domainHost(domainNumber) }}"

    private fun loadDb(): List<SeriesItem> {
        val (body, pageUrl) = client.newCall(GET(baseUrl, headers)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("webtoon data request failed: ${response.code}")
            response.body.string() to response.request.url.toString()
        }
        val fetchScript: (String) -> String = { fetchDataScript(it, pageUrl) }
        return loadBlacktoonDataScripts(blacktoonDataScripts(body, pageUrl, fetchScript), json, fetchScript)
    }

    private fun dataHeaders(pageUrl: String): Headers = headers.newBuilder()
        .set("Referer", pageUrl)
        .set("Origin", pageUrl.toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'))
        .build()

    private fun fetchDataScript(url: String, pageUrl: String): String = client.newCall(GET(url, dataHeaders(pageUrl))).execute().use { response ->
        if (!response.isSuccessful) throw IOException("webtoon data script failed: ${response.code}")
        response.body.string()
    }

    private fun browse(page: Int, selection: BlacktoonSelection): Observable<MangasPage> = Observable.fromCallable {
        val (items, hasNextPage) = catalog.page(page, selection)
        MangasPage(items.map { it.toSManga(cdnUrl) }, hasNextPage)
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = browse(page, BlacktoonSelection(order = 1))

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = browse(page, BlacktoonSelection(order = 0))

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = browse(
        page,
        BlacktoonSelection.from(query, filters),
    )

    override fun getFilterList() = getFilters()

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/webtoon/${manga.url}.html#${manga.status}", headers)

    override fun getMangaUrl(manga: SManga): String = buildString {
        append(activeBaseUrl())
        append("/webtoon/")
        append(manga.url)
        append(".html")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val mangaId = response.request.url.pathSegments.last().removeSuffix(".html")

        val metadata = try {
            catalog.items().firstOrNull { it.id == mangaId }?.toSManga(cdnUrl)
        } catch (error: Exception) {
            error.rethrowIfBlacktoonCancelled()
            null
        }
        return (metadata ?: SManga.create()).apply {
            description = doc.select("p.mt-2").last()?.text()
            status = response.request.url.fragment?.toIntOrNull() ?: status
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/webtoon/${manga.url}.html", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val pageUrl = response.request.url.toString()
        val mangaId = response.request.url.pathSegments.last().removeSuffix(".html")
        return blacktoonChapters(response.body.string(), pageUrl, json) { fetchDataScript(it, pageUrl) }
            .map { it.toSChapter(mangaId) }
    }

    override fun getChapterUrl(chapter: SChapter): String = buildString {
        append(activeBaseUrl())
        append("/webtoons/")
        append(chapter.url)
        append(".html")
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/webtoons/${chapter.url}.html", headers)

    override fun pageListParse(response: Response): List<Page> {
        val pageUrl = response.request.url.toString()
        return blacktoonPages(response.body.string(), pageUrl, cdnUrl, { fetchDataScript(it, pageUrl) })
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, dataHeaders(page.url.ifBlank { activeBaseUrl() }))

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

    private fun getManualBaseUrl(): String? = preferences
        .getString(PREF_MANUAL_BASE_URL, null)
        ?.let(::normalizeManualBaseUrl)

    private fun normalizeManualBaseUrl(value: String): String? = normalizeBaseUrl(value)

    private fun baseUrlPreferenceSummary(): String = getManualBaseUrl()
        ?.let { "현재 수동 주소: $it" }
        ?: "현재 자동 주소: ${latestBaseUrlResolver.cachedBaseUrl() ?: "https://${domainHost(domainNumber)}"}\n" +
        "비워두면 공식 안내 사이트에서 최신 주소를 자동 확인합니다."

    @Volatile
    private var domainNumber = ""
        get() {
            val currentValue = field
            if (currentValue.isNotEmpty()) return currentValue

            val stored = preferences.getString(PREF_DOMAIN_NUMBER, DEFAULT_DOMAIN_NUMBER)!!
            val normalized = normalizeDomainNumber(stored)
            if (normalized != stored) {
                preferences.edit().putString(PREF_DOMAIN_NUMBER, normalized).apply()
            }

            field = normalized
            return normalized
        }
        private set

    private fun normalizeDomainNumber(value: String): String = value.trim().trimStart('0').ifEmpty { DEFAULT_DOMAIN_NUMBER }

    private fun saveDomainNumber(value: String, resetCachedHost: Boolean) {
        val normalized = normalizeDomainNumber(value)
        preferences.edit().putString(PREF_DOMAIN_NUMBER, normalized).apply()
        domainNumber = normalized
        if (resetCachedHost) {
            currentBaseUrlHost = ""
            latestBaseUrlResolver.clearCache()
        }
    }

    private fun updateDomainNumberFromHost(host: String) {
        val newDomainNumber = domainRegex.matchEntire(host)?.groupValues?.get(1) ?: return
        if (newDomainNumber != domainNumber) {
            saveDomainNumber(newDomainNumber, resetCachedHost = false)
        }
    }

    private fun fetchLatestBaseUrl(): String? = runCatching {
        domainLookupClient.newCall(
            GET(
                LATEST_DOMAIN_ENDPOINT,
                Headers.Builder()
                    .set("User-Agent", USER_AGENT)
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
                .firstOrNull { url -> isValidDiscoveredDomain(url) }
                ?.toString()
                ?.trimEnd('/')
        }
    }.getOrNull()

    private fun resolveRedirectBaseUrl(): String? = runCatching {
        noRedirectClient.newCall(GET("https://${domainHost(domainNumber)}", headers)).execute().use { response ->
            response.headers["location"]
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

    private fun domainHost(number: String) = "blacktoon$number.com"

    // unused
    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        private const val PREF_DOMAIN_NUMBER = "domain_number"
        private const val PREF_MANUAL_BASE_URL = "manual_base_url"
        private const val DEFAULT_DOMAIN_NUMBER = "421"
        private const val LATEST_DOMAIN_ENDPOINT = "https://blacktoonurl.net/"
        private const val LATEST_DOMAIN_URL_PREF = "latest_domain_url"
        private const val LEGACY_LATEST_DOMAIN_HOST_PREF = "latest_domain_host"
        private const val LATEST_DOMAIN_FETCHED_AT_PREF = "latest_domain_fetched_at"
        private const val LATEST_DOMAIN_ATTEMPTED_AT_PREF = "latest_domain_attempted_at"
        private const val DOMAIN_LOOKUP_TIMEOUT_SECONDS = 8L
        private val domainRegex = Regex("""blacktoon(\d+)\.com""")
        private val domainHostRegex = Regex("""^blacktoon\d+\.com$""")
    }
}
