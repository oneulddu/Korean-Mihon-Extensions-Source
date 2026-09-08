package eu.kanade.tachiyomi.extension.ko.xtoon

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NewXtoonParserTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()
    private fun detail() = Jsoup.parse(fixture("detail.html"), "https://newxtoon1.com/comics/1876")

    @Test fun parsesRealCardsExcludingInlineAdAndRealPagination() {
        val page = NewXtoonParser.mangaPage(Jsoup.parse(fixture("list.html"), "https://newxtoon1.com/comics?sort=popular&page=1"))
        assertEquals(23, page.mangas.size)
        assertTrue(page.hasNextPage)
        assertEquals("외모지상주의", page.mangas.first().title)
        assertTrue(page.mangas.all { it.url.matches(NewXtoonParser.comicPath) })
        val last = NewXtoonParser.mangaPage(Jsoup.parse(fixture("list.html"), "https://newxtoon1.com/comics?sort=popular&page=369"))
        assertFalse(last.hasNextPage)
        val search = NewXtoonParser.mangaPage(Jsoup.parse(fixture("search.html"), "https://newxtoon1.com/search?q=ARCHE&page=1"))
        assertFalse(search.hasNextPage)
    }

    @Test fun ignoresUnrelatedPaginationAndRecommendationCards() {
        val document = Jsoup.parse("""<a href='/comics?sort=popular&page=2'>Next</a><a class='comic-link' href='https://evil.example/comics/1'><h3>Ad</h3></a>""", "https://newxtoon1.com/comics?genre=2&page=1")
        assertFalse(NewXtoonParser.mangaPage(document).hasNextPage)
        assertTrue(NewXtoonParser.mangaPage(document).mangas.isEmpty())
    }

    @Test fun paginationIgnoresQueryParameterOrder() {
        val document = Jsoup.parse("""<a href='/comics?genre=2&amp;sort=popular&amp;page=2'>Next</a>""", "https://newxtoon1.com/comics?page=1&sort=popular&genre=2")
        assertTrue(NewXtoonParser.mangaPage(document).hasNextPage)
    }

    @Test fun readsTitleAuthorsAndSynopsisWithoutRecommendations() {
        val manga = NewXtoonParser.details(detail())
        assertEquals("화산귀환", manga.title)
        assertEquals("ARCHE, LICO, 비가", manga.author)
        assertTrue(manga.description!!.contains("화산파"))
        assertTrue(manga.genre!!.contains("무협/사극"))
        assertTrue(manga.thumbnail_url!!.startsWith("https://user281.quicksharefiles.top/"))
    }

    @Test fun collectsAll178ChaptersAcrossNinePagesWithoutInventingIds() {
        val index = NewXtoonParser.chapterIndex(detail())
        val requests = mutableListOf<Int>()
        assertEquals(178, index.expectedCount)
        assertEquals(20, index.first.chapters.size)
        val result = NewXtoonParser.allChapters(index) { page ->
            requests += page
            NewXtoonParser.chapterBatch(fixture("chapters-$page.json"), index.apiUrl)
        }
        assertEquals((2..9).toList(), requests)
        assertEquals(178, result.size)
        assertEquals(178, result.map { it.url }.distinct().size)
        assertEquals("/comics/1876/chapters/1057185", result.first().url)
        assertEquals("/comics/1876/chapters/139884", result.last().url)
        assertTrue(result.any { it.name == "외유4화(특별편)" })
        assertTrue(result.all { it.date_upload > 0 })
    }

    @Test(expected = IOException::class)
    fun rejectsPartialChapterResult() {
        val index = NewXtoonParser.chapterIndex(detail())
        NewXtoonParser.allChapters(index.copy(first = index.first.copy(nextPage = null))) { error("No request expected") }
    }

    @Test(expected = IOException::class)
    fun rejectsRepeatedPageWithoutProgress() {
        val index = NewXtoonParser.chapterIndex(detail())
        NewXtoonParser.allChapters(index) { index.first.copy(nextPage = 3) }
    }

    @Test(expected = IOException::class)
    fun rejectsBrokenContinuation() {
        NewXtoonParser.chapterBatch("""{"chapters":[],"has_more":true,"next_page":null}""", "https://newxtoon1.com/comics/1876/chapters".toHttpUrl())
    }

    @Test(expected = IOException::class)
    fun rejectsCrossComicChapterApi() {
        val document = detail()
        document.selectFirst("#chapters")!!.attr("data-chapters-url", "https://newxtoon1.com/comics/1/chapters")
        NewXtoonParser.chapterIndex(document)
    }

    @Test fun readerHas146OrderedImagesAndExcludesAds() {
        val document = Jsoup.parse(fixture("chapter.html"), "https://newxtoon1.com/comics/1876/chapters/139884")
        document.body().prepend("<img src='https://cdn.example/ad.jpg'><div data-reader-image-error></div>")
        val pages = NewXtoonParser.pages(document)
        assertEquals(146, pages.size)
        assertEquals("https://user281.quicksharefiles.top/toon/831645/6c839307567908c3ee5941331a5f198d4f02f759.jpg", pages.first().imageUrl)
        assertEquals(document.location(), pages.first().url)
    }

    @Test(expected = IOException::class)
    fun rejectsMissingImagePosition() {
        val document = Jsoup.parse(fixture("chapter.html"), "https://newxtoon1.com/comics/1876/chapters/139884")
        document.selectFirst("[data-image-position=2]")!!.remove()
        NewXtoonParser.pages(document)
    }

    @Test(expected = IOException::class)
    fun rejectsMissingFinalImageUsingDeclaredCount() {
        val document = Jsoup.parse(fixture("chapter.html"), "https://newxtoon1.com/comics/1876/chapters/139884")
        document.selectFirst("[data-image-position=146]")!!.remove()
        NewXtoonParser.pages(document)
    }

    @Test fun partialChapterErrorIncludesActualCounts() {
        val index = NewXtoonParser.chapterIndex(detail())
        val error = runCatching {
            NewXtoonParser.allChapters(index.copy(first = index.first.copy(nextPage = null))) { error("Unexpected request") }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("20/178"))
    }

    @Test fun normalizesOnlyWhitespaceCaseAndUnicodeForLegacyTitles() {
        assertEquals(NewXtoonParser.normalizedTitle("  작품   ABC "), NewXtoonParser.normalizedTitle("작품 abc"))
        assertNotEquals(NewXtoonParser.normalizedTitle("작품 완전판"), NewXtoonParser.normalizedTitle("작품"))
        assertNotEquals(NewXtoonParser.normalizedTitle("작 품"), NewXtoonParser.normalizedTitle("작품"))
    }
}
