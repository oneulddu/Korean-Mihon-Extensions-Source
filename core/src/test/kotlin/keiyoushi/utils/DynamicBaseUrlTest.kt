package keiyoushi.utils

import okhttp3.HttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class DynamicBaseUrlTest {
    private val keys = BaseUrlCacheKeys("cached", "fetched", "attempted")

    @Test
    fun normalizeBaseUrlAcceptsOnlySafeHttpsRootUrls() {
        assertEquals("https://example.com", normalizeBaseUrl(" https://example.com/ "))
        assertNull(normalizeBaseUrl("http://example.com"))
        assertNull(normalizeBaseUrl("https://example.com/path"))
        assertNull(normalizeBaseUrl("https://example.com/?query=1"))
        assertNull(normalizeBaseUrl("https://user:pass@example.com"))
        assertNull(normalizeBaseUrl("https://example.com/#fragment"))
    }

    @Test
    fun automaticUrlMustMatchSitePolicy() {
        val allowed: (HttpUrl) -> Boolean = { it.host.matches(Regex("site\\d+\\.com")) }

        assertEquals("https://site123.com", normalizeBaseUrl("https://site123.com", allowed))
        assertNull(normalizeBaseUrl("https://other.com", allowed))
    }

    @Test
    fun freshCacheSkipsNetworkDiscovery() {
        val store = FakeStorage(strings = mutableMapOf("cached" to "https://site2.com"), longs = mutableMapOf("fetched" to 900L))
        var discoveryCalls = 0
        val resolver = resolver(store, now = { 1_000L }, discover = {
            discoveryCalls += 1
            "https://site3.com"
        })

        assertEquals("https://site2.com", resolver.resolve())
        assertEquals(0, discoveryCalls)
    }

    @Test
    fun discoveryWinsAndUpdatesCache() {
        val store = FakeStorage()
        val resolver = resolver(store, now = { 1_000_000L }, discover = { "https://site3.com/" })

        assertEquals("https://site3.com", resolver.resolve())
        assertEquals("https://site3.com", store.strings["cached"])
        assertEquals(1_000_000L, store.longs["fetched"])
    }

    @Test
    fun redirectIsUsedAfterDiscoveryFailure() {
        val store = FakeStorage()
        val resolver = resolver(store, now = { 1_000_000L }, discover = { null }, redirect = { "https://site4.com" })

        assertEquals("https://site4.com", resolver.resolve())
    }

    @Test
    fun staleCacheSurvivesTemporaryFailures() {
        val store = FakeStorage(strings = mutableMapOf("cached" to "https://site2.com"))
        val resolver = resolver(store, now = { 1_000_000L }, discover = { null }, redirect = { null })

        assertEquals("https://site2.com", resolver.resolve())
    }

    @Test
    fun retryDelayPreventsRepeatedFailedDiscovery() {
        val store = FakeStorage(longs = mutableMapOf("attempted" to 950_000L))
        var discoveryCalls = 0
        val resolver = resolver(store, now = { 1_000_000L }, discover = {
            discoveryCalls += 1
            "https://site3.com"
        })

        assertEquals("https://site1.com", resolver.resolve())
        assertEquals(0, discoveryCalls)
    }

    @Test
    fun rewriteUpdatesSiteUrlRefererAndOriginOnly() {
        val request = Request.Builder()
            .url("https://site1.com/chapter/1")
            .header("Referer", "https://site1.com/title/1")
            .header("Origin", "https://site1.com/")
            .build()
        val rewritten = request.rewriteBaseUrl("https://site9.com") { it.matches(Regex("site\\d+\\.com")) }

        assertEquals("https://site9.com/chapter/1", rewritten.url.toString())
        assertEquals("https://site9.com/title/1", rewritten.header("Referer"))
        assertEquals("https://site9.com", rewritten.header("Origin"))

        val cdnRequest = Request.Builder().url("https://cdn.example.com/image.jpg").build()
        assertTrue(cdnRequest === cdnRequest.rewriteBaseUrl("https://site9.com") { it.matches(Regex("site\\d+\\.com")) })
    }

    @Test
    fun numberedDomainCacheIsInvalidatedOnlyWhenOlderThanNewDefault() {
        val hostRegex = Regex("site(\\d+)\\.com")

        assertTrue(shouldInvalidateNumberedDomainCache("https://site425.com", "425", 426, hostRegex))
        assertEquals(false, shouldInvalidateNumberedDomainCache("https://site426.com", "425", 426, hostRegex))
        assertEquals(false, shouldInvalidateNumberedDomainCache("https://site427.com", "425", 426, hostRegex))
        assertEquals(false, shouldInvalidateNumberedDomainCache(null, "427", 426, hostRegex))
        assertTrue(shouldInvalidateNumberedDomainCache(null, null, 426, hostRegex))
    }

    @Test
    fun concurrentRequestsWaitForTheSameFreshAddress() {
        val store = FakeStorage(strings = ConcurrentHashMap(mapOf("cached" to "https://site2.com")), longs = ConcurrentHashMap())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val resolver = resolver(store, now = { 100_000_000L }, discover = {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            "https://site3.com"
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<String> { resolver.resolve() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<String> { resolver.resolve() }
            try {
                second.get(100, TimeUnit.MILLISECONDS)
                throw AssertionError("Concurrent request returned before discovery completed")
            } catch (_: TimeoutException) {
                // Both requests must use the discovery result instead of the stale address.
            }
            release.countDown()
            assertEquals("https://site3.com", first.get(5, TimeUnit.SECONDS))
            assertEquals("https://site3.com", second.get(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun invalidationDoesNotWaitForNetworkOrRestoreItsOldResult() {
        val store = FakeStorage(strings = ConcurrentHashMap(), longs = ConcurrentHashMap())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val resolver = resolver(store, now = { 100_000_000L }, discover = {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "https://site2.com"
            } else {
                "https://site3.com"
            }
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<String> { resolver.resolve() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            executor.submit { resolver.clearCache() }.get(1, TimeUnit.SECONDS)
            assertNull(store.getString("cached"))
            release.countDown()
            assertEquals("https://site3.com", first.get(5, TimeUnit.SECONDS))
            assertEquals("https://site3.com", store.getString("cached"))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun ioFailureStillUsesRedirectAndPreservesLastGoodCache() {
        val store = FakeStorage(strings = mutableMapOf("cached" to "https://site2.com"))
        val resolver = resolver(store, now = { 100_000_000L }, discover = { throw IOException("offline") }, redirect = { "https://site4.com" })
        assertEquals("https://site4.com", resolver.resolve())
        resolver.clearCache()
        store.putString("cached", "https://site4.com")
        val offline = resolver(store, now = { 200_000_000L }, discover = { throw IOException("offline") }, redirect = { throw IOException("offline") })
        assertEquals("https://site4.com", offline.resolve())
    }

    @Test
    fun clockRollbackDoesNotExtendExpiredCache() {
        val store = FakeStorage(
            strings = mutableMapOf("cached" to "https://site2.com"),
            longs = mutableMapOf("fetched" to 200_000_000L, "attempted" to 200_000_000L),
        )
        assertEquals("https://site3.com", resolver(store, now = { 100_000_000L }, discover = { "https://site3.com" }).resolve())
    }

    @Test
    fun successfulRequestRedirectUpdatesCacheButLateOrExternalResponsesCannotRevertIt() {
        val store = FakeStorage(strings = mutableMapOf("cached" to "https://site2.com"))
        val resolver = resolver(store, now = { 100_000_000L }, discover = { null })
        assertTrue(resolver.recordRedirect("https://site2.com", "https://site3.com"))
        assertEquals("https://site3.com", resolver.resolve())
        assertEquals(false, resolver.recordRedirect("https://site2.com", "https://site1.com"))
        assertEquals(false, resolver.recordRedirect("https://site3.com", "https://cdn.example.com"))
        assertEquals("https://site3.com", resolver.resolve())
    }

    private fun resolver(
        store: FakeStorage,
        now: () -> Long,
        discover: () -> String?,
        redirect: () -> String? = { null },
    ) = DynamicBaseUrlResolver(
        storage = store,
        keys = keys,
        fallbackBaseUrl = { "https://site1.com" },
        isAllowedAutomaticUrl = { it.host.matches(Regex("site\\d+\\.com")) },
        discoverBaseUrl = discover,
        redirectBaseUrl = redirect,
        now = now,
    )

    private class FakeStorage(
        val strings: MutableMap<String, String> = mutableMapOf(),
        val longs: MutableMap<String, Long> = mutableMapOf(),
    ) : BaseUrlStorage {
        override fun getString(key: String): String? = strings[key]
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun getLong(key: String): Long = longs[key] ?: 0L
        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }

        override fun remove(vararg keys: String) {
            keys.forEach {
                strings.remove(it)
                longs.remove(it)
            }
        }
    }
}
