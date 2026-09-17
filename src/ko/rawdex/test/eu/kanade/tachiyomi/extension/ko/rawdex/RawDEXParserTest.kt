package eu.kanade.tachiyomi.extension.ko.rawdex

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDEXParserTest {
    @Test
    fun libraryAndSearchCardsUseTitleLinksInsteadOfRecentChapterLinks() {
        val document = html(
            """
            <article class="rdx-library-card">
              <a class="rdx-library-card__cover" href="/manga/sample/"><img src="/covers/sample.jpg"></a>
              <div class="rdx-library-card__body">
                <h2><a href="/manga/sample/">Sample &amp; Story</a></h2>
                <div class="rdx-library-card__chapters"><a href="/manga/sample/12/">Chapter 12</a></div>
              </div>
            </article>
            <article class="rdx-library-card rdx-search-card">
              <h2><a href="/manga/another/">Another Story</a></h2>
            </article>
            <article class="rdx-related-card"><h3><a href="/manga/suggestion/">Suggestion</a></h3></article>
            <a class="next page-numbers" href="/manga/page/2/?m_orderby=views">Next</a>
            """,
        )
        val mangas = document.select(RawDEXParser.CARD).map(RawDEXParser::manga)
        assertEquals(2, mangas.size)
        assertEquals("Sample & Story", mangas.first().title)
        assertEquals("https://rawdex.net/manga/sample/", mangas.first().url)
        assertEquals("https://rawdex.net/covers/sample.jpg", mangas.first().cover!!.absUrl("src"))
        assertNull(mangas.last().cover)
        assertEquals("https://rawdex.net/manga/page/2/?m_orderby=views", document.selectFirst(RawDEXParser.NEXT_PAGE)!!.absUrl("href"))
    }

    @Test
    fun finalAndEmptyPagesDoNotContinueThroughPreviousLinks() {
        val document = html("""<a class="prev page-numbers" href="/manga/page/2/">Previous</a><span class="page-numbers current">3</span>""")
        assertTrue(document.select(RawDEXParser.CARD).isEmpty())
        assertNull(document.selectFirst(RawDEXParser.NEXT_PAGE))
    }

    @Test
    fun detailsReadDefinitionListMetadataAndPreserveParagraphsAndAlternativeNames() {
        val details = RawDEXParser.details(
            html(
                """
                <header><h1>Site heading</h1></header>
                <img class="rdx-manga-cover" src="/covers/sample.jpg">
                <header class="rdx-manga-heading"><h1>Sample Story</h1></header>
                <p class="rdx-manga-alternative">다른 이름; Other title</p>
                <span class="rdx-manga-status">end</span>
                <div class="rdx-manga-summary"><p>First paragraph.</p><p>Second paragraph.</p></div>
                <div class="rdx-manga-tags"><a>Adventure</a><a>Fantasy</a></div>
                <dl class="rdx-manga-meta">
                  <div><dt>Artist</dt><dd><a>Illustrator</a></dd></div>
                  <div><dt>Author</dt><dd>Writer</dd></div>
                  <div><dt>Type</dt><dd>Manhwa</dd></div>
                  <div><dt>Release</dt><dd>2026</dd></div>
                </dl>
                """,
            ),
        )
        assertEquals("Sample Story", details.title)
        assertEquals("Writer", details.author)
        assertEquals("Illustrator", details.artist)
        assertEquals("Adventure, Fantasy, Manhwa", details.genres)
        assertEquals("First paragraph.\n\nSecond paragraph.\n\nAlternative names: 다른 이름; Other title", details.description)
        assertEquals("end", details.status)
        assertEquals("https://rawdex.net/covers/sample.jpg", details.cover!!.absUrl("src"))
    }

    @Test
    fun missingOptionalMetadataDoesNotRejectAValidSeries() {
        val details = RawDEXParser.details(html("""<div class="rdx-manga-heading"><h1>Sample</h1></div><div class="rdx-manga-summary">Plain summary</div>"""))
        assertEquals("Plain summary", details.description)
        assertNull(details.author)
        assertNull(details.artist)
        assertNull(details.cover)
        assertEquals("", details.status)
    }

    @Test
    fun everyChapterIsReadInSiteOrderWithoutDatesOrBadgesInNames() {
        val chaptersHtml = (347 downTo 1).joinToString("") { number ->
            """
            <a class="rdx-chapter-row" href="/manga/sample/$number/">
              <span class="rdx-chapter-row__label">Chapter $number</span>
              <span class="rdx-chapter-up">UP</span>
              <span class="rdx-chapter-row__date">3 days ago</span>
            </a>
            """
        }
        val chapters = RawDEXParser.chapters(
            html(
                """
                <a class="rdx-manga-read" href="/manga/sample/1/">Read first</a>
                <div class="rdx-chapter-list">$chaptersHtml</div>
                <div class="rdx-related-card"><a href="/manga/other/1/">Other chapter</a></div>
                """,
            ),
        )
        assertEquals(347, chapters.size)
        assertEquals("Chapter 347", chapters.first().name)
        assertEquals("Chapter 1", chapters.last().name)
        assertEquals("https://rawdex.net/manga/sample/347/", chapters.first().url)
        assertEquals("3 days ago", chapters.first().date)
        assertEquals((347 downTo 1).map { "https://rawdex.net/manga/sample/$it/" }, chapters.map { it.url })
    }

    @Test
    fun fractionalAndLegacyChapterPathsAreNotRewritten() {
        val chapters = RawDEXParser.chapters(
            html(
                """
                <div class="rdx-chapter-list">
                  <a class="rdx-chapter-row" href="https://rawdex.net/manga/sample/40-5/"><span class="rdx-chapter-row__label">Chapter 40.5</span></a>
                  <a class="rdx-chapter-row" href="/manga/sample/05/"><span class="rdx-chapter-row__label">Chapter 05</span></a>
                  <a class="rdx-chapter-row" href="/manga/sample/05/"><span class="rdx-chapter-row__label">Chapter 05</span></a>
                </div>
                """,
            ),
        )
        assertEquals(listOf("https://rawdex.net/manga/sample/40-5/", "https://rawdex.net/manga/sample/05/"), chapters.map { it.url })
        assertEquals("Chapter 40.5", chapters.first().name)
        assertEquals("", chapters.first().date)
    }

    @Test
    fun readerSelectorExcludesAdvertisementsAndKeepsIntentionalRepeatedImages() {
        val document = html(
            """
            <div class="rdx-ad-slot"><img src="/ads/banner.jpg"></div>
            <div class="reading-content rdx-reader-content">
              <div class="rdx-longstrip-pages">
                <figure class="rdx-reader-page" data-page="1"><img src="https://img.rawdex.net/chapter/01.jpg"></figure>
                <figure class="rdx-reader-page" data-page="2"><img data-src="//img.rawdex.net/chapter/02.jpg" src="/placeholder.gif"></figure>
                <figure class="rdx-reader-page" data-page="3"><img src="https://img.rawdex.net/chapter/01.jpg"></figure>
              </div>
              <div class="rdx-ad-slot"><img src="/ads/second.jpg"></div>
            </div>
            """,
        )
        // Madara expands each matching node with select("img"); an img matches itself.
        val images = document.select(RawDEXParser.PAGE_IMAGES).flatMap { it.select("img") }
        assertEquals(3, images.size)
        assertEquals("https://img.rawdex.net/chapter/01.jpg", images[0].absUrl("src"))
        assertEquals("https://img.rawdex.net/chapter/02.jpg", images[1].absUrl("data-src"))
        assertEquals(images[0].absUrl("src"), images[2].absUrl("src"))
        assertFalse(images.any { it.attr("src").startsWith("/ads/") })
    }

    private fun html(content: String) = Jsoup.parse(content, "https://rawdex.net/manga/sample/")
}
