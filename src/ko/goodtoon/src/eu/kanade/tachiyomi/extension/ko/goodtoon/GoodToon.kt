package eu.kanade.tachiyomi.extension.ko.goodtoon

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.util.concurrent.TimeUnit

class GoodToon :
    HttpSource(),
    ConfigurableSource {
    override val name = "굿툰"
    override val lang = "ko"
    override val supportsLatest = true
    private val preferences: SharedPreferences by getPreferencesLazy()
    override val baseUrl: String
        get() = manualBaseUrl() ?: resolver.cachedBaseUrl() ?: DEFAULT_URL

    private val lookupClient = network.client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).build()

    private val resolver by lazy {
        DynamicBaseUrlResolver(
            storage = SharedPreferencesBaseUrlStorage(preferences),
            keys = BaseUrlCacheKeys("automatic_url", "automatic_fetched_at", "automatic_attempted_at"),
            fallbackBaseUrl = { DEFAULT_URL },
            isAllowedAutomaticUrl = { isGoodToonHost(it.host) },
            discoverBaseUrl = {
                lookupClient.newCall(GET(GOODTOON_CHANNEL)).execute().use {
                    if (it.isSuccessful) {
                        parseGoodToonChannel(it.body.string())?.also {
                            preferences.edit().putString("automatic_source", "공식 텔레그램 주소 안내").apply()
                        }
                    } else {
                        null
                    }
                }
            },
            redirectBaseUrl = {
                val current = preferences.getString("automatic_url", null)
                    ?.let { normalizeBaseUrl(it) { isGoodToonHost(it.host) } } ?: DEFAULT_URL
                lookupClient.newCall(GET(current)).execute().use { response ->
                    val url = when {
                        response.code in 300..399 -> response.header("Location")?.let(response.request.url::resolve)
                        response.isSuccessful -> response.request.url
                        else -> null
                    }
                    url?.let { normalizeBaseUrl(it.toString()) { isGoodToonHost(it.host) } }?.also {
                        preferences.edit().putString("automatic_source", "콘텐츠 주소 확인·리다이렉트").apply()
                    }
                }
            },
        )
    }

    override val client = network.cloudflareClient.newBuilder().addInterceptor { chain ->
        val request = chain.request()
        val manual = manualBaseUrl()
        val ownHost: (String) -> Boolean = { isGoodToonHost(it) || it == manual?.toHttpUrl()?.host }
        val isImage = isGoodToonImageHost(request.url.host)
        if (!ownHost(request.url.host) && !isImage) return@addInterceptor chain.proceed(request)
        val automatic = manual ?: resolver.resolve()
        // A setting changed while discovery was in flight still takes priority.
        val target = manualBaseUrl() ?: automatic
        chain.proceed(if (isImage) request.rewriteGoodToonImageHeaders(target) else request.rewriteGoodToonOrigin(target))
    }.build()

    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

    // The site exposes update order only; the default catalogue is also used for Popular.
    override fun popularMangaRequest(page: Int) = searchMangaRequest(page, "", FilterList())
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = GET(
        baseUrl.toHttpUrl().newBuilder().apply {
            filters.filterIsInstance<StatusFilter>().firstOrNull()?.value?.takeIf(String::isNotEmpty)?.let { addPathSegment(it) }
            addPathSegment("")
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            filters.filterIsInstance<UrlFilter>().forEach { filter ->
                filter.value.takeIf(String::isNotEmpty)?.let { addQueryParameter(filter.parameter, it) }
            }
            addQueryParameter("pg", page.toString())
        }.build(),
        headers,
    )

    override fun popularMangaParse(response: Response): MangasPage = GoodToonParser.catalogue(response.asJsoup()).let {
        MangasPage(it.mangas.map { manga -> manga.toManga() }, it.hasNext)
    }
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)
    override fun mangaDetailsParse(response: Response) = GoodToonParser.details(response.asJsoup()).toManga()

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga) = GET(
        getMangaUrl(manga),
        // WordPress omits author metadata entirely from its mobile HTML response.
        headers.newBuilder().set(
            "User-Agent",
            headers["User-Agent"].orEmpty()
                .replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)")
                .replace(" Mobile", ""),
        ).build(),
    )

    // Madara's read-more button only hides rows. This POST returns the entire list,
    // including mixed numeric and chapter-N slugs; never derive slugs from titles.
    override fun chapterListRequest(manga: SManga) = POST(
        getMangaUrl(manga).trimEnd('/') + "/ajax/chapters",
        headers.newBuilder().set("Referer", getMangaUrl(manga)).set("X-Requested-With", "XMLHttpRequest").build(),
    )
    override fun chapterListParse(response: Response) = GoodToonParser.chapters(response.asJsoup()).map {
        SChapter.create().apply {
            url = it.url
            name = it.name
            date_upload = it.date
            chapter_number = it.number
        }
    }
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return GoodToonParser.images(document).mapIndexed { index, image -> Page(index, document.location(), image) }
    }
    override fun imageRequest(page: Page) = GET(
        page.imageUrl!!,
        headers.newBuilder().set("Referer", page.url)
            .set("Origin", page.url.toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')).build(),
    )
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun GoodToonManga.toManga() = SManga.create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = thumbnail
        it.author = author
        it.description = description
        it.genre = genre
        it.status = status
    }
    private fun manualBaseUrl() = preferences.getString("manual_base_url", null)?.let(::normalizeBaseUrl)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "manual_base_url"
            title = "접속 주소 직접 설정"
            setDefaultValue("")
            summary = preferenceSummary()
            dialogMessage = "https://로 시작하는 전체 기본 주소를 입력하세요. 비우면 공식 주소 채널에서 자동 확인합니다."
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
        ?: "현재 자동 주소: $baseUrl\n탐색 출처: ${preferences.getString("automatic_source", "공식 텔레그램 주소 안내 → 리다이렉트 → 기본 주소")}"

    override fun getFilterList() = FilterList(
        Filter.Header("기본 목록은 최근 업데이트 순입니다."),
        StatusFilter(),
        UrlFilter("분류", "mcat", arrayOf("전체" to "", "일반 웹툰" to "webtoon", "BL·GL" to "bl-gl", "성인 웹툰" to "adult")),
        UrlFilter("요일", "mday", arrayOf("전체" to "", "월" to "mon", "화" to "tue", "수" to "wed", "목" to "thu", "금" to "fri", "토" to "sat", "일" to "sun", "열흘" to "etc")),
        UrlFilter("장르", "genre", arrayOf("전체" to "", "드라마" to "drama", "판타지" to "fantasy", "액션" to "action", "무협" to "martial-arts", "로맨스" to "romance", "개그" to "gag", "스포츠" to "sports", "학원" to "school", "일상" to "slice-of-life", "공포" to "horror", "미스터리" to "mystery")),
        UrlFilter("플랫폼", "plat", arrayOf("전체" to "", "네이버" to "naver", "다음" to "daum", "카카오" to "kakao", "레진" to "rejin", "탑툰" to "toptoon", "투믹스" to "tomics", "리디" to "ridi", "봄툰" to "bom", "코미코" to "comico", "기타" to "etc")),
    )
    private class StatusFilter : Filter.Select<String>("연재 상태", arrayOf("전체", "연재", "완결")) {
        val value get() = arrayOf("", "ongoing", "end")[state]
    }
    private class UrlFilter(name: String, val parameter: String, private val options: Array<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val value get() = options[state].second
    }
    companion object {
        private const val DEFAULT_URL = "https://www.goodtoon004.com"
    }
}
