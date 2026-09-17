package eu.kanade.tachiyomi.extension.ko.goodtoon

import keiyoushi.utils.normalizeBaseUrl
import keiyoushi.utils.rewriteBaseUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GoodToonParserTest {
    private val base = "https://www.goodtoon003.com"
    private fun fixture(name: String) = javaClass.getResource("/$name.html")!!.readText()
    private fun document(name: String, path: String) = Jsoup.parse(fixture(name), base + path)

    @Test fun catalogueUsesActualNextLinksAndExcludesPlatformIcon() {
        val result = GoodToonParser.catalogue(document("list", "/?pg=1"))
        assertEquals("덕혜옹주를 도와줘", result.mangas.single().title)
        assertEquals("/manga/gt-60423/", result.mangas.single().url)
        assertEquals("https://img.goodtoon9001.top/gt-60423/cover.jpg", result.mangas.single().thumbnail)
        assertTrue(result.hasNext)
        assertFalse(GoodToonParser.catalogue(document("list", "/?pg=2")).hasNext)
        assertFalse(GoodToonParser.catalogue(document("list", "/?q=unrelated")).hasNext)
        val last = document("list", "/")
        last.select(".pagination").remove()
        assertFalse(GoodToonParser.catalogue(last).hasNext)
    }

    @Test fun emptyGridIsValidButHasNoNextPage() {
        val page = GoodToonParser.catalogue(Jsoup.parse("<div class=card-grid></div>", "$base/"))
        assertTrue(page.mangas.isEmpty())
        assertFalse(page.hasNext)
    }

    @Test(expected = IOException::class)
    fun missingGridIsAnError() {
        GoodToonParser.catalogue(Jsoup.parse("<h1>Just a moment</h1>", "$base/"))
    }

    @Test fun parsesDetailsAndRealChapterSlugsDatesAndBadges() {
        val manga = GoodToonParser.details(document("detail", "/manga/gt-60423/"))
        assertEquals("덕혜옹주를 도와줘", manga.title)
        assertEquals("홍인표,주먹", manga.author)
        assertEquals("드라마", manga.genre)
        assertTrue(manga.description!!.contains("현민"))
        val chapters = GoodToonParser.chapters(document("chapters", "/manga/gt-60423/ajax/chapters"))
        assertEquals(26, chapters.size)
        assertEquals("/manga/gt-60423/26/", chapters.first().url)
        assertEquals("덕혜옹주를 도와줘 26화", chapters.first().name)
        assertEquals("/manga/gt-60423/chapter-24/", chapters[2].url)
        assertTrue(chapters.all { it.date > 0 && it.url.startsWith("/") && !it.name.startsWith("UP") })
        assertTrue(chapters.first().date >= chapters.last().date)
    }

    @Test(expected = IOException::class)
    fun rejectsEmptyChapterResponse() {
        GoodToonParser.chapters(Jsoup.parse("", "$base/manga/gt-60423/ajax/chapters"))
    }

    @Test(expected = IOException::class)
    fun rejectsOtherMangaChapter() {
        val doc = document("chapters", "/manga/gt-60423/ajax/chapters")
        doc.selectFirst("li a")!!.attr("href", "/manga/gt-999/26/")
        GoodToonParser.chapters(doc)
    }

    @Test(expected = IOException::class)
    fun rejectsForeignChapterHost() {
        val doc = document("chapters", "/manga/gt-60423/ajax/chapters")
        doc.selectFirst("li a")!!.attr("href", "https://evil.example/manga/gt-60423/26/")
        GoodToonParser.chapters(doc)
    }

    @Test fun explicitEpisodeSuffixWinsOverUnrelatedLeadingNumber() {
        val doc = Jsoup.parse("""<li class=wp-manga-chapter><a href='/manga/gt-19964/426/'>0427원존용의비상 426화</a></li>""", "$base/manga/gt-19964/ajax/chapters")
        assertEquals(426f, GoodToonParser.chapters(doc).single().number)
    }

    @Test fun readsAll108ImagesInOrderAndFallsBackToSrc() {
        val doc = document("reader", "/manga/gt-60423/26/")
        val pages = GoodToonParser.images(doc)
        assertEquals(108, pages.size)
        assertEquals("https://img.goodtoon9001.top/gt-60423/26/001.jpg", pages.first())
        assertEquals("https://img.goodtoon9001.top/gt-60423/26/108.jpg", pages.last())
        val first = doc.selectFirst("img")!!
        first.attr("src", first.attr("data-src")).removeAttr("data-src")
        assertEquals(pages, GoodToonParser.images(doc))
    }

    @Test(expected = IOException::class)
    fun rejectsPartialReader() {
        val doc = document("reader", "/manga/gt-60423/26/")
        doc.selectFirst("img")!!.removeAttr("data-src")
        GoodToonParser.images(doc)
    }

    private fun post(id: String, href: String, forwarded: Boolean = false) = """
        <div class=tgme_widget_message data-post="$id">
        ${if (forwarded) "<div class=tgme_widget_message_forwarded_from></div>" else ""}
        <div class=tgme_widget_message_text><a href="$href">주소</a></div></div>
    """

    @Test fun officialChannelUsesNewestOwnPostAndUpgradesValidatedHttp() {
        val html = post("goodtoon_url/17", "http://goodtoon003.com/") +
            post("goodtoon_url/18", "https://www.goodtoon004.com/") +
            post("other/19", "https://goodtoon999.com/") +
            post("goodtoon_url/20", "https://goodtoon998.com/", true)
        assertEquals("https://www.goodtoon004.com", parseGoodToonChannel(html))
        assertEquals("https://goodtoon003.com", parseGoodToonChannel(post("goodtoon_url/17", "http://goodtoon003.com/")))
    }

    @Test fun guideRejectsForeignHostsPortsCredentialsAndNonRootUrls() {
        listOf(
            "http://goodtoon003.com:8080/", "https://goodtoon003.com:80/", "https://user@goodtoon003.com/",
            "https://goodtoon003.com/?next=bad", "https://goodtoon003.com/#fragment", "https://goodtoon003.com/manga/",
            "https://goodtoon003.com.evil.example/", "https://img.goodtoon9001.top/", "https://goodtoon.top/",
        ).forEach {
            assertNull(it, parseGoodToonChannel(post("goodtoon_url/17", it)))
        }
    }

    @Test fun rewritingPreservesCdnAndUpdatesSiteHeadersAndPort() {
        val cdn = Request.Builder().url("https://img.goodtoon9001.top/gt-60423/26/001.jpg").build()
        assertSame(cdn, cdn.rewriteBaseUrl("https://goodtoon004.com", ::isGoodToonHost))
        val site = Request.Builder().url("http://www.goodtoon003.com/manga/gt-60423/")
            .header("Referer", "$base/manga/gt-60423/").header("Origin", base).build()
            .rewriteBaseUrl("https://goodtoon004.com", ::isGoodToonHost)
        assertEquals("https://goodtoon004.com/manga/gt-60423/", site.url.toString())
        assertEquals("https://goodtoon004.com/manga/gt-60423/", site.header("Referer"))
        assertEquals("https://goodtoon004.com", site.header("Origin"))
        assertNull(normalizeBaseUrl("http://goodtoon003.com"))
    }

    @Test fun leavingManualModeRewritesThePreviousManualReferer() {
        val request = Request.Builder().url("$base/manga/gt-60423/")
            .header("Referer", "https://manual.example/manga/gt-60423/")
            .header("Origin", "https://manual.example").build()
            .rewriteGoodToonOrigin("https://goodtoon004.com")
        assertEquals("https://goodtoon004.com/manga/gt-60423/", request.header("Referer"))
        assertEquals("https://goodtoon004.com", request.header("Origin"))
    }

    @Test fun cdnCoverHeadersFollowRotationWithoutChangingImageUrl() {
        val cover = Request.Builder().url("https://img.goodtoon9001.top/gt-60423/cover.jpg")
            .header("Referer", "https://www.goodtoon003.com/").build()
        val fixed = cover.rewriteGoodToonImageHeaders("https://goodtoon004.com")
        assertEquals(cover.url, fixed.url)
        assertEquals("https://goodtoon004.com/", fixed.header("Referer"))
        assertEquals("https://goodtoon004.com", fixed.header("Origin"))
        val reader = cover.newBuilder().header("Referer", "https://goodtoon003.com/manga/gt-60423/29/").build()
            .rewriteGoodToonImageHeaders("https://manual.example")
        assertEquals("https://manual.example/manga/gt-60423/29/", reader.header("Referer"))
        val external = Request.Builder().url("https://external.example/image.jpg").build()
        assertSame(external, external.rewriteGoodToonImageHeaders("https://goodtoon004.com"))
    }
}
