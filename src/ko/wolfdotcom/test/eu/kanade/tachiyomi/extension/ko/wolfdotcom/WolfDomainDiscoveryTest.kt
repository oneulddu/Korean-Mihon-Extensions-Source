package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class WolfDomainDiscoveryTest {
    private val requests = mutableListOf<String>()

    @Test
    fun usesOfficialChannelWhenGuideFails() {
        val result = discovery { url ->
            if (url == WOLF_GUIDE_URL) throw IOException("reset")
            Reply(body = """<div class="tgme_widget_message" data-post="wfwf_com/149"><div class="tgme_widget_message_text"><a href="https://wfwf494.com">늑대닷컴</a></div></div>""")
        }.discover()
        assertEquals("https://wfwf494.com", result?.baseUrl)
        assertEquals("공식 텔레그램 주소 안내", result?.source)
        assertEquals(listOf(WOLF_GUIDE_URL, WOLF_CHANNEL_URL), requests)
    }

    @Test
    fun keepsPrimaryGuideFirst() {
        assertEquals(
            "https://wfwf494.com",
            discovery {
                Reply(body = """<a href="https://wfwf494.com">이동</a>""")
            }.discover()?.baseUrl,
        )
        assertEquals(listOf(WOLF_GUIDE_URL), requests)
    }

    @Test
    fun channelUsesNewestMessageAndIgnoresSecondarySiteAndOutsideLinks() {
        val html = """
            <a href="https://wfwf999.com">page chrome</a>
            <div class="tgme_widget_message" data-post="wfwf_com/149"><div class="tgme_widget_message_text">https://wftoon227.com https://wfwf494.com</div></div>
            <div class="tgme_widget_message" data-post="wfwf_com/148"><div class="tgme_widget_message_text">https://wfwf493.com</div></div>
            <div class="tgme_widget_message" data-post="other/999"><div class="tgme_widget_message_text">https://wfwf999.com</div></div>
            <div class="tgme_widget_message" data-post="wfwf_com/150"><div class="tgme_widget_message_forwarded_from">forwarded</div><div class="tgme_widget_message_text">https://wfwf998.com</div></div>
        """
        assertEquals("https://wfwf494.com", parseWolfOfficialChannel(html))
        assertNull(parseWolfOfficialChannel("<a href='https://wfwf999.com'>outside</a>"))
    }

    @Test
    fun neverFollowsUnrelatedRedirectOrLoop() {
        assertNull(discovery { Reply(302, location = "https://evil.example") }.discover())
        assertTrue(requests.all { it in listOf(WOLF_GUIDE_URL, WOLF_CHANNEL_URL) })
        requests.clear()
        assertNull(discovery { Reply(302, location = WOLF_GUIDE_URL) }.discover())
        assertEquals(2, requests.size)
    }

    @Test
    fun stopsWhenSharedBudgetExpires() {
        var now = 0L
        assertNull(
            discovery({ now }) {
                now += TimeUnit.SECONDS.toNanos(8)
                throw IOException("timeout")
            }.discover(),
        )
        assertEquals(listOf(WOLF_GUIDE_URL), requests)
    }

    private data class Reply(val code: Int = 200, val body: String = "", val location: String? = null)

    private fun discovery(nanoTime: () -> Long = System::nanoTime, reply: (String) -> Reply): WolfDomainDiscovery {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.toString()
            val value = reply(request.url.toString())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(value.code).message("test").body(value.body.toResponseBody())
                .apply { value.location?.let { header("Location", it) } }.build()
        }.build()
        return WolfDomainDiscovery(client, nanoTime)
    }
}
