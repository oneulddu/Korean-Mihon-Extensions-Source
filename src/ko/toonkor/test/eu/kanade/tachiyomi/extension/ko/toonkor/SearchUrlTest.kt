package eu.kanade.tachiyomi.extension.ko.toonkor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchUrlTest {
    @Test
    fun reservedCharactersCannotReplaceSearchFieldsOrCreateFragment() {
        val query = "일상+치유 &sfl=other#한글?x=%2B/검색"
        val url = toonkorSearchUrl("https://tkor138.com", query)
        assertEquals("/bbs/search.php", url.encodedPath)
        assertEquals(query, url.queryParameter("stx"))
        assertEquals(listOf("wr_subject||wr_content"), url.queryParameterValues("sfl"))
        assertEquals(setOf("stx", "sfl"), url.queryParameterNames)
        assertNull(url.fragment)
    }
}
