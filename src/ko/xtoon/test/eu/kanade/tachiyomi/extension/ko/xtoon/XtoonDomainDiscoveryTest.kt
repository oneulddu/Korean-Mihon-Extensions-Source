package eu.kanade.tachiyomi.extension.ko.xtoon

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class XtoonDomainDiscoveryTest {
    private val requests = mutableListOf<String>()
    private fun discovery(clock: () -> Long = System::nanoTime, body: (String) -> String): XtoonDomainDiscovery = XtoonDomainDiscovery(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.toString()
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(body(request.url.toString()).toResponseBody()).build()
        }.build(),
        clock,
    )

    @Test fun guideSelectsOnlySecureRootContentAddress() {
        val invalid = listOf("http://newxtoon1.com/", "https://newxtoon1.com.evil.example", "https://newxtoon1.com:8443", "https://user@newxtoon1.com", "https://newxtoon1.com/path", "https://newxtoon1.com/?q=x", "https://newxtoon1.com/#x")
        invalid.forEach { assertNull(it, parseXtoonGuide("<a href='$it'>go</a>", XTOON_GUIDE_URL)) }
        assertEquals("https://newxtoon1.com", discovery { "<a href='https://newxtoon1.com/'>go</a>" }.discover()?.baseUrl)
        assertEquals(listOf(XTOON_GUIDE_URL), requests)
    }

    @Test fun unavailableGuideFallsBackToOfficialChannelNewestOwnMessage() {
        val html = """
            <div class='tgme_widget_message' data-post='newxtoon/4'><div class='tgme_widget_message_text'><a href='https://newxtoon1.com'>old</a></div></div>
            <div class='tgme_widget_message' data-post='newxtoon/6'><div class='tgme_widget_message_text'><a href='https://newxtoon2.com'>new</a></div></div>
            <div class='tgme_widget_message' data-post='newxtoon/7'><div class='tgme_widget_message_forwarded_from'></div><div class='tgme_widget_message_text'><a href='https://newxtoon3.com'>forward</a></div></div>
            <div class='tgme_widget_message' data-post='external/8'><div class='tgme_widget_message_text'><a href='https://newxtoon4.com'>other</a></div></div>
            <div class='tgme_widget_message' data-post='newxtoon/9'><a href='https://newxtoon5.com'>preview</a><div class='tgme_widget_message_text'>No new address</div></div>
        """.trimIndent()
        val result = discovery { if (it == XTOON_GUIDE_URL) throw IOException("reset") else html }.discover()
        assertEquals("https://newxtoon2.com", result?.baseUrl)
        assertEquals(listOf(XTOON_GUIDE_URL, XTOON_CHANNEL_URL), requests)
    }

    @Test fun sharedTimeBudgetStopsFurtherRequests() {
        var now = 0L
        assertNull(
            discovery({ now }) {
                now += TimeUnit.SECONDS.toNanos(8)
                throw IOException("timeout")
            }.discover(),
        )
        assertEquals(listOf(XTOON_GUIDE_URL), requests)
    }
}
