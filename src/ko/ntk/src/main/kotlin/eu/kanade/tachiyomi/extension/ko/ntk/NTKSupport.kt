package eu.kanade.tachiyomi.extension.ko.ntk

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A late JavaScript callback must never revive a timed out or interrupted WebView. */
internal class WebViewResult<T> {
    private val latch = CountDownLatch(1)
    private var value: T? = null

    @Volatile
    var completed = false
        private set

    @Synchronized
    fun complete(value: T) {
        if (completed) return
        this.value = value
        completed = true
        latch.countDown()
    }

    fun await(timeout: Long, unit: TimeUnit, cleanup: () -> Unit): T? = try {
        latch.await(timeout, unit)
        synchronized(this) {
            completed = true
            value
        }
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("NTK WebView interrupted").apply { initCause(error) }
    } finally {
        synchronized(this) { completed = true }
        cleanup()
    }
}

internal fun <T> orderedImages(images: List<T>, page: (T) -> Int?): List<T> = if (images.all { page(it) != null }) images.sortedBy(page) else images

internal fun validImageUrl(baseUrl: HttpUrl, vararg candidates: String?): String? = candidates.firstNotNullOfOrNull { candidate ->
    candidate?.trim()?.takeIf(String::isNotEmpty)?.let(baseUrl::resolve)
        ?.takeIf { it.scheme == "http" || it.scheme == "https" }
        ?.toString()
}

internal fun validateEpisode(id: String, title: String, seenIds: MutableSet<String>) {
    if (
        id.isEmpty() || id == "null" || id == "true" || id == "false" || id == "." || id == ".." ||
        id.any { it in "/\\?#%" || it.isISOControl() || it.isWhitespace() } || !seenIds.add(id)
    ) {
        throw IOException("NTK episode API failed: invalid or duplicate episode id")
    }
    if (title.isBlank()) throw IOException("NTK episode API failed: missing episode title")
}

/** Try API conversion too: malformed IDs/titles should fall back just like malformed JSON. */
internal fun <T> episodeApiOrHtml(api: () -> List<T>?, html: () -> List<T>): List<T> = try {
    api()
} catch (error: IOException) {
    if (error is InterruptedIOException) throw error
    null
} ?: html()

internal fun requireCompleteChapters(expected: Int, actual: Int) {
    if (actual != expected) throw IOException("NTK chapter list incomplete: expected $expected, received $actual")
}

/** Reads array boundaries without counting brackets inside JSON strings. */
internal fun jsonArrayAt(text: String, start: Int): String? {
    if (text.getOrNull(start) != '[') return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val char = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
        } else {
            when (char) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> if (--depth == 0) return text.substring(start, index + 1)
            }
        }
    }
    return null
}

internal fun extractAllCards(scripts: List<String>, json: Json): String? {
    val flight = scripts.flatMap { script ->
        Regex("self\\.__next_f\\.push\\s*\\(").findAll(script).mapNotNull { match ->
            val start = match.range.last + 1
            val arrayStart = script.indexOfFirstFrom(start) { !it.isWhitespace() }
            val array = jsonArrayAt(script, arrayStart) ?: return@mapNotNull null
            runCatching {
                val parts = json.parseToJsonElement(array) as? kotlinx.serialization.json.JsonArray
                (parts?.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.getOrNull()
        }.toList()
    }.joinToString("")
    for (text in listOf(flight) + scripts) {
        // Scan actual JSON string tokens so a title containing 'allCards' cannot be a property.
        var index = 0
        while (index < text.length) {
            if (text[index] != '"') {
                index++
                continue
            }
            val start = index++
            var escaped = false
            while (index < text.length) {
                val char = text[index++]
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    break
                }
            }
            if (text.substring(start, index) != "\"allCards\"") continue
            var next = text.indexOfFirstFrom(index) { !it.isWhitespace() }
            if (text.getOrNull(next) != ':') continue
            next = text.indexOfFirstFrom(next + 1) { !it.isWhitespace() }
            jsonArrayAt(text, next)?.let { return it }
        }
    }
    return null
}

private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
    for (index in start until length) if (predicate(this[index])) return index
    return length
}
