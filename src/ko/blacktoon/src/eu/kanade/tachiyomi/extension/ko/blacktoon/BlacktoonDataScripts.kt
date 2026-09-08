package eu.kanade.tachiyomi.extension.ko.blacktoon

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

internal data class BlacktoonDataScript(val index: Int, val urls: List<String>)

internal fun blacktoonDataScripts(
    html: String,
    pageUrl: String,
    fetchConfig: (String) -> String,
    timeKey: String = blacktoonTimeKey(),
): List<BlacktoonDataScript> {
    val candidates = blacktoonPageScripts(html, pageUrl, fetchConfig, timeKey).urls
        .mapNotNull { url ->
            val match = catalogPath.matchEntire(url.encodedPath) ?: return@mapNotNull null
            (match.groups[1] ?: match.groups[2])!!.value.toInt() to url
        }.groupBy({ it.first }, { it.second })
    return listOf(1, 0).map { index ->
        val urls = candidates[index].orEmpty()
            .sortedBy { it.encodedPath.startsWith("/data/webtoon/") }
            .map(HttpUrl::toString)
            .distinct()
        if (urls.isEmpty()) throw IOException("unable to find webtoon data$index scripts")
        BlacktoonDataScript(index, urls)
    }
}

internal data class BlacktoonPageScripts(val document: Document, val variables: Map<String, String>, val urls: List<HttpUrl>)

/** Reads only data URL expressions and configuration literals; never evaluates site JavaScript. */
internal fun blacktoonPageScripts(
    html: String,
    pageUrl: String,
    fetchConfig: (String) -> String,
    timeKey: String = blacktoonTimeKey(),
    randomValue: String = Random.nextDouble().toString(),
): BlacktoonPageScripts {
    val base = pageUrl.toHttpUrl()
    val document = Jsoup.parse(html, pageUrl)
    val variables = mutableMapOf<String, String>()
    val configs = mutableSetOf<String>()
    val candidates = mutableListOf<HttpUrl>()

    fun addCandidate(value: String) {
        val url = base.resolve(value)?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.fragment == null } ?: return
        if (catalogPath.matches(url.encodedPath) || chapterScriptPath.matches(url.encodedPath)) candidates.add(url)
    }

    fun readConfig(value: String) {
        val url = base.resolve(value) ?: return
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
            url.encodedPath != "/data/config.js" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null
        ) {
            return
        }
        if (!configs.add(url.toString())) return
        try {
            readCatalogVariables(fetchConfig(url.toString()), variables)
        } catch (error: Exception) {
            error.rethrowIfBlacktoonCancelled()
            // An already resolved script[src] or inline fallback can still supply the catalog.
        }
    }

    document.select("script").forEach { script ->
        if (script.hasAttr("src")) {
            readConfig(script.attr("src"))
            addCandidate(script.attr("src"))
        } else {
            val code = withoutJsComments(script.data())
            readCatalogVariables(code, variables)
            // Raw HTML writes the config tag dynamically; reproduce only its cache-busting key.
            configSource.findAll(code).forEach { match ->
                readConfig(match.groupValues[1] + if (match.groupValues[2].isNotEmpty()) timeKey else "")
            }
            loadScriptCall.findAll(code).forEach { match ->
                catalogExpression(match.groupValues[1], variables, randomValue)?.let(::addCandidate)
            }
        }
    }
    return BlacktoonPageScripts(document, variables.toMap(), candidates.distinct())
}

private fun blacktoonTimeKey(): String = SimpleDateFormat("mmHHddMMyy", Locale.US).format(Date())

internal fun loadBlacktoonDataScripts(
    scripts: List<BlacktoonDataScript>,
    json: Json,
    fetch: (String) -> String,
): List<SeriesItem> = scripts.flatMap { script ->
    var failure: Exception? = null
    var items: List<SeriesItem>? = null
    for (url in script.urls) {
        try {
            val match = catalogPayload.matchEntire(fetch(url)) ?: throw IOException("invalid webtoon data script")
            if (match.groupValues[1].toInt() != script.index) throw IOException("unexpected webtoon data index")
            items = json.decodeFromString<List<SeriesItem>>(match.groupValues[2])
                .also { if (it.isEmpty()) throw IOException("webtoon data${script.index} is empty") }
                .onEach { it.listIndex = script.index }
            break
        } catch (error: Exception) {
            error.rethrowIfBlacktoonCancelled()
            failure = error
        }
    }
    items ?: throw IOException("unable to load webtoon data${script.index}", failure)
}

private fun readCatalogVariables(code: String, variables: MutableMap<String, String>) {
    catalogVariable.findAll(withoutJsComments(code)).forEach { match ->
        val name = match.groupValues[1]
        val value = catalogExpression(match.groupValues[2], variables)
        if (value == null) variables.remove(name) else variables[name] = value
    }
}

private fun catalogExpression(expression: String, variables: Map<String, String>, randomValue: String? = null): String? {
    val result = StringBuilder()
    var offset = 0
    while (offset < expression.length) {
        val token = expressionToken.find(expression, offset)?.takeIf { it.range.first == offset } ?: return null
        val value = token.groups[1]?.value ?: token.groups[2]?.value ?: token.groups[4]?.value
            ?: if (token.groups[5] != null) randomValue else variables[token.groupValues[3]]
        result.append(value ?: return null)
        offset = token.range.last + 1
        if (offset == expression.length) return result.toString()
        if (expression[offset] != '+') return null
        offset++
    }
    return null
}

private fun withoutJsComments(code: String): String = jsComments.replace(code) {
    if (it.value.startsWith("//") || it.value.startsWith("/*")) "\n" else it.value
}

private val catalogPath = Regex("""/(?:webtoon_([01])|data/webtoon/webtoon_([01])_\d+)\.js""")
private val chapterScriptPath = Regex("""/data/toonlist/\d+\.js""")
private const val LITERAL_VARIABLES = "inc_url2|inc_url|poster_js|img_domain[2-8]?|img_per[3-8]|toonlistid|uptime"
private val catalogVariable = Regex("""(?:^|[;\r\n])\s*(?:(?:var|let|const)\s+)?($LITERAL_VARIABLES)\s*=\s*([^;\r\n]+)""")
private val expressionToken = Regex("""\s*(?:"([^"\\]*)"|'([^'\\]*)'|($LITERAL_VARIABLES)|(\d+(?:\.\d+)?)|(Math\.random\(\)))\s*""")
private val loadScriptCall = Regex("""\b(?:loadScript|loadjs)\s*\(((?:"[^"\\]*"|'[^'\\]*'|Math\.random\(\)|[^"'();\r\n])+)\)""")
private val configSource = Regex("""\bsrc=['"]([^'"\s]*?/data/config\.js[^'"\s]*)['"](\s*\+\s*timeKey\b)?""")
private val catalogPayload = Regex("""\s*(?:(?:var|let|const)\s+)?data([01])\s*=\s*(\[.*])\s*;?\s*""", RegexOption.DOT_MATCHES_ALL)
private val jsComments = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|//[^\r\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
