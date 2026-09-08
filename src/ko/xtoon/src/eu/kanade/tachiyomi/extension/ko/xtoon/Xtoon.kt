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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import rx.schedulers.Schedulers
import java.io.IOException
import java.util.concurrent.TimeUnit

class Xtoon :
    HttpSource(),
    ConfigurableSource {
    override val name = "Xtoon"
    override val lang = "ko"
    override val supportsLatest = true
    private val preferences: SharedPreferences by getPreferencesLazy()

    // UI callers (including WebView URL getters) never trigger network discovery.
    override val baseUrl: String
        get() = manualBaseUrl() ?: latestBaseUrlResolver.cachedBaseUrl() ?: DEFAULT_URL

    private val lookupClient = network.client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).build()

    private val discovery by lazy { XtoonDomainDiscovery(lookupClient) }
    private val latestBaseUrlResolver by lazy {
        if (!preferences.getBoolean("newxtoon_discovery_v1", false)) {
            preferences.edit().putBoolean("newxtoon_discovery_v1", true)
                .remove("latest_base_url_fetched_at").remove("latest_base_url_attempted_at").apply()
        }
        DynamicBaseUrlResolver(
            storage = SharedPreferencesBaseUrlStorage(preferences),
            keys = BaseUrlCacheKeys("latest_base_url", "latest_base_url_fetched_at", "latest_base_url_attempted_at"),
            fallbackBaseUrl = { DEFAULT_URL },
            isAllowedAutomaticUrl = { isXtoonAutomaticHost(it.host) },
            discoverBaseUrl = {
                discovery.discover()?.also {
                    preferences.edit().putString("latest_base_url_source", it.source).apply()
                }?.baseUrl
            },
            redirectBaseUrl = ::redirectBaseUrl,
        )
    }

    override val client = network.cloudflareClient.newBuilder().addInterceptor { chain ->
        val original = chain.request()
        val manual = manualBaseUrl()?.toHttpUrl()
        val ownHost: (String) -> Boolean = { isXtoonAutomaticHost(it) || it == manual?.host }
        val request = if (ownHost(original.url.host)) {
            val target = manual?.toString() ?: latestBaseUrlResolver.resolve()
            val rewritten = original.rewriteBaseUrl(target, ownHost)
            rewritten.newBuilder().header("Origin", target.trimEnd('/'))
                .header("Referer", rewritten.header("Referer") ?: target.trimEnd('/') + "/").build()
        } else {
            original
        }
        chain.proceed(request)
    }.build()

    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int) = GET(browseUrl(page, popular = true), headers)
    override fun latestUpdatesRequest(page: Int) = GET(browseUrl(page), headers)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET(
                baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
                    .addQueryParameter("q", query.trim()).addQueryParameter("page", page.toString()).build(),
                headers,
            )
        }
        val url = browseUrl(page).toHttpUrl().newBuilder()
        filters.filterIsInstance<UrlFilter>().forEach { filter ->
            filter.value.takeIf(String::isNotEmpty)?.let { url.addQueryParameter(filter.parameter, it) }
        }
        return GET(url.build(), headers)
    }

    private fun browseUrl(page: Int, popular: Boolean = false): String = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment("comics").apply { if (popular) addQueryParameter("sort", "popular") }
        .addQueryParameter("page", page.toString()).build().toString()

    override fun popularMangaParse(response: Response): MangasPage {
        val page = NewXtoonParser.mangaPage(response.asJsoup())
        return MangasPage(page.mangas.map { it.toManga() }, page.hasNextPage)
    }
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)
    override fun mangaDetailsParse(response: Response) = NewXtoonParser.details(response.asJsoup()).toManga()

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.defer {
        super.fetchMangaDetails(resolvedManga(manga))
    }.subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.defer {
        super.fetchChapterList(resolvedManga(manga))
    }.subscribeOn(Schedulers.io())

    override fun chapterListParse(response: Response): List<SChapter> {
        val index = NewXtoonParser.chapterIndex(response.asJsoup())
        return NewXtoonParser.allChapters(index) { page ->
            val url = index.apiUrl.newBuilder().addQueryParameter("page", page.toString()).build()
            client.newCall(
                GET(
                    url,
                    headers.newBuilder().set("Accept", "application/json")
                        .set("Referer", response.request.url.toString()).build(),
                ),
            ).execute().use {
                if (!it.isSuccessful) throw IOException("회차 목록 요청 실패: HTTP ${it.code}")
                NewXtoonParser.chapterBatch(it.body.string(), index.apiUrl)
            }
        }.map { chapter ->
            SChapter.create().apply {
                url = chapter.url
                name = chapter.name
                date_upload = chapter.date_upload
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val path = chapter.url.toHttpUrlOrNull()?.encodedPath ?: chapter.url
        if (!NewXtoonParser.chapterPath.matches(path)) {
            throw IOException("이전 엑스툰 회차 주소입니다. 작품의 회차 목록을 새로고침한 뒤 다시 열어 주세요.")
        }
        return GET(baseUrl + path, headers.newBuilder().set("Referer", baseUrl + path).build())
    }

    override fun pageListParse(response: Response) = NewXtoonParser.pages(response.asJsoup())
    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl!!,
        headers.newBuilder()
            .set("Referer", page.url).set("Origin", page.url.toHttpUrl().let { "${it.scheme}://${it.host}" }).build(),
    )
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun XtoonManga.toManga(): SManga = SManga.create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = thumbnail_url
        it.author = author
        it.description = description
        it.genre = genre
        it.status = status
    }

    private val migrationLock = Any()

    private fun resolvedManga(manga: SManga): SManga {
        val path = manga.url.toHttpUrlOrNull()?.encodedPath ?: manga.url
        if (NewXtoonParser.comicPath.matches(path)) return manga
        if (!Regex("/comic/[0-9]+").matches(path)) throw IOException("지원하지 않는 작품 주소입니다. 소스 이전 기능으로 새 작품을 선택해 주세요.")
        return synchronized(migrationLock) {
            val key = "newxtoon_manga_$path"
            val cached = preferences.getString(key, null)?.takeIf(NewXtoonParser.comicPath::matches)
            val resolved = cached ?: resolveLegacyTitle(manga).also { preferences.edit().putString(key, it).apply() }
            SManga.create().apply {
                url = resolved
                title = manga.title
            }
        }
    }

    private fun resolveLegacyTitle(manga: SManga): String {
        if (manga.title.isBlank()) throw IOException("이전 작품 제목이 없습니다. 소스 이전 기능으로 새 작품을 선택해 주세요.")
        // IDs from the old service have no verified correspondence with the new database.
        // Never transplant a numeric ID. Search all returned pages and require one exact title.
        val matches = linkedMapOf<String, SManga>()
        for (page in 1..100) {
            val result = client.newCall(searchMangaRequest(page, manga.title, FilterList())).execute().use {
                if (!it.isSuccessful) throw IOException("이전 작품 검색 실패: HTTP ${it.code}")
                searchMangaParse(it)
            }
            result.mangas.filter { NewXtoonParser.normalizedTitle(it.title) == NewXtoonParser.normalizedTitle(manga.title) }
                .forEach { matches[it.url] = it }
            if (matches.size > 1) break
            if (result.hasNextPage) continue
            val candidate = matches.values.singleOrNull() ?: break
            val detail = client.newCall(mangaDetailsRequest(candidate)).execute().use {
                if (!it.isSuccessful) throw IOException("이전 작품 상세 확인 실패: HTTP ${it.code}")
                mangaDetailsParse(it)
            }
            if (NewXtoonParser.normalizedTitle(detail.title) != NewXtoonParser.normalizedTitle(manga.title)) break
            val oldAuthors = manga.author.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            val newAuthors = detail.author.orEmpty().split(',').map(String::trim)
            if (oldAuthors.isNotEmpty() && oldAuthors.none { it in newAuthors }) break
            return candidate.url
        }
        throw IOException("이전 작품을 하나로 확인할 수 없습니다. Mihon의 소스 이전 기능으로 새 작품을 선택해 주세요.")
    }

    override fun getMangaUrl(manga: SManga): String {
        val path = manga.url.toHttpUrlOrNull()?.encodedPath ?: manga.url
        val cached = preferences.getString("newxtoon_manga_$path", null)?.takeIf(NewXtoonParser.comicPath::matches)
        return baseUrl + (cached ?: path)
    }

    private fun manualBaseUrl(): String? = preferences.getString("manual_base_url", null)?.let(::normalizeBaseUrl)

    private fun redirectBaseUrl(): String? = runCatching {
        val current = preferences.getString("latest_base_url", null)
            ?.let { normalizeBaseUrl(it) { url -> isXtoonAutomaticHost(url.host) } } ?: DEFAULT_URL
        lookupClient.newCall(GET(current)).execute().use { response ->
            val url = when {
                response.code in 300..399 -> response.header("Location")?.let(response.request.url::resolve)
                response.isSuccessful -> response.request.url
                else -> null
            }
            url?.let { normalizeBaseUrl(it.toString()) { candidate -> isXtoonAutomaticHost(candidate.host) } }
                ?.also { preferences.edit().putString("latest_base_url_source", "콘텐츠 주소 리다이렉트").apply() }
        }
    }.getOrNull()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "manual_base_url"
            title = "접속 주소 직접 설정"
            summary = preferenceSummary()
            setDefaultValue("")
            dialogMessage = "https://로 시작하는 전체 기본 주소를 입력하세요. 비우면 공식 주소 안내와 채널에서 자동 확인합니다."
            setOnPreferenceChangeListener { preference, value ->
                val input = (value as? String).orEmpty().trim()
                val normalized = if (input.isEmpty()) "" else normalizeBaseUrl(input) ?: return@setOnPreferenceChangeListener false
                preferences.edit().putString(key, normalized).apply()
                (preference as EditTextPreference).text = normalized
                preference.summary = preferenceSummary()
                false
            }
        }.also(screen::addPreference)
    }

    private fun preferenceSummary() = manualBaseUrl()?.let { "현재 수동 주소: $it" }
        ?: "현재 자동 주소: $baseUrl\n탐색 출처: ${preferences.getString("latest_base_url_source", "공식 주소 안내 → 공식 채널 → 리다이렉트")}"

    override fun getFilterList() = FilterList(
        UrlFilter("분류", "category", arrayOf("일반만화" to "", "BL·GL" to "BL·GL", "성인만화" to "성인")),
        UrlFilter("정렬", "sort", arrayOf("최신" to "", "인기" to "popular")),
        UrlFilter("상태", "status", arrayOf("전체" to "", "연재중" to "연재중", "완결" to "완결")),
        UrlFilter("요일", "weekday", arrayOf("전체" to "", "월" to "월", "화" to "화", "수" to "수", "목" to "목", "금" to "금", "토" to "토", "일" to "일")),
        UrlFilter("장르", "genre", arrayOf("전체" to "", "로맨스" to "1", "드라마" to "4", "판타지" to "2", "액션" to "3", "개그/코미디" to "6", "로맨스판타지" to "2739", "무협/사극" to "2743")),
    )

    private class UrlFilter(name: String, val parameter: String, private val options: Array<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val value get() = options[state].second
    }

    private companion object {
        const val DEFAULT_URL = "https://newxtoon1.com"
    }
}
