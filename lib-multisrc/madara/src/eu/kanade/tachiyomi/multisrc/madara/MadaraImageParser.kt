package eu.kanade.tachiyomi.multisrc.madara

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Collections
import java.util.IdentityHashMap

internal fun selectMadaraPageImages(document: Document, selector: String): List<Element> {
    // Containers and their nested images may both match. Only the same DOM node
    // is redundant; separate images with the same URL can be intentional pages.
    val seen = Collections.newSetFromMap(IdentityHashMap<Element, Boolean>())
    return document.select(selector).flatMap { it.select("img") }.filter(seen::add)
}

internal fun resolveMadaraSrcSetImage(element: Element, image: String?): String? = image?.let {
    element.baseUri().toHttpUrlOrNull()?.resolve(it)?.toString()
        ?: it.toHttpUrlOrNull()?.toString()
}

internal fun selectMadaraSrcSetImage(srcset: String): String? {
    var remaining = srcset.trim()
    var bestUrl: String? = null
    var bestSize = 0.0
    while (remaining.isNotEmpty()) {
        remaining = remaining.trimStart { it.isWhitespace() || it == ',' }
        if (remaining.isEmpty()) break
        val url = remaining.takeWhile { !it.isWhitespace() }
        remaining = remaining.drop(url.length).trimStart()
        val descriptor = if (url.endsWith(',')) {
            ""
        } else {
            remaining.substringBefore(',').also {
                remaining = remaining.drop(it.length).trimStart(',')
            }.trim()
        }
        val size = when {
            descriptor.isEmpty() -> 1.0
            descriptor.endsWith('w') -> descriptor.dropLast(1).toIntOrNull()?.toDouble()
            descriptor.endsWith('x') -> descriptor.dropLast(1).toDoubleOrNull()
            else -> null
        } ?: continue
        if (size.isFinite() && size > bestSize) {
            bestSize = size
            bestUrl = url.trimEnd(',')
        }
    }
    return bestUrl
}
