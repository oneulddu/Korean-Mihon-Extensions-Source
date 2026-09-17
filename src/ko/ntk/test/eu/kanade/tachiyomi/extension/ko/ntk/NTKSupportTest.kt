package eu.kanade.tachiyomi.extension.ko.ntk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NTKSupportTest {
    @Test
    fun signingKeyCannotBeReusedAfterDomainOrFingerprintChanges() {
        val key = ClientSigningKey(
            keyId = "key-id",
            origin = "https://sbxh9.com",
            fingerprint = "ntk_fp=first",
            privateKey = KeyPairGenerator.getInstance("EC").generateKeyPair().private,
            expiresAt = 10_000L,
            serverTimeOffsetMs = 0L,
        )
        assertTrue(key.isValidFor("https://sbxh9.com", "ntk_fp=first", 1_000L, 100L))
        assertFalse(key.isValidFor("https://sbxh12.com", "ntk_fp=first", 1_000L, 100L))
        assertFalse(key.isValidFor("https://sbxh9.com", "ntk_fp=second", 1_000L, 100L))
        assertFalse(key.isValidFor("https://sbxh9.com", null, 1_000L, 100L))
        assertFalse(key.isValidFor("https://sbxh9.com", "ntk_fp=first", 9_900L, 100L))
    }

    @Test
    fun completedWebViewCleansUpAndIgnoresLaterCallbacks() {
        val result = WebViewResult<String>()
        val cleanups = AtomicInteger()
        result.complete("first")
        result.complete("second")
        assertEquals("first", result.await(1, TimeUnit.SECONDS) { cleanups.incrementAndGet() })
        assertTrue(result.completed)
        assertEquals(1, cleanups.get())
    }

    @Test
    fun timedOutWebViewRejectsLatePayload() {
        val result = WebViewResult<String>()
        val cleanups = AtomicInteger()
        assertNull(result.await(0, TimeUnit.MILLISECONDS) { cleanups.incrementAndGet() })
        result.complete("late payload")
        assertTrue(result.completed)
        assertNull(result.await(0, TimeUnit.MILLISECONDS) {})
        assertEquals(1, cleanups.get())
    }

    @Test
    fun interruptedWebViewClosesAndPreservesInterruption() {
        val result = WebViewResult<String>()
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val cleanups = AtomicInteger()
        val thread = Thread {
            try {
                started.countDown()
                assertThrows(InterruptedIOException::class.java) {
                    result.await(10, TimeUnit.SECONDS) { cleanups.incrementAndGet() }
                }
                assertTrue(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        thread.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        thread.interrupt()
        thread.join(2_000)
        assertFalse(thread.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertTrue(result.completed)
        assertEquals(1, cleanups.get())
    }

    @Test
    fun partialPageNumbersKeepOriginalOrderAndEqualPagesAreStable() {
        val partial = listOf(2 to "z", null to "a", 1 to "b")
        assertEquals(partial, orderedImages(partial) { it.first })
        val numbered = listOf(2 to "z", 1 to "c", 2 to "a", 1 to "b")
        assertEquals(listOf(1 to "c", 1 to "b", 2 to "z", 2 to "a"), orderedImages(numbered) { it.first })
    }

    @Test
    fun invalidImageSourcesFallBackToValidatedLazyUrl() {
        val base = "https://sbxh9.com/manhwa/work/episode".toHttpUrl()
        assertEquals("https://cdn.example/1.webp", validImageUrl(base, "data:image/gif;base64,x", "blob:https://sbxh9.com/id", "//cdn.example/1.webp"))
        assertEquals("https://sbxh9.com/images/2.webp", validImageUrl(base, "", "/images/2.webp"))
        assertNull(validImageUrl(base, " ", "javascript:alert(1)", "data:image/png,x"))
    }

    @Test
    fun invalidDuplicateAndUntitledEpisodesUseHtmlFallback() {
        for ((id, title, seen) in listOf(
            Triple("../bad", "bad", mutableSetOf<String>()),
            Triple("%2e%2e", "bad", mutableSetOf<String>()),
            Triple("null", "bad", mutableSetOf<String>()),
            Triple("123", "duplicate", mutableSetOf("123")),
            Triple("123", " ", mutableSetOf<String>()),
        )) {
            assertEquals(
                listOf("complete HTML"),
                episodeApiOrHtml(
                    api = {
                        validateEpisode(id, title, seen)
                        listOf("invalid API")
                    },
                    html = { listOf("complete HTML") },
                ),
            )
        }
    }

    @Test
    fun incompleteHtmlMustRemainAnErrorAfterApiFailure() {
        assertThrows(IOException::class.java) {
            episodeApiOrHtml<String>(
                api = { throw IOException("API unavailable") },
                html = {
                    requireCompleteChapters(250, 100)
                    listOf("partial")
                },
            )
        }
        assertThrows(IOException::class.java) { requireCompleteChapters(3, 0) }
        assertThrows(IOException::class.java) { requireCompleteChapters(3, 4) }
        requireCompleteChapters(250, 250)
    }

    @Test
    fun latestFlightHandlesBracketsEscapesAndSplitChunks() {
        val cards = """[{"sourceWorkId":"1","title":"닫힘 ] [ 와 \\\"인용\\\"", "tags":["[", "]"]},{"sourceWorkId":"2","title":"\\\\ 경로"}]"""
        val payload = "a:[\"allCards inside title\",{\"allCards\" : $cards}]\n"
        val split = payload.length / 2
        val scripts = listOf(
            "self.__next_f.push([1,${JsonPrimitive(payload.take(split))}]);",
            "self.__next_f.push([1,${JsonPrimitive(payload.drop(split))}]);",
        )
        assertEquals(cards, extractAllCards(scripts, Json))
        Json.parseToJsonElement(cards)
    }

    @Test
    fun latestIgnoresPropertyTextInsideTitlesAndTruncatedArrays() {
        assertEquals("[]", extractAllCards(listOf("""{"title":"\"allCards\": [broken]", "allCards": []}"""), Json))
        assertNull(extractAllCards(listOf("""{"allCards":[{"title":"]"}"""), Json))
    }
}
