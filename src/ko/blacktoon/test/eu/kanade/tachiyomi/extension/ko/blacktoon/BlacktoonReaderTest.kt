package eu.kanade.tachiyomi.extension.ko.blacktoon

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class BlacktoonReaderTest {
    private val detailUrl = "https://blacktoon421.com/webtoon/279.html"
    private val viewerUrl = "https://blacktoon421.com/webtoons/279/1194275.html"
    private val json = Json { ignoreUnknownKeys = true }
    private val header = """
        <script>var inc_url = ''; var poster_js = '2026';</script>
        <script>document.write("<script src='/data/config.js?v=A"+timeKey+"'></s"+"cript>");</script>
    """.trimIndent()
    private val config = """
        inc_url = 'https://jsc.speedwebgo.com';
        var img_domain2 = 'https://retry.example/';
        var img_domain3 = 'https://cdn3.example/';
        var img_domain4 = 'https://cdn4.example/';
        var img_domain5 = 'https://cdn5.example/';
        var img_domain6 = 'https://cdn6.example/';
        var img_domain7 = 'https://cdn7.example/';
        var img_domain8 = 'https://fresh.example/';
        var img_per3 = '20';
        var img_per4 = '20';
        var img_per5 = '20';
        var img_per6 = '20';
        var img_per7 = '20';
        var img_per8 = '5';
    """.trimIndent()
    private val detail = header + """
        <script>loadjs(inc_url+"/data/toonlist/279.js?v="+Math.random());</script>
        <script>loadjs(inc_url+"/poster_js/12.js?v="+Math.random());</script>
    """
    private val chapters = """
        var clist = [
            {"id":"128261","t":"프롤로그","u":"/webtoons/279/128261.html","d":"2019-08-07","od":44557},
            {"id":"1194275","t":"완결","d":"2023-05-31"}
        ];
    """.trimIndent()

    @Test
    fun chapterScriptUsesConfiguredCdnAndLocalRandomCacheKey() {
        val scripts = blacktoonPageScripts(detail, detailUrl, { config }, "123", "0.7491846257249568")
        assertEquals(listOf("https://jsc.speedwebgo.com/data/toonlist/279.js?v=0.7491846257249568"), scripts.urls.map { it.toString() })
    }

    @Test
    fun chapterListFetchesConfigThenCdnAndPreservesChapterOrdering() {
        val requests = mutableListOf<String>()
        val result = blacktoonChapters(detail, detailUrl, json) {
            requests.add(it)
            if ("/data/config.js" in it) config else chapters
        }
        assertEquals(2, requests.size)
        assertTrue(requests[0].startsWith("https://blacktoon421.com/data/config.js?v=A"))
        assertTrue(requests[1].startsWith("https://jsc.speedwebgo.com/data/toonlist/279.js?v=0."))
        assertEquals(listOf("1194275", "128261"), result.map { it.id })
        assertEquals(listOf("완결", "프롤로그"), result.map { it.title })
    }

    @Test
    fun renderedChapterSourcePreservesQueryAndIgnoresOtherMangaAndAds() {
        val html = """
            <script src="https://jsc.speedwebgo.com/data/toonlist/280.js?v=1"></script>
            <script src="https://jsc.speedwebgo.com/data/toonlist/279.js?v=0.3&amp;token=a%2Bb"></script>
            <script src="https://ads.example/ad.js"></script>
        """
        val requested = mutableListOf<String>()
        assertEquals(
            2,
            blacktoonChapters(html, detailUrl, json) {
                requested.add(it)
                chapters
            }.size,
        )
        assertEquals(listOf("https://jsc.speedwebgo.com/data/toonlist/279.js?v=0.3&token=a%2Bb"), requested)
    }

    @Test
    fun malformedChapterPayloadAndExpressionsFailExplicitly() {
        listOf("<html>blocked</html>", "var clist = [broken];", "var clist = [];", "var other = [];", chapters + "runAds();").forEach { payload ->
            assertTrue(
                runCatching {
                    blacktoonChapters(detail, detailUrl, json) { if ("/data/config.js" in it) config else payload }
                }.exceptionOrNull() is IOException,
            )
        }
        val malformed = header + """<script>loadjs(getCdn()+"/data/toonlist/279.js?v="+Math.random());</script>"""
        assertTrue(runCatching { blacktoonChapters(malformed, detailUrl, json) { config } }.exceptionOrNull() is IOException)
    }

    @Test
    fun chapterCancellationPropagates() {
        assertTrue(
            runCatching {
                blacktoonChapters(detail, detailUrl, json) {
                    if ("/data/config.js" in it) config else throw CancellationException("cancelled")
                }
            }.exceptionOrNull() is CancellationException,
        )
    }

    @Test
    fun viewerUsesConfiguredImageDistributionAndRetainsViewerReferer() {
        val html = header + """
            <script>var toonlistid=1194275; var uptime=1685556346000;</script>
            <div id="toon_content_imgs">
                <img o_src="img/one.jpg" src="/style/img/loading.svg">
                <img data-original="https://absolute.example/two.jpg" o_src="ignored.jpg">
                <img o_src="//relative.example/three.jpg">
                <img src="/four.jpg">
            </div>
        """
        val requested = mutableListOf<String>()
        val pages = blacktoonPages(html, viewerUrl, "https://old.example/", {
            requested.add(it)
            config
        }, 1_788_869_000_000)
        assertEquals(
            listOf("https://cdn6.example/img/one.jpg", "https://absolute.example/two.jpg", "https://relative.example/three.jpg", "https://blacktoon421.com/four.jpg"),
            pages.map { it.imageUrl },
        )
        assertEquals(listOf(0, 1, 2, 3), pages.map { it.index })
        assertTrue(pages.all { it.url == viewerUrl })
        assertEquals(1, requested.size)
    }

    @Test
    fun imageDistributionMatchesTimeAndPercentageBoundaries() {
        val data = blacktoonPageScripts(header, viewerUrl, { config }).variables.toMutableMap()
        data["uptime"] = "20000000"
        val boundary = 20_000_000L - 18_000_000L + 300_000L
        for ((id, domain) in listOf(0 to 3, 19 to 3, 20 to 4, 39 to 4, 40 to 5, 60 to 6, 80 to 7, 99 to 7)) {
            data["toonlistid"] = id.toString()
            assertEquals("https://cdn$domain.example/", blacktoonImageCdn(data, "https://old.example/", boundary + 1))
            assertEquals("https://fresh.example/", blacktoonImageCdn(data, "https://old.example/", boundary))
        }
    }

    @Test
    fun missingOrMalformedImageConfigurationKeepsLegacyFallback() {
        val html = header + """<div id="toon_content_imgs"><img o_src="img/one.jpg"></div>"""
        assertEquals("https://old.example/img/one.jpg", blacktoonPages(html, viewerUrl, "https://old.example/", { throw IOException("403") }).single().imageUrl)
        assertEquals("https://old.example/", blacktoonImageCdn(mapOf("img_domain2" to "javascript:bad"), "https://old.example/", 0))
        assertEquals("https://retry.example/", blacktoonImageCdn(mapOf("img_domain2" to "https://retry.example"), "https://old.example/", 0))
    }

    @Test
    fun emptyOrMalformedViewerDoesNotReturnSilentSuccess() {
        listOf("<html>blocked</html>", "<div id='toon_content_imgs'><img></div>").forEach { html ->
            assertTrue(runCatching { blacktoonPages(html, viewerUrl, "https://old.example/", { config }) }.exceptionOrNull() is IOException)
        }
    }
}
