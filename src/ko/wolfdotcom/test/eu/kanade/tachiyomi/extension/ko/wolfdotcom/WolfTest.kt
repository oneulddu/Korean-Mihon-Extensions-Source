package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import org.junit.Assert.assertEquals
import org.junit.Test

class WolfTest {

    @Test
    fun latestDomainParsesOfficialAbsoluteAndProtocolRelativeLinks() {
        assertEquals(
            "https://wfwf435.com",
            parseWolfLatestBaseUrl(
                """<a href="https://wfwf435.com/">늑대닷컴</a>""",
                "https://a14c.com/",
            ),
        )
        assertEquals(
            "https://wfwf436.com",
            parseWolfLatestBaseUrl(
                """<a href="//wfwf436.com/">최신 주소</a>""",
                "https://a14c.com/",
            ),
        )
    }

    @Test
    fun latestDomainRejectsUnrelatedHosts() {
        assertEquals(
            null,
            parseWolfLatestBaseUrl(
                """<a href="https://wftoon222.com">웹툰</a>""",
                "https://a14c.com/",
            ),
        )
        assertEquals(
            null,
            parseWolfLatestBaseUrl(
                """<a href="https://wfwf435.com.evil.example">가짜 주소</a>""",
                "https://a14c.com/",
            ),
        )
    }

    @Test
    fun guideValidatesWholeUrlsBeforeUpgradingHttp() {
        listOf(
            "https://user@wfwf501.com",
            "https://wfwf501.com@evil.example",
            "https://wfwf501.com:8443",
            "https://wfwf501.com/path",
            "https://wfwf501.com/?next=bad",
            "https://wfwf501.com/#bad",
            "https://wfwf501.com.evil.example",
            "http://wfwf501.com:8080",
        ).forEach {
            assertEquals(it, null, parseWolfLatestBaseUrl("<a href='$it'>$it</a>", "https://guide.example/"))
        }
        assertEquals("https://wfwf501.com", parseWolfLatestBaseUrl("<a href='http://wfwf501.com/'>이동</a>", "https://guide.example/"))
    }

    @Test
    fun onlyExplicitMigrationButtonChangesContentOrigin() {
        assertEquals("https://wfwf501.com", parseWolfMigrationPage("<a class='main-btn' href='https://wfwf501.com/'>이동</a>", "https://wfwf494.com"))
        assertEquals(null, parseWolfMigrationPage("<a href='https://wfwf501.com/'>광고</a>", "https://wfwf494.com"))
        assertEquals(null, parseWolfMigrationPage("<a class='main-btn' href='https://wfwf501.com:8443/'>이동</a>", "https://wfwf494.com"))
    }
}
