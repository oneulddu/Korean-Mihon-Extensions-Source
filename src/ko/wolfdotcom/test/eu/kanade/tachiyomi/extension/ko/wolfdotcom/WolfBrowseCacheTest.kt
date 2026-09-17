package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WolfBrowseCacheTest {
    @Test
    fun interleavedFiltersKeepTheirOwnSnapshots() {
        val cache = WolfBrowseCache<String>()
        val latest = (1..45).map { "latest$it" }
        val popular = (1..30).map { "popular$it" }
        cache.page("/browse?o=n", 1) { latest }
        cache.page("/browse?o=f&t2=3", 1) { popular }
        assertEquals(latest.subList(20, 40) to true, cache.page("/browse?o=n", 2) { error("unexpected load") })
        assertEquals(popular.subList(20, 30) to false, cache.page("/browse?o=f&t2=3", 2) { error("unexpected load") })
    }

    @Test
    fun uninitializedAndOutOfRangePagesReloadTheirOwnRequest() {
        val cache = WolfBrowseCache<Int>()
        var loads = 0
        assertEquals(
            (21..30).toList() to false,
            cache.page("a", 2) {
                loads++
                (1..30).toList()
            },
        )
        assertEquals(
            emptyList<Int>() to false,
            cache.page("a", 3) {
                loads++
                (1..30).toList()
            },
        )
        assertEquals(
            emptyList<Int>() to false,
            cache.page("a", Int.MAX_VALUE) {
                loads++
                (1..30).toList()
            },
        )
        assertEquals(3, loads)
    }

    @Test
    fun independentRequestsLoadConcurrentlyWithoutCrossContamination() {
        val cache = WolfBrowseCache<String>()
        val entered = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf("latest", "popular").map { key ->
                pool.submit(
                    Callable {
                        cache.page(key, 1) {
                            entered.countDown()
                            assertTrue(entered.await(5, TimeUnit.SECONDS))
                            (1..25).map { "$key$it" }
                        }
                    },
                )
            }
            assertEquals("latest1", tasks[0].get(10, TimeUnit.SECONDS).first.first())
            assertEquals("popular1", tasks[1].get(10, TimeUnit.SECONDS).first.first())
            assertEquals("latest21", cache.page("latest", 2) { error("unexpected load") }.first.first())
            assertEquals("popular21", cache.page("popular", 2) { error("unexpected load") }.first.first())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun loaderMutationsAndFailedRefreshDoNotChangePublishedSnapshot() {
        val cache = WolfBrowseCache<Int>()
        val input = (1..30).toMutableList()
        cache.page("a", 1) { input }
        input.clear()
        val failed = runCatching { cache.page("a", 1) { error("offline") } }
        assertTrue(failed.isFailure)
        val page = cache.page("a", 2) { error("unexpected load") }
        assertEquals((21..30).toList(), page.first)
        assertFalse(page.second)
    }
}
