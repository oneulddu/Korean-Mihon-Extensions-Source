package eu.kanade.tachiyomi.extension.ko.blacktoon

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class BlacktoonDataScriptsTest {
    private val base = "https://blacktoon421.com/"
    private val json = Json { ignoreUnknownKeys = true }
    private val config = """
        var poster_js = "20260903";
        //inc_url = "https://obsolete.example";
        inc_url2= "https://ttjsde.speedwebgo.com";
        inc_url = "https://jsc.speedwebgo.com";
    """.trimIndent()
    private val html = """
        <script>var poster_js = "2026"; var inc_url = '';</script>
        <script>document.write("<script src='/data/config.js?v=A"+timeKey+"'></s"+"cript>");</script>
        <script>
        async function init_data_js() {
            try { await loadScript(inc_url2+'/webtoon_1.js?v=09082047'); }
            catch (error) { await loadScript(inc_url+'/data/webtoon/webtoon_1_09082045.js?'+poster_js); }
            try { await loadScript(inc_url2+'/webtoon_0.js?v=09082047'); }
            catch (error) { await loadScript(inc_url+'/data/webtoon/webtoon_0_09082045.js?'+poster_js); }
            await loadScript(inc_url3+'/style/js/index.data.js?v=7.'+poster_js);
        }
        </script>
        <script src="https://ads.example/ad.js"></script>
    """.trimIndent()

    private fun scripts() = blacktoonDataScripts(html, base, { config }, "4821080926")
    private fun payload(index: Int) = "var data$index = [{\"x\":\"$index\",\"t\":\"series\",\"unused\":true}];\n"

    @Test
    fun rawHtmlLoadsOnlyConfigurationAndPreservesBothUrlQueries() {
        val requested = mutableListOf<String>()
        val scripts = blacktoonDataScripts(html, base, {
            requested.add(it)
            config
        }, "4821080926")
        assertEquals(listOf("${base}data/config.js?v=A4821080926"), requested)
        assertEquals(listOf(1, 0), scripts.map { it.index })
        scripts.forEach {
            assertEquals(
                listOf(
                    "https://ttjsde.speedwebgo.com/webtoon_${it.index}.js?v=09082047",
                    "https://jsc.speedwebgo.com/data/webtoon/webtoon_${it.index}_09082045.js?20260903",
                ),
                it.urls,
            )
        }
    }

    @Test
    fun primarySuccessDoesNotFetchFallback() {
        val requested = mutableListOf<String>()
        val items = loadBlacktoonDataScripts(scripts(), json) {
            requested.add(it)
            payload(if ("webtoon_1" in it) 1 else 0)
        }
        assertEquals(listOf(1, 0), items.map { it.listIndex })
        assertEquals(2, requested.size)
        assertTrue(requested.all { it.startsWith("https://ttjsde.speedwebgo.com/") })
    }

    @Test
    fun failedPrimaryUsesFallbackForSameIndexOnly() {
        val requested = mutableListOf<String>()
        val items = loadBlacktoonDataScripts(scripts(), json) {
            requested.add(it)
            if ("webtoon_1.js" in it) throw IOException("403")
            payload(if ("webtoon_1" in it) 1 else 0)
        }
        assertEquals(listOf("1", "0"), items.map { it.id })
        assertEquals(listOf(scripts()[0].urls[0], scripts()[0].urls[1], scripts()[1].urls[0]), requested)
    }

    @Test
    fun malformedWrongIndexAndEmptyPayloadsUseFallback() {
        listOf("<html>blocked</html>", "var data1 = [broken];", "var data1 = [];", payload(0)).forEach { bad ->
            val items = loadBlacktoonDataScripts(scripts(), json) {
                if ("webtoon_1.js" in it) bad else payload(if ("webtoon_1" in it) 1 else 0)
            }
            assertEquals(listOf(1, 0), items.map { it.listIndex })
        }
    }

    @Test
    fun renderedScriptSourcesWorkWhenConfigurationIsUnavailable() {
        val rendered = """
            <script src="/data/config.js?v=A123"></script>
            <script src="//ttjsde.speedwebgo.com/webtoon_1.js?v=12&amp;token=a%2Bb"></script>
            <script src="/webtoon_0.js?v=34"></script>
            <script src="https://ads.example/advert.js"></script>
        """
        val scripts = blacktoonDataScripts(rendered, base, { throw IOException("unavailable") })
        assertEquals(listOf("https://ttjsde.speedwebgo.com/webtoon_1.js?v=12&token=a%2Bb"), scripts[0].urls)
        assertEquals(listOf("${base}webtoon_0.js?v=34"), scripts[1].urls)
    }

    @Test
    fun fallbackOnlyAndLiteralQueriesRemainSupported() {
        val scripts = blacktoonDataScripts(
            """<script>
                var inc_url = '';
                var poster_js = '123';
                loadScript( inc_url + '/data/webtoon/webtoon_1_456.js?' + poster_js );
                loadScript('/data/webtoon/webtoon_0_456.js?v=5&key=a+b');
            </script>""",
            base,
            { error("should not fetch") },
        )
        assertEquals(listOf("${base}data/webtoon/webtoon_1_456.js?123"), scripts[0].urls)
        assertEquals(listOf("${base}data/webtoon/webtoon_0_456.js?v=5&key=a+b"), scripts[1].urls)
    }

    @Test
    fun malformedExpressionsAndNonHttpUrlsCannotBecomeRequests() {
        listOf(
            "unknown + '/webtoon_1.js?v=1'",
            "inc_url2 + '/webtoon_1.js?v=1' + missing",
            "getHost() + '/webtoon_1.js?v=1'",
            "'javascript:/webtoon_1.js'",
            "'https://user:pass@example.org/webtoon_1.js'",
            "'/webtoon_1.js#fragment'",
            "'/webtoon_1.js' +",
        ).forEach { expression ->
            val fixture = """<script>loadScript($expression); loadScript('/webtoon_0.js?v=2');</script>"""
            assertTrue(runCatching { blacktoonDataScripts(fixture, base, { config }) }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun commentsAndUnrelatedScriptsAreIgnoredAndDuplicateUrlsRemoved() {
        val fixture = html + """
            <script>
            //loadScript('https://obsolete.example/webtoon_1.js');
            /* loadScript('https://obsolete.example/webtoon_0.js'); */
            loadScript(inc_url2+'/webtoon_1.js?v=09082047');
            </script>
            <script src="https://ttjsde.speedwebgo.com/webtoon_1.js?v=09082047"></script>
        """
        assertEquals(scripts(), blacktoonDataScripts(fixture, base, { config }))
    }

    @Test
    fun cancellationDoesNotRequestFallback() {
        val requested = mutableListOf<String>()
        val error = runCatching {
            loadBlacktoonDataScripts(scripts(), json) {
                requested.add(it)
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, requested.size)
    }

    @Test
    fun failedPartitionCannotReplaceExistingCatalogWithPartialResults() {
        var failed = false
        var time = 0L
        val catalog = BlacktoonCatalog(
            {
                loadBlacktoonDataScripts(scripts(), json) {
                    if (failed && "webtoon_0" in it) throw IOException("403")
                    payload(if ("webtoon_1" in it) 1 else 0)
                }
            },
            { time },
            ttlMs = 10,
        )
        val before = catalog.page(1, BlacktoonSelection())
        failed = true
        time = 11
        assertEquals(before, catalog.page(1, BlacktoonSelection()))
        assertFalse(before.first.isEmpty())
    }
}
