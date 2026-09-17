package eu.kanade.tachiyomi.extension.ko.navercomic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchUrlTest {
    @Test
    fun reservedCharactersRemainInKeywordForEverySourceType() {
        val query = "일상+치유 &page=99#한글?x=%2B/검색"
        for (type in listOf("webtoon", "bestChallenge", "challenge")) {
            val url = naverSearchUrl("https://comic.naver.com", type, query, 2)
            assertEquals("/api/search/$type", url.encodedPath)
            assertEquals(query, url.queryParameter("keyword"))
            assertEquals(listOf("2"), url.queryParameterValues("page"))
            assertEquals(setOf("keyword", "page"), url.queryParameterNames)
            assertNull(url.fragment)
        }
    }
}
