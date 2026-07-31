package eu.kanade.tachiyomi.extension.ko.jjaptoon

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class JjaptoonTest {

    @Test
    fun chapterListParsesDateAndDateTime() {
        assertEquals(
            epochMillis(2026, 7, 29, 23, 25),
            parseJjaptoonChapterDate(
                listOf(
                    "2025-01-01에 시작된 이야기 183화",
                    "2026-07-29 23:25 무료",
                ),
            ),
        )
        assertEquals(
            epochMillis(2026, 7, 22, 0, 0),
            parseJjaptoonChapterDate(listOf("182화", "2026-07-22")),
        )
        assertEquals(0L, parseJjaptoonChapterDate(listOf("날짜 없음")))
    }

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long = LocalDateTime
        .of(year, month, day, hour, minute)
        .atZone(ZoneId.of("Asia/Seoul"))
        .toInstant()
        .toEpochMilli()
}
