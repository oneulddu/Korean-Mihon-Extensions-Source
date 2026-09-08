package eu.kanade.tachiyomi.extension.ko.blacktoon

import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BlacktoonCatalogTest {
    private fun series(prefix: String) = (1..60).map {
        SeriesItem("$prefix$it", "$prefix title $it", hot = it, updatedAt = (61 - it).toLong())
    }

    @Test
    fun refreshDoesNotReplaceLaterPagesOfAnotherSelection() {
        var time = 0L
        var loads = 0
        val cache = BlacktoonCatalog({ series(if (++loads == 1) "old" else "new") }, { time }, ttlMs = 100)
        val popular = BlacktoonSelection(order = 1)
        val latest = BlacktoonSelection(order = 0)
        assertEquals("old60", cache.page(1, popular).first.first().id)
        time = 101
        assertEquals("new1", cache.page(1, latest).first.first().id)
        assertEquals("old36", cache.page(2, popular).first.first().id)
        assertEquals("new25", cache.page(2, latest).first.first().id)
        assertEquals("new60", cache.page(1, popular).first.first().id)
        assertEquals(2, loads)
    }

    @Test
    fun refreshFailureKeepsLastGoodSnapshotAndThrottlesRetry() {
        var time = 0L
        var loads = 0
        val cache = BlacktoonCatalog(
            { if (++loads == 2) error("temporary outage") else series("v$loads") },
            { time },
            ttlMs = 100,
            retryMs = 20,
        )
        val selection = BlacktoonSelection()
        val before = cache.page(1, selection)
        time = 101
        assertEquals(before, cache.page(1, selection))
        time = 110
        assertEquals(before, cache.page(1, selection))
        assertEquals(2, loads)
        time = 122
        assertEquals("v31", cache.page(1, selection).first.first().id)
    }

    @Test
    fun defaultContentTtlRefreshesAtFifteenMinutesOnlyOnPageOne() {
        var time = 0L
        var loads = 0
        val cache = BlacktoonCatalog({ series("v${++loads}-") }, { time })
        val selection = BlacktoonSelection()
        cache.page(1, selection)
        time = TimeUnit.MINUTES.toMillis(14)
        assertEquals("v1-1", cache.page(1, selection).first.first().id)
        time = TimeUnit.MINUTES.toMillis(15)
        assertEquals("v1-25", cache.page(2, selection).first.first().id)
        assertEquals("v2-1", cache.page(1, selection).first.first().id)
        assertEquals(2, loads)
    }

    @Test
    fun cancelledRefreshPropagatesWithoutStartingFailureBackoff() {
        val errors = listOf(
            InterruptedException("interrupted"),
            CancellationException("cancelled"),
            InterruptedIOException("interrupted"),
            IOException("Canceled"),
            IOException("wrapped interruption", InterruptedException("interrupted")),
        )
        errors.forEach { failure ->
            var time = 0L
            var loads = 0
            val cache = BlacktoonCatalog(
                { if (++loads == 2) throw failure else series("v$loads-") },
                { time },
                ttlMs = 10,
            )
            val selection = BlacktoonSelection()
            cache.page(1, selection)
            time = 11
            try {
                assertSame(failure, runCatching { cache.page(1, selection) }.exceptionOrNull())
                if (failure is InterruptedException || failure.cause is InterruptedException) {
                    assertTrue(Thread.currentThread().isInterrupted)
                }
            } finally {
                Thread.interrupted()
            }
            assertEquals("v1-1", cache.items().first().id)
            assertEquals("v3-1", cache.page(1, selection).first.first().id)
        }
    }

    @Test
    fun ioFailureOnInterruptedThreadCannotReturnStaleSnapshot() {
        var time = 0L
        val failure = IOException("connection closed")
        val cache = BlacktoonCatalog(
            {
                if (time > 0) {
                    Thread.currentThread().interrupt()
                    throw failure
                }
                series("old")
            },
            { time },
            ttlMs = 10,
        )
        cache.page(1, BlacktoonSelection())
        time = 11
        try {
            assertSame(failure, runCatching { cache.page(1, BlacktoonSelection()) }.exceptionOrNull())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun ordinarySocketTimeoutStillKeepsLastGoodSnapshot() {
        var time = 0L
        val cache = BlacktoonCatalog(
            { if (time > 0) throw SocketTimeoutException("read timed out") else series("old") },
            { time },
            ttlMs = 10,
        )
        val before = cache.page(1, BlacktoonSelection())
        time = 11
        assertEquals(before, cache.page(1, BlacktoonSelection()))
    }

    @Test
    fun coldMetadataLoadPropagatesInterruptionAndRestoresInterruptFlag() {
        val failure = InterruptedException("interrupted")
        val cache = BlacktoonCatalog({ throw failure })
        try {
            assertSame(failure, runCatching { cache.items() }.exceptionOrNull())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun emptyRefreshCannotEraseCatalog() {
        var time = 0L
        val cache = BlacktoonCatalog({ if (time == 0L) series("old") else emptyList() }, { time }, ttlMs = 10)
        val before = cache.page(1, BlacktoonSelection())
        time = 11
        assertEquals(before, cache.page(1, BlacktoonSelection()))
    }

    @Test
    fun laterPageDoesNotRefreshExpiredCatalogAndBoundsAreSafe() {
        var time = 0L
        var loads = 0
        val cache = BlacktoonCatalog({
            loads++
            series("a")
        }, { time }, ttlMs = 10)
        assertTrue(cache.page(1, BlacktoonSelection()).second)
        time = 11
        assertEquals(12, cache.page(3, BlacktoonSelection()).first.size)
        assertFalse(cache.page(3, BlacktoonSelection()).second)
        assertEquals(emptyList<SeriesItem>() to false, cache.page(Int.MAX_VALUE, BlacktoonSelection()))
        assertEquals(1, loads)
    }

    @Test
    fun selectionCapturesFiltersAndSeparatesQueries() {
        val order = Order()
        val filters = FilterList(order)
        val first = BlacktoonSelection.from("  OLD ", filters)
        order.state = 1
        assertEquals(0, first.order)
        assertEquals("old", first.query)
        val cache = BlacktoonCatalog({ series("old") + series("new") })
        assertEquals("old1", cache.page(1, first).first.first().id)
        assertEquals("new60", cache.page(1, BlacktoonSelection.from("new", filters)).first.first().id)
        assertEquals("old25", cache.page(2, first).first.first().id)
    }

    @Test
    fun concurrentInitialRequestsShareOneCatalogLoad() {
        val loads = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val cache = BlacktoonCatalog({
            loads.incrementAndGet()
            series("a")
        })
        try {
            val tasks = (1..8).map { index ->
                pool.submit(
                    Callable {
                        start.await()
                        cache.page(1, BlacktoonSelection(order = index % 2)).first.size
                    },
                )
            }
            start.countDown()
            tasks.forEach { assertEquals(24, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, loads.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun relativeImagesUseProvidedActiveBaseUrl() {
        assertEquals("https://blacktoon416.com/pages/1.jpg", "/pages/1.jpg".toBlacktoonImageUrl("https://blacktoon416.com", "https://cdn.example/"))
        assertEquals("https://manual.example/pages/1.jpg", "/pages/1.jpg".toBlacktoonImageUrl("https://manual.example/", "https://cdn.example/"))
        assertEquals("https://cdn.example/1.jpg", "//cdn.example/1.jpg".toBlacktoonImageUrl("https://manual.example", "https://cdn.example/"))
    }

    @Test
    fun chapterDatesRemainStableUnderConcurrentParsing() {
        val pool = Executors.newFixedThreadPool(8)
        val dates = listOf("2026-01-02", "2025-12-31", "2024-02-29", "bad")
        val expected = dates.associateWith { parseBlacktoonChapterDate(it) }
        try {
            val tasks = (1..800).map { index ->
                pool.submit(
                    Callable {
                        val date = dates[index % dates.size]
                        assertEquals(expected[date], parseBlacktoonChapterDate(date))
                    },
                )
            }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
