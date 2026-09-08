package eu.kanade.tachiyomi.extension.ko.ntk

import keiyoushi.utils.BaseUrlStorage
import keiyoushi.utils.rewriteBaseUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NTKDomainTest {
    private class Storage : BaseUrlStorage {
        private val values = ConcurrentHashMap<String, Any>()
        override fun getString(key: String): String? = values[key] as? String
        override fun putString(key: String, value: String) {
            values[key] = value
        }
        override fun getLong(key: String): Long = values[key] as? Long ?: 0L
        override fun putLong(key: String, value: Long) {
            values[key] = value
        }
        override fun remove(vararg keys: String) {
            keys.forEach(values::remove)
        }
    }

    @Test
    fun successfulRedirectRecoversAfterDiscoveryFailureAndCannotReplaceManualSettings() {
        val domain = NTKDomain(Storage()) { null }
        assertEquals(NTKDomain.DEFAULT_URL, domain.resolve())
        assertTrue(domain.recordRedirect(NTKDomain.DEFAULT_URL, "https://sbxh12.com"))
        assertEquals("https://sbxh12.com", domain.resolve())
        assertFalse(domain.recordRedirect(NTKDomain.DEFAULT_URL, "https://sbxh8.com"))
        domain.setManual("https://manual.example")
        assertFalse(domain.recordRedirect("https://sbxh12.com", "https://sbxh13.com"))
        assertEquals("https://manual.example", domain.resolve())
    }

    @Test
    fun rootReadsNeverDiscoverAndLegacyValuesBecomeAutomaticOnly() {
        val storage = Storage().apply { putString("pref_domain_key", "0012") }
        val domain = NTKDomain(storage) { error("Getter must not perform I/O") }
        assertEquals("https://sbxh12.com", domain.currentUrl())
        assertNull(domain.manualUrl())
        assertNull(storage.getString("pref_domain_key"))
    }

    @Test
    fun previousBuildDefaultMigratesWithoutCreatingManualPreference() {
        val storage = Storage().apply {
            putString("pref_domain_key", "3")
            putString("pref_domain_default_key", "3")
        }
        val domain = NTKDomain(storage) { null }
        assertEquals(NTKDomain.DEFAULT_URL, domain.currentUrl())
        assertNull(domain.manualUrl())
    }

    @Test
    fun manualFullUrlValidationAndReturningToAutomatic() {
        val domain = NTKDomain(Storage()) { error("Manual URL must bypass discovery") }
        for (value in listOf("12", "http://manual.example", "https://manual.example:444", "https://manual.example/a", "https://manual.example/?x=1", "https://user@manual.example")) {
            assertFalse(value, domain.setManual(value))
        }
        assertTrue(domain.setManual(" https://manual.example/ "))
        assertEquals("https://manual.example", domain.resolve())
        assertTrue(domain.setManual(""))
        assertEquals(NTKDomain.DEFAULT_URL, domain.currentUrl())
    }

    @Test
    fun manualEditWinsOverInFlightAutomaticDiscovery() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resolved = AtomicReference<String>()
        val failure = AtomicReference<Throwable>()
        val domain = NTKDomain(Storage()) {
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            "https://sbxh12.com"
        }
        val worker = Thread {
            try {
                resolved.set(domain.resolve())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(domain.setManual("https://manual.example"))
        } finally {
            release.countDown()
            worker.join(3_000)
        }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertEquals("https://manual.example", resolved.get())
        assertEquals("https://manual.example", domain.currentUrl())
        assertTrue(domain.setManual(""))
        assertEquals("https://sbxh12.com", domain.currentUrl())
    }

    @Test
    fun failedDiscoveryKeepsStaleCacheAndTriesCurrentThenDefault() {
        val storage = Storage().apply { putString(NTKDomain.CACHE_KEY, "https://sbxh12.com") }
        val domain = NTKDomain(storage) { candidates ->
            assertEquals(listOf("https://sbxh12.com", NTKDomain.DEFAULT_URL), candidates)
            null
        }
        assertEquals("https://sbxh12.com", domain.resolve())
        assertEquals("https://sbxh12.com", domain.currentUrl())
    }

    @Test
    fun onlyThePreviousManualHostIsKeptForFifteenMinutes() {
        var now = 100_000L
        val domain = NTKDomain(Storage(), now = { now }) { null }
        domain.setManual("https://first.example")
        domain.setManual("https://second.example")
        assertTrue(domain.ownsHost("first.example"))
        domain.setManual("https://third.example")
        assertFalse(domain.ownsHost("first.example"))
        assertTrue(domain.ownsHost("second.example"))
        now += 15 * 60 * 1000L
        assertFalse(domain.ownsHost("second.example"))
        assertTrue(domain.ownsHost("third.example"))
    }

    @Test
    fun requestRewritingPreservesSavedPathsAndLeavesCdnAlone() {
        val domain = NTKDomain(Storage()) { null }
        domain.setManual("https://manual.example")
        val request = Request.Builder().url("http://sbxh3.com:8080/manhwa/123/456?x=1")
            .header("Referer", "https://sbxh3.com/manhwa/123/456")
            .header("Origin", "https://sbxh3.com").build()
        val rewritten = request.rewriteBaseUrl(domain.currentUrl(), domain::ownsHost)
        assertEquals("https://manual.example/manhwa/123/456?x=1", rewritten.url.toString())
        assertEquals("https://manual.example/manhwa/123/456", rewritten.header("Referer"))
        val cdn = Request.Builder().url("https://cdn.example/sbxh3.com/image.jpg").build()
        assertSame(cdn, cdn.rewriteBaseUrl(domain.currentUrl(), domain::ownsHost))
        assertFalse(domain.ownsHost("sbxh3.com.evil.example"))
    }
}
