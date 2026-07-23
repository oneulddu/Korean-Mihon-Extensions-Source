package eu.kanade.tachiyomi.extension.ko.toonkor

import android.content.SharedPreferences
import android.util.Base64
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.normalizeBaseUrl
import keiyoushi.utils.tryParse
import okhttp3.Request
import okhttp3.Response
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale

class Toonkor :
    HttpSource(),
    ConfigurableSource {

    override val name = "Toonkor"

    private val defaultBaseUrl = "https://tkor137.com"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val lang = "ko"

    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val pageListRegex = Regex("""src="([^"]*)"""")

    private val preferences: SharedPreferences by getPreferencesLazy()

    // Popular

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl$WEBTOONS_PATH$ALL_STATUS_PATH$SORT_POPULAR", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.section-item-inner").map { element ->
            SManga.create().apply {
                element.select("div.section-item-title a").let {
                    title = it.select("h3").text()
                    setUrlWithoutDomain(it.attr("abs:href"))
                }
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }

        return MangasPage(mangas, false)
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl$WEBTOONS_PATH$ALL_STATUS_PATH$SORT_LATEST", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val type = filterList.firstInstanceOrNull<TypeFilter>()
        val status = filterList.firstInstanceOrNull<StatusFilter>()
        val sort = filterList.firstInstanceOrNull<SortFilter>()

        val requestPath = when {
            query.isNotBlank() -> "/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=$query"
            else -> "${type?.toUriPart() ?: ""}${status?.toUriPart() ?: ""}${sort?.toUriPart() ?: ""}"
        }

        return GET(baseUrl + requestPath, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            with(document.select("table.bt_view1")) {
                title = select("td.bt_title").text()
                author = select("td.bt_label span.bt_data").text()
                description = select("td.bt_over").text()
                thumbnail_url = select("td.bt_thumb img").firstOrNull()?.attr("abs:src")
            }
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("table.web_list tr:has(td.content__title)").map { element ->
            SChapter.create().apply {
                element.select("td.content__title").let {
                    url = it.attr("data-role")
                    name = it.text()
                }
                date_upload = dateFormat.tryParse(element.select("td.episode__index").text())
            }
        }
    }

    // Pages

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val encoded = document.select("script:containsData(toon_img)").firstOrNull()?.data()
            ?.substringAfter("'")?.substringBefore("'") ?: throw Exception("toon_img script not found")

        val decoded = Base64.decode(encoded, Base64.DEFAULT).toString(Charset.defaultCharset())

        return pageListRegex.findAll(decoded).mapIndexed { i, matchResult ->
            val imageUrl = matchResult.destructured.component1().let { if (it.startsWith("http")) it else baseUrl + it }
            Page(i, imageUrl = imageUrl)
        }.toList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Note: can't combine with text search!"),
        Filter.Separator(),
        TypeFilter(),
        StatusFilter(),
        SortFilter(),
    )

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        migrateLegacyBaseUrl()
        EditTextPreference(screen.context).apply {
            key = PREF_MANUAL_BASE_URL
            title = BASE_URL_PREF_TITLE
            summary = baseUrlPreferenceSummary()
            setDefaultValue("")
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "비워두면 기본 주소 $defaultBaseUrl 을 사용합니다."
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

    private fun getPrefBaseUrl(): String {
        migrateLegacyBaseUrl()
        return preferences.getString(PREF_MANUAL_BASE_URL, null)
            ?.let(::normalizeBaseUrl)
            ?: defaultBaseUrl
    }

    private fun migrateLegacyBaseUrl() {
        if (preferences.contains(PREF_MIGRATED_BASE_URL)) return
        val legacyManualUrl = preferences.all.asSequence()
            .filter { (key, _) -> key.startsWith(LEGACY_BASE_URL_PREF_PREFIX) }
            .mapNotNull { (_, value) -> (value as? String)?.let(::normalizeBaseUrl) }
            .firstOrNull { it !in oldDefaultBaseUrls && it != defaultBaseUrl }
        preferences.edit().apply {
            legacyManualUrl?.let { putString(PREF_MANUAL_BASE_URL, it) }
            putBoolean(PREF_MIGRATED_BASE_URL, true)
        }.apply()
    }

    private fun baseUrlPreferenceSummary(): String = preferences.getString(PREF_MANUAL_BASE_URL, null)
        ?.let(::normalizeBaseUrl)
        ?.let { "현재 수동 주소: $it" }
        ?: "현재 기본 주소: $defaultBaseUrl"

    companion object {
        private val oldDefaultBaseUrls = setOf(
            "https://tkor114.com",
            "https://tkor136.com",
        )

        private const val PREF_MANUAL_BASE_URL = "manual_base_url"
        private const val PREF_MIGRATED_BASE_URL = "manual_base_url_migrated_v1"
        private const val LEGACY_BASE_URL_PREF_PREFIX = "overrideBaseUrl_v"
        private const val BASE_URL_PREF_TITLE = "Override BaseUrl"
    }
}
