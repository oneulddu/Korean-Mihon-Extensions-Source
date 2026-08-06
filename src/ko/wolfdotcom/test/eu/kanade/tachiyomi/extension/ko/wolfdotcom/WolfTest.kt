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
}
