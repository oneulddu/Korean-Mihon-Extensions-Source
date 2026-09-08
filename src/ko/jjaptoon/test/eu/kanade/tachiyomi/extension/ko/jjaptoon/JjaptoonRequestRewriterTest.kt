package eu.kanade.tachiyomi.extension.ko.jjaptoon

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.TimeUnit

class JjaptoonRequestRewriterTest {
    private val automaticHosts = Regex("""^(?:www\.)?jjaptoon\d{3}\.com$""")

    @Test
    fun automaticHostImagesUseManualUrlAndPreserveImagePathAndQuery() {
        val rewriter = JjaptoonRequestRewriter(automaticHosts)
        val request = Request.Builder().url("http://www.jjaptoon005.com:8080/images/one.jpg?signature=123")
            .header("Referer", "https://www.jjaptoon005.com/chapters/1")
            .header("Origin", "https://www.jjaptoon005.com")
            .build()
        val rewritten = rewriter.rewrite(request, "https://manual.example") { error("manual mode must not discover") }
        assertEquals("https://manual.example/images/one.jpg?signature=123", rewritten.url.toString())
        assertEquals("https://manual.example/chapters/1", rewritten.header("Referer"))
        assertEquals("https://manual.example", rewritten.header("Origin"))
    }

    @Test
    fun unrelatedCdnIsUnchangedAndDoesNotTriggerDiscovery() {
        val rewriter = JjaptoonRequestRewriter(automaticHosts)
        val request = Request.Builder().url("https://cdn.example/1.jpg").build()
        assertSame(request, rewriter.rewrite(request, "https://manual.example") { error("unexpected lookup") })
        assertSame(request, rewriter.rewrite(request, null) { error("unexpected lookup") })
    }

    @Test
    fun precedingManualHostIsRetainedBrieflyForQueuedImages() {
        var time = 0L
        val rewriter = JjaptoonRequestRewriter(automaticHosts) { time }
        rewriter.updateManual("https://old.example")
        rewriter.updateManual("https://new.example")
        val oldRequest = Request.Builder().url("https://old.example/images/1.jpg").build()
        assertEquals("new.example", rewriter.rewrite(oldRequest, "https://new.example") { error("unexpected lookup") }.url.host)
        time = TimeUnit.MINUTES.toMillis(16)
        assertSame(oldRequest, rewriter.rewrite(oldRequest, "https://new.example") { error("unexpected lookup") })
    }

    @Test
    fun clearingManualSettingRewritesQueuedImagesToAutomaticSite() {
        val rewriter = JjaptoonRequestRewriter(automaticHosts)
        rewriter.updateManual("https://old.example")
        rewriter.updateManual(null)
        val oldRequest = Request.Builder().url("https://old.example/images/1.jpg").build()
        assertEquals("www.jjaptoon006.com", rewriter.rewrite(oldRequest, null) { "https://www.jjaptoon006.com" }.url.host)
    }
}
