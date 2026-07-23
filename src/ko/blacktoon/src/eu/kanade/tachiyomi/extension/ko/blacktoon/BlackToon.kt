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
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.decodeFromString
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
import kotlin.random.Random

class BlackToon :
    HttpSource(),
    ConfigurableSource {

    override val name = "블랙툰"

    override val lang = "ko"

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val domainRefreshLock = Any()
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
            else -> "https://${resolveBaseUrlHost()}".toHttpUrl()
        }

        if (resolvedBaseUrl != null) {
            currentBaseUrlHost = resolvedBaseUrl.host
        }
        val requestHeaderBaseUrl = resolvedBaseUrl ?: if (originalRequest.url.host == cdnHost) {
            getManualBaseUrl()?.toHttpUrl()
                ?: "https://${currentBaseUrlHost.ifBlank { getCachedLatestDomainHost() ?: domainHost(domainNumber) }}".toHttpUrl()
        } else {
            null
        }

        val request = originalRequest.newBuilder().apply {
            if (resolvedBaseUrl != null) {
                url(
                    originalRequest.url.newBuilder()
                        .scheme(resolvedBaseUrl.scheme)
                        .host(resolvedBaseUrl.host)
                        .port(resolvedBaseUrl.port)
                        .build(),
                )
            }
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

    private val json by injectLazy<Json>()

    private val db by lazy { synchronized(dbCacheLock) { cachedDb ?: loadDb().also { cachedDb = it } } }

    private fun loadDb(): List<SeriesItem> {
        val response = client.newCall(GET(baseUrl, headers)).execute()
        val body = response.body.string()
        val dataScriptUrls = dataScriptRegex.findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
            .ifEmpty { throw IOException("unable to find webtoon data scripts") }

        return dataScriptUrls.flatMap { scriptUrl ->
            var listIdx: Int
            client.newCall(GET("$baseUrl$scriptUrl", headers))
                .execute().body.string()
                .also {
                    listIdx = it.substringBefore(" = ")
                        .substringAfter("data")
                        .toInt()
                }
                .substringAfter(" = ")
                .removeSuffix(";")
                .let { json.decodeFromString<List<SeriesItem>>(it) }
                .onEach { it.listIndex = listIdx }
        }
    }

    private fun List<SeriesItem>.getPageChunk(page: Int): MangasPage = MangasPage(
        mangas = drop((page - 1) * 24).take(24)
            .map { it.toSManga(cdnUrl) },
        hasNextPage = page * 24 < size,
    )

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.just(
        db.sortedByDescending { it.hot }.getPageChunk(page),
    )

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.just(
        db.sortedByDescending { it.updatedAt }.getPageChunk(page),
    )

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        var list = db

        if (query.isNotBlank()) {
            val stdQuery = query.trim()
            list = list.filter {
                it.name.contains(stdQuery, true) ||
                    it.author.contains(stdQuery, true)
            }
        }

        filters.filterIsInstance<ListFilter>().forEach {
            list = it.applyFilter(list)
        }

        return Observable.just(
            list.getPageChunk(page),
        )
    }

    override fun getFilterList() = getFilters()

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/webtoon/${manga.url}.html#${manga.status}", headers)

    override fun getMangaUrl(manga: SManga): String = buildString {
        if (currentBaseUrlHost.isBlank()) {
            append(baseUrl)
        } else {
            append("https://")
            append(currentBaseUrlHost)
        }
        append("/webtoon/")
        append(manga.url)
        append(".html")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val mangaId = response.request.url.pathSegments.last().removeSuffix(".html")

        return (runCatching { db.firstOrNull { it.id == mangaId }?.toSManga(cdnUrl) }.getOrNull() ?: SManga.create()).apply {
            description = doc.select("p.mt-2").last()?.text()
            status = response.request.url.fragment?.toIntOrNull() ?: status
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = "$baseUrl/data/toonlist/${manga.url}.js?v=${"%.17f".format(Random.nextDouble())}"

        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaId = response.request.url.pathSegments.last().removeSuffix(".js")

        val data = response.body.string()
            .substringAfter(" = ")
            .removeSuffix(";")
            .let { json.decodeFromString<List<Chapter>>(it) }

        return data.map { it.toSChapter(mangaId) }.reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = buildString {
        if (currentBaseUrlHost.isBlank()) {
            append(baseUrl)
        } else {
            append("https://")
            append(currentBaseUrlHost)
        }
        append("/webtoons/")
        append(chapter.url)
        append(".html")
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/webtoons/${chapter.url}.html", headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        return document.select("#toon_content_imgs img").mapIndexed { index, element ->
            val imageUrl = element.attr("data-original")
                .ifBlank { element.attr("o_src") }
                .ifBlank { element.attr("src") }
                .toImageUrl()

            Page(index, imageUrl = imageUrl)
        }
    }

    private fun String.toImageUrl(): String = when {
        startsWith("http") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> "https://$currentBaseUrlHost$this"
        else -> cdnUrl + this
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

    private fun normalizeManualBaseUrl(value: String): String? {
        val url = value.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        if (
            url.scheme != "https" ||
            url.port != 443 ||
            url.encodedPath != "/" ||
            url.query != null ||
            url.username.isNotEmpty() ||
            url.password.isNotEmpty()
        ) {
            return null
        }
        return url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
    }

    private fun baseUrlPreferenceSummary(): String = getManualBaseUrl()
        ?.let { "현재 수동 주소: $it" }
        ?: "현재 자동 주소: https://${getCachedLatestDomainHost() ?: domainHost(domainNumber)}\n" +
        "비워두면 공식 안내 사이트에서 최신 주소를 자동 확인합니다."

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
            preferences.edit()
                .remove(LATEST_DOMAIN_HOST_PREF)
                .remove(LATEST_DOMAIN_FETCHED_AT_PREF)
                .remove(LATEST_DOMAIN_ATTEMPTED_AT_PREF)
                .apply()
        }
    }

    private fun updateDomainNumberFromHost(host: String) {
        val newDomainNumber = domainRegex.matchEntire(host)?.groupValues?.get(1) ?: return
        if (newDomainNumber != domainNumber) {
            saveDomainNumber(newDomainNumber, resetCachedHost = false)
        }
    }

    private fun resolveBaseUrlHost(): String {
        val now = System.currentTimeMillis()
        val cachedHost = getCachedLatestDomainHost()
        val fetchedAt = preferences.getLong(LATEST_DOMAIN_FETCHED_AT_PREF, 0L)
        if (cachedHost != null && now - fetchedAt < DOMAIN_CACHE_DURATION_MS) {
            currentBaseUrlHost = cachedHost
            return cachedHost
        }

        val attemptedAt = preferences.getLong(LATEST_DOMAIN_ATTEMPTED_AT_PREF, 0L)
        if (now - attemptedAt < DOMAIN_RETRY_DELAY_MS) {
            return cachedHost ?: currentBaseUrlHost.ifBlank {
                resolveRedirectDomainHost() ?: domainHost(domainNumber)
            }
        }

        return synchronized(domainRefreshLock) {
            val synchronizedNow = System.currentTimeMillis()
            val synchronizedCachedHost = getCachedLatestDomainHost()
            val synchronizedFetchedAt = preferences.getLong(LATEST_DOMAIN_FETCHED_AT_PREF, 0L)
            if (
                synchronizedCachedHost != null &&
                synchronizedNow - synchronizedFetchedAt < DOMAIN_CACHE_DURATION_MS
            ) {
                currentBaseUrlHost = synchronizedCachedHost
                return@synchronized synchronizedCachedHost
            }

            val synchronizedAttemptedAt = preferences.getLong(LATEST_DOMAIN_ATTEMPTED_AT_PREF, 0L)
            if (synchronizedNow - synchronizedAttemptedAt < DOMAIN_RETRY_DELAY_MS) {
                return@synchronized synchronizedCachedHost
                    ?: currentBaseUrlHost.ifBlank {
                        resolveRedirectDomainHost() ?: domainHost(domainNumber)
                    }
            }

            preferences.edit()
                .putLong(LATEST_DOMAIN_ATTEMPTED_AT_PREF, synchronizedNow)
                .apply()

            val discoveredHost = fetchLatestDomainHost() ?: resolveRedirectDomainHost()
            val resolvedHost = discoveredHost
                ?: synchronizedCachedHost
                ?: currentBaseUrlHost.ifBlank { domainHost(domainNumber) }

            currentBaseUrlHost = resolvedHost
            updateDomainNumberFromHost(resolvedHost)

            if (discoveredHost != null) {
                preferences.edit()
                    .putString(LATEST_DOMAIN_HOST_PREF, discoveredHost)
                    .putLong(LATEST_DOMAIN_FETCHED_AT_PREF, synchronizedNow)
                    .apply()
            }

            resolvedHost
        }
    }

    private fun fetchLatestDomainHost(): String? = runCatching {
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
                ?.host
        }
    }.getOrNull()

    private fun getCachedLatestDomainHost(): String? = preferences
        .getString(LATEST_DOMAIN_HOST_PREF, null)
        ?.takeIf { it.matches(domainHostRegex) }

    private fun resolveRedirectDomainHost(): String? = runCatching {
        noRedirectClient.newCall(GET(baseUrl, headers)).execute().use { response ->
            response.headers["location"]
                ?.toHttpUrlOrNull()
                ?.takeIf(::isValidDiscoveredDomain)
                ?.host
                ?: response.request.url.host.takeIf { it.matches(domainHostRegex) }
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
        private const val DEFAULT_DOMAIN_NUMBER = "416"
        private const val LATEST_DOMAIN_ENDPOINT = "https://blacktoonurl.net/"
        private const val LATEST_DOMAIN_HOST_PREF = "latest_domain_host"
        private const val LATEST_DOMAIN_FETCHED_AT_PREF = "latest_domain_fetched_at"
        private const val LATEST_DOMAIN_ATTEMPTED_AT_PREF = "latest_domain_attempted_at"
        private const val DOMAIN_LOOKUP_TIMEOUT_SECONDS = 8L
        private const val DOMAIN_CACHE_DURATION_MS = 12 * 60 * 60 * 1000L
        private const val DOMAIN_RETRY_DELAY_MS = 15 * 60 * 1000L
        private val dbCacheLock = Any()
        private var cachedDb: List<SeriesItem>? = null
        private val dataScriptRegex = Regex("""loadScript\((?:inc_url\+)?['"](/data/webtoon/webtoon_\d+_\d+\.js)""")
        private val domainRegex = Regex("""blacktoon(\d+)\.com""")
        private val domainHostRegex = Regex("""^blacktoon\d+\.com$""")
    }
}
