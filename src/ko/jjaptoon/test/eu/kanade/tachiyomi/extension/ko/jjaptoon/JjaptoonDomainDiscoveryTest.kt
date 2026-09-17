package eu.kanade.tachiyomi.extension.ko.jjaptoon

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class JjaptoonDomainDiscoveryTest {
    private val net = jjaptoonGuideUrls[0]
    private val live = jjaptoonGuideUrls[1]
    private val requests = mutableListOf<String>()

    @Test
    fun followsGuideMigrationAndReadsDynamicDomainApi() {
        val discovery = discovery { url ->
            when (url) {
                net -> Reply(302, location = live)
                live -> Reply(body = "<div id='latestDomain'>로딩 중입니다...</div><!-- old https://jjaptoon005.com -->")
                live + "data/domain.json" -> Reply(body = """{"domain":"https://www.jjaptoon008.com","telegram":"https://t.me/jjaptoon003"}""")
                else -> error("Unexpected request: $url")
            }
        }
        assertEquals("https://www.jjaptoon008.com", discovery.discover())
        assertEquals(listOf(net, live, live + "data/domain.json"), requests)
    }

    @Test
    fun unavailableOriginalGuideFallsBackToConfirmedGuide() {
        val discovery = discovery { url ->
            when (url) {
                net -> throw IOException("reset")
                live -> Reply(body = "<div id='latestDomain'>loading</div>")
                else -> Reply(body = """{"domain":"https://jjaptoon009.com"}""")
            }
        }
        assertEquals("https://jjaptoon009.com", discovery.discover())
        assertEquals(3, requests.size)
    }

    @Test
    fun acceptsLegacyMarkupAndDirectContentRedirect() {
        assertEquals("https://jjaptoon008.com", discovery { Reply(body = "<div id='latestDomain'>jjaptoon008.com</div>") }.discover())
        assertEquals("https://jjaptoon009.com", discovery { Reply(302, location = "https://jjaptoon009.com/") }.discover())
    }

    @Test
    fun rejectsUntrustedGuideRedirectsAndRedirectCycles() {
        assertNull(discovery { Reply(302, location = "https://evil.example/") }.discover())
        assertTrue(requests.all { it in jjaptoonGuideUrls })
        requests.clear()
        assertNull(discovery { Reply(302, location = net) }.discover())
        assertEquals(jjaptoonGuideUrls, requests)
    }

    @Test
    fun rejectsInvalidJsonDomainWithoutExtractingSubstring() {
        val invalid = listOf(
            "http://jjaptoon008.com",
            "https://jjaptoon008.com.evil.example",
            "https://jjaptoon008.com@evil.example",
            "https://user@jjaptoon008.com",
            "https://jjaptoon008.com:8443",
            "https://jjaptoon008.com/path",
            "https://jjaptoon008.com/?q=x",
            "https://jjaptoon008.com/#x",
        )
        invalid.forEach { assertNull(it, parseJjaptoonDomainJson("""{"domain":"$it"}""")) }
        listOf("invalid", "[]", "{}", "{\"domain\":null}", "{\"domain\":123}").forEach {
            assertNull(parseJjaptoonDomainJson(it))
        }
    }

    @Test
    fun doesNotContinueAfterSharedTimeBudgetExpires() {
        var now = 0L
        val discovery = discovery({ now }) {
            now += TimeUnit.SECONDS.toNanos(8)
            throw IOException("timeout")
        }
        assertNull(discovery.discover())
        assertEquals(listOf(net), requests)
    }

    private data class Reply(val code: Int = 200, val body: String = "", val location: String? = null)

    private fun discovery(nanoTime: () -> Long = System::nanoTime, reply: (String) -> Reply): JjaptoonDomainDiscovery {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request: Request = chain.request()
            requests += request.url.toString()
            val value = reply(request.url.toString())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(value.code).message("test").body(value.body.toResponseBody())
                .apply { value.location?.let { header("Location", it) } }.build()
        }.build()
        return JjaptoonDomainDiscovery(client, nanoTime)
    }
}
