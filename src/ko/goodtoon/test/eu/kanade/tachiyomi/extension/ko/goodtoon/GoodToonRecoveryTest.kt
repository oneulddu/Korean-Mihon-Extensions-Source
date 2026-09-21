package eu.kanade.tachiyomi.extension.ko.goodtoon

import keiyoushi.utils.AutomaticDomainInterceptor
import keiyoushi.utils.BaseUrlCacheKeys
import keiyoushi.utils.BaseUrlStorage
import keiyoushi.utils.DynamicBaseUrlResolver
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GoodToonRecoveryTest {
    private val old = "https://goodtoon004.com"
    private val next = "https://goodtoon005.com"
    private val strings = mutableMapOf("url" to old)
    private val longs = mutableMapOf("fetched" to 999_000L)
    private var manual: String? = null
    private var discovery: String? = next
    private var lookups = 0
    private var closed = 0
    private val requests = mutableListOf<Request>()
    private val resolver = DynamicBaseUrlResolver(
        storage = object : BaseUrlStorage {
            override fun getString(key: String) = strings[key]
            override fun putString(key: String, value: String) {
                strings[key] = value
            }
            override fun getLong(key: String) = longs[key] ?: 0L
            override fun putLong(key: String, value: Long) {
                longs[key] = value
            }
            override fun remove(vararg keys: String) {
                keys.forEach {
                    strings.remove(it)
                    longs.remove(it)
                }
            }
        },
        keys = BaseUrlCacheKeys("url", "fetched", "attempted"),
        fallbackBaseUrl = { next },
        isAllowedAutomaticUrl = { isGoodToonHost(it.host) },
        discoverBaseUrl = {
            lookups++
            discovery
        },
        redirectBaseUrl = { null },
        now = { 1_000_000L },
    )
    private fun client(reply: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor(
            AutomaticDomainInterceptor(
                { resolver },
                { manual },
                ::isGoodToonHost,
                {
                    val target = manual ?: resolver.resolve()
                    when {
                        isGoodToonImageHost(it.url.host) -> it.rewriteGoodToonImageHeaders(target)
                        isGoodToonHost(it.url.host) -> it.rewriteGoodToonOrigin(target)
                        else -> it
                    }
                },
            ),
        )
        .addInterceptor(GoodToonGuideRedirectInterceptor { manual })
        .addInterceptor {
            requests += it.request()
            reply(it.request())
        }
        .build()

    private fun request(url: String = "$old/manga/title/ajax/chapters?q=1") = Request.Builder().url(url)
        .header("Referer", "$old/manga/title/").header("Origin", old).build()
    private fun response(request: Request) = Response.Builder().request(request).code(200)
        .protocol(Protocol.HTTP_1_1).message("OK").body("content".toResponseBody()).build()
    private fun guide(request: Request, raw: Boolean = false, url: String = "https://t.me/goodtoon_url"): Response {
        val body = object : ResponseBody() {
            private val buffer = Buffer().writeUtf8("guide")
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = 5L
            override fun source() = buffer
            override fun close() {
                closed++
                super.close()
            }
        }
        return response(if (raw) request else request.newBuilder().url(url).build()).newBuilder()
            .code(if (raw) 301 else 200).header("Location", url).body(body).build()
    }

    @Test fun followedGuideInvalidatesFreshCacheAndKeepsContentPathAndHeaders() {
        val client = client { if (it.url.host == "goodtoon004.com") guide(it) else response(it) }
        client.newCall(request()).execute().close()
        assertEquals(listOf("goodtoon004.com", "goodtoon005.com"), requests.map { it.url.host })
        assertEquals("$next/manga/title/ajax/chapters?q=1", requests.last().url.toString())
        assertEquals("$next/manga/title/", requests.last().header("Referer"))
        assertEquals(next, requests.last().header("Origin"))
        assertEquals(next, strings["url"])
        assertEquals(1, lookups)
        assertEquals(1, closed)
        client.newCall(request("https://img.goodtoon9001.top/cover.jpg?v=1")).execute().close()
        assertEquals("img.goodtoon9001.top", requests.last().url.host)
        assertEquals("v=1", requests.last().url.query)
        assertEquals("$next/manga/title/", requests.last().header("Referer"))
        assertEquals(next, requests.last().header("Origin"))
    }

    @Test fun rawGuideRedirectAlsoRecovers() {
        client { if (it.url.host == "goodtoon004.com") guide(it, raw = true) else response(it) }
            .newCall(request()).execute().close()
        assertEquals(2, requests.size)
        assertEquals(1, lookups)
        assertEquals(1, closed)
    }

    @Test fun failedDiscoveryPreservesCacheAndCooldownPreventsRepeatedLookup() {
        discovery = null
        val client = client { guide(it) }
        repeat(2) { assertThrows(IOException::class.java) { client.newCall(request()).execute() } }
        assertEquals(old, strings["url"])
        assertEquals(1, lookups)
        assertEquals(2, requests.size)
        assertEquals(2, closed)
    }

    @Test fun repeatedGuideStopsAfterOneRetryAndClosesBothBodies() {
        assertThrows(IOException::class.java) { client { guide(it) }.newCall(request()).execute() }
        assertEquals(2, requests.size)
        assertEquals(1, lookups)
        assertEquals(2, closed)
    }

    @Test fun manualContentHostsAndCdnResponsesNeverTriggerAutomaticRecovery() {
        manual = old
        client { guide(it) }.newCall(request()).execute().close()
        manual = null
        client { guide(it) }.newCall(request("https://img.goodtoon9001.top/cover.jpg")).execute().close()
        assertEquals(0, lookups)
        assertEquals(2, requests.size)
        assertEquals(old, strings["url"])
    }

    @Test fun unrelatedRedirectsAreNotTreatedAsOfficialRetirement() {
        listOf("https://t.me/other", "https://t.me/goodtoon_url/19", "https://evil.example/goodtoon_url", "http://t.me/goodtoon_url", "https://t.me/goodtoon_url?x=1").forEach { url ->
            client { guide(it, url = url) }.newCall(request()).execute().close()
        }
        assertEquals(0, lookups)
        assertTrue(requests.all { it.url.host == "goodtoon004.com" })
    }

    @Test fun newestOfficialPostSelects005OverPrevious004() {
        val html = """
            <div class="tgme_widget_message" data-post="goodtoon_url/18"><div class="tgme_widget_message_text"><a href="http://goodtoon004.com/">old</a></div></div>
            <div class="tgme_widget_message" data-post="goodtoon_url/19"><div class="tgme_widget_message_text"><a href="http://goodtoon.top/">guide</a><a href="http://goodtoon005.com/">new</a></div></div>
        """.trimIndent()
        assertEquals(next, parseGoodToonChannel(html))
    }
}
