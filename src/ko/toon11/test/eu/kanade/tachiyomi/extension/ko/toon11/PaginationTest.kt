package eu.kanade.tachiyomi.extension.ko.toon11

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationTest {
    @Test
    fun popularRequestAdvancesPage() {
        val url = toon11PopularUrl("https://toon.example", 3)
        assertEquals("3", url.queryParameter("page"))
        assertEquals("toon_c", url.queryParameter("bo_table"))
        assertEquals("0", url.queryParameter("is_over"))
    }

    @Test
    fun textSearchAdvancesPagesWithoutChangingKeyword() {
        val query = "일상+치유 &page=99#검색%2B"
        for (page in listOf(1, 2, 10)) {
            val url = toon11SearchUrl("https://toon.example", query, page)
            assertEquals("/bbs/search_stx.php", url.encodedPath)
            assertEquals(query, url.queryParameter("stx"))
            assertEquals(listOf(page.toString()), url.queryParameterValues("page"))
            assertEquals(setOf("stx", "page"), url.queryParameterNames)
            val nextPage = Jsoup.parse("""<a class="pg_next" href="?page=${page + 1}">다음</a>""")
            assertTrue(hasToon11NextPage(nextPage, url))
            val lastPage = Jsoup.parse("""<a class="pg_end" href="?page=$page">맨끝</a>""")
            assertFalse(hasToon11NextPage(lastPage, url))
        }
    }

    @Test
    fun lastPageControlAloneDoesNotMeanMorePages() {
        assertFalse(hasNext("""<span class="pg_end">맨끝</span>""", 1))
        assertFalse(hasNext("""<a class="pg_end" href="?page=10">맨끝</a>""", 10))
        assertFalse(hasNext("""<a class="pg_end" href="#">맨끝</a>""", 10))
        assertFalse(hasNext("""<a class="pg_page" href="?page=9">9</a>""", 10))
    }

    @Test
    fun numberedLinksNextGroupAndLastLinksKeepPaginationWorking() {
        assertTrue(hasNext("""<a class="pg_page" href="?page=2">2</a>""", 1))
        assertTrue(hasNext("""<a class="pg_next" href="?page=11">다음</a>""", 10))
        assertTrue(hasNext("""<a class="pg_end" href="/bbs/board.php?page=20">맨끝</a>""", 10))
    }

    @Test
    fun currentMarkerHandlesServerClampedPagesAndAccessibilityLabels() {
        assertFalse(
            hasNext(
                """
                <strong class="pg_current"><span class="sound_only">열린</span>5<span class="sound_only">페이지</span></strong>
                <a class="pg_end" href="?page=5">맨끝</a>
                """.trimIndent(),
                1,
            ),
        )
    }

    @Test
    fun disabledAndMalformedLinksDoNotContinue() {
        assertFalse(hasNext("""<a class="pg_next disabled" href="?page=2">다음</a>""", 1))
        assertFalse(hasNext("""<a class="pg_next" aria-disabled="true" href="?page=2">다음</a>""", 1))
        assertFalse(hasNext("""<a class="pg_end" href="?page=oops">맨끝</a>""", 1))
        assertFalse(hasNext("""<a class="pg_end" href="javascript:nextPage()">맨끝</a>""", 1))
    }

    @Test
    fun genrePlusIsEncodedExactlyOnce() {
        val genre = genreList.single { it.name == "일상+치유" }.value
        val url = toon11PopularUrl("https://toon.example", 1).newBuilder()
            .addQueryParameter("sca", genre)
            .build()
        assertEquals("일상+치유", url.queryParameter("sca"))
        assertTrue(url.encodedQuery!!.contains("%2B"))
        assertFalse(url.encodedQuery!!.contains("%252B"))
    }

    private fun hasNext(html: String, page: Int): Boolean {
        val url = "https://toon.example/bbs/board.php?bo_table=toon_c&page=$page".toHttpUrl()
        return hasToon11NextPage(Jsoup.parse(html, url.toString()), url)
    }
}
