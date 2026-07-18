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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import rx.Observable
import uy.kohesive.injekt.injectLazy
import kotlin.random.Random

class BlackToon :
    HttpSource(),
    ConfigurableSource {

    override val name = "블랙툰"

    override val lang = "ko"

    private val preferences: SharedPreferences by getPreferencesLazy()

    private var currentBaseUrlHost = ""
    override val baseUrl: String
        get() = "https://blacktoon$domainNumber.com"

    private val cdnUrl = "https://aa3cc9.speedwebgo.com/"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")

    override val client = network.client.newBuilder().addInterceptor { chain ->
        val originalRequest = chain.request()
        val configuredBaseUrlHost = baseUrl.toHttpUrlOrNull()?.host

        if (currentBaseUrlHost.isBlank()) {
            noRedirectClient.newCall(GET(baseUrl, headers)).execute().use {
                currentBaseUrlHost = it.headers["location"]?.toHttpUrlOrNull()?.host
                    ?: it.request.url.host
                updateDomainNumberFromHost(currentBaseUrlHost)
            }
        }

        val request = originalRequest.newBuilder().apply {
            if (originalRequest.url.host == configuredBaseUrlHost) {
                url(
                    originalRequest.url.newBuilder()
                        .host(currentBaseUrlHost)
                        .build(),
                )
            }
            header("Referer", "https://$currentBaseUrlHost/")
            header("Origin", "https://$currentBaseUrlHost")
        }.build()

        return@addInterceptor chain.proceed(request)
    }.build()

    private val noRedirectClient = network.client.newBuilder()
        .followRedirects(false)
        .build()

    private val json by injectLazy<Json>()

    private val db by lazy {
        val response = client.newCall(GET(baseUrl, headers)).execute()
        val body = response.body.string()
        val dataScriptUrls = dataScriptRegex.findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
            .ifEmpty { throw IOException("unable to find webtoon data scripts") }

        dataScriptUrls.flatMap { scriptUrl ->
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
            key = PREF_DOMAIN_NUMBER
            title = "도메인 번호 (blacktoon#.com)"
            summary = "현재 도메인 번호: $domainNumber\n숫자만 입력하세요 (예: 415)"
            setDefaultValue(DEFAULT_DOMAIN_NUMBER)
            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim()
                if (value.isEmpty() || value.toIntOrNull() == null) {
                    false
                } else {
                    saveDomainNumber(value, resetCachedHost = true)
                    false
                }
            }
        }.also(screen::addPreference)
    }

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
        }
    }

    private fun updateDomainNumberFromHost(host: String) {
        val newDomainNumber = domainRegex.matchEntire(host)?.groupValues?.get(1) ?: return
        if (newDomainNumber != domainNumber) {
            saveDomainNumber(newDomainNumber, resetCachedHost = false)
        }
    }

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
        private const val DEFAULT_DOMAIN_NUMBER = "416"
        private val dataScriptRegex = Regex("""loadScript\((?:inc_url\+)?['"](/data/webtoon/webtoon_\d+_\d+\.js)""")
        private val domainRegex = Regex("""blacktoon(\d+)\.com""")
    }
}
