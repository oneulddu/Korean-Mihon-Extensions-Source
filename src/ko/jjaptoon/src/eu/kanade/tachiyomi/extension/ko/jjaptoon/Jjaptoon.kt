package eu.kanade.tachiyomi.extension.ko.jjaptoon

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
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
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class Jjaptoon :
    HttpSource(),
    ConfigurableSource {

    override val name = "짭툰"

    private val defaultBaseUrl = "https://jjaptoon003.com"

    private val baseUrlPref = "overrideBaseUrl_v${AppInfo.getVersionName()}"

    override val baseUrl by lazy { getPrefBaseUrl().trimEnd('/') }

    override val lang = "ko"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")

    override fun popularMangaRequest(page: Int): Request = GET(comicsUrl(page), headers)

    override fun popularMangaParse(response: Response): MangasPage = mangaPageParse(response)

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = mangaPageParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/comics".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("search", query.trim())
            }
            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaPageParse(response)

    private fun comicsUrl(page: Int): String = "$baseUrl/comics".toHttpUrl().newBuilder().apply {
        if (page > 1) {
            addQueryParameter("page", page.toString())
        }
    }.build().toString()

    private fun mangaPageParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a[href^=/comics/]:has(img)")
            .distinctBy { it.attr("href") }
            .mapNotNull(::mangaFromElement)

        val hasNextPage = document.select("button")
            .any { it.attr("wire:click").startsWith("nextPage") && !it.hasAttr("disabled") }

        return MangasPage(mangas, hasNextPage)
    }

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
                    date_upload = dateFormat.tryParse(element.selectFirst("p + p")?.text().orEmpty())
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
            .mapNotNull { imageSrcRegex.find(it.attr(":src"))?.groupValues?.get(1) }
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
        EditTextPreference(screen.context).apply {
            key = baseUrlPref
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"
        }.also(screen::addPreference)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(baseUrlPref, defaultBaseUrl)!!

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
        private const val BASE_URL_PREF_SUMMARY = "Override default domain with a different one"

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }

        private val imageSrcRegex = """loaded\s*\?\s*'([^']+)'""".toRegex()

        private val ignoredBadges = setOf("완결", "연재", "월", "화", "수", "목", "금", "토", "일")
    }
}
