package eu.kanade.tachiyomi.extension.ko.blacktoon

import eu.kanade.tachiyomi.source.model.FilterList
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal data class BlacktoonSelection(
    val query: String = "",
    val order: Int = -1,
    val status: Int = -1,
    val platform: Int = -1,
    val day: Int = -1,
    val includedTags: List<Int> = emptyList(),
    val excludedTags: List<Int> = emptyList(),
) {
    fun matches(item: SeriesItem): Boolean = (query.isEmpty() || item.name.contains(query, true) || item.author.contains(query, true)) &&
        (status == -1 || item.listIndex == status) &&
        (platform == -1 || item.platform == platform) &&
        (day == -1 || item.publishDay == day) &&
        (
            (includedTags.isEmpty() && excludedTags.isEmpty()) || item.tag.let { tags ->
                includedTags.all { it in tags } && excludedTags.none { it in tags }
            }
            )

    companion object {
        fun from(query: String, filters: FilterList): BlacktoonSelection {
            val tags = filters.filterIsInstance<TagFilter>().firstOrNull()?.state.orEmpty()
            return BlacktoonSelection(
                query = query.trim().lowercase(Locale.ROOT),
                order = filters.filterIsInstance<Order>().firstOrNull()?.selected ?: -1,
                status = filters.filterIsInstance<Status>().firstOrNull()?.selected ?: -1,
                platform = filters.filterIsInstance<PlatformFilter>().firstOrNull()?.selected ?: -1,
                day = filters.filterIsInstance<PublishDayFilter>().firstOrNull()?.selected ?: -1,
                includedTags = tags.filter { it.isIncluded() }.map { it.id },
                excludedTags = tags.filter { it.isExcluded() }.map { it.id },
            )
        }
    }
}

/** Page one may refresh the catalog; later pages retain the selected catalog and ordering. */
internal class BlacktoonCatalog(
    private val load: () -> List<SeriesItem>,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TimeUnit.MINUTES.toMillis(15),
    private val retryMs: Long = TimeUnit.MINUTES.toMillis(15),
) {
    private class Snapshot(val items: List<SeriesItem>, val fetchedAt: Long) {
        val popular by lazy { items.sortedByDescending { it.hot } }
        val latest by lazy { items.sortedByDescending { it.updatedAt } }
    }

    private data class SelectionSnapshot(val catalog: Snapshot, val items: List<SeriesItem>)

    private var snapshot: Snapshot? = null
    private var failedAt: Long? = null
    private val selections = LinkedHashMap<BlacktoonSelection, SelectionSnapshot>(16, 0.75f, true)

    @Synchronized
    fun items(): List<SeriesItem> = catalog(refresh = false).items

    @Synchronized
    fun page(page: Int, selection: BlacktoonSelection): Pair<List<SeriesItem>, Boolean> {
        require(page >= 1) { "page must be positive" }
        val selected = if (page > 1) selections[selection] else null
        val items = selected?.items ?: run {
            val current = catalog(refresh = page == 1)
            selections[selection]?.takeIf { it.catalog === current }?.items ?: run {
                val ordered = when (selection.order) {
                    0 -> current.latest
                    1 -> current.popular
                    else -> current.items
                }
                val filtered = ordered.filter(selection::matches)
                selections[selection] = SelectionSnapshot(current, filtered)
                while (selections.size > 32) selections.remove(selections.keys.first())
                filtered
            }
        }
        val start = ((page.toLong() - 1) * 24).coerceAtMost(items.size.toLong()).toInt()
        val end = (start + 24).coerceAtMost(items.size)
        return items.subList(start, end) to (end < items.size)
    }

    private fun catalog(refresh: Boolean): Snapshot {
        val previous = snapshot
        val time = now()
        if (previous != null && (!refresh || time - previous.fetchedAt in 0 until ttlMs)) return previous
        if (previous != null && failedAt?.let { time - it in 0 until retryMs } == true) return previous
        return try {
            val items = load().toList()
            check(items.isNotEmpty()) { "webtoon catalog is empty" }
            Snapshot(items, now()).also {
                snapshot = it
                failedAt = null
            }
        } catch (error: Exception) {
            error.rethrowIfBlacktoonCancelled()
            failedAt = now()
            previous ?: throw error
        }
    }
}

internal fun String.toBlacktoonImageUrl(baseUrl: String, cdnUrl: String): String = when {
    startsWith("https://") || startsWith("http://") -> this
    startsWith("//") -> "https:$this"
    startsWith("/") -> baseUrl.trimEnd('/') + this
    else -> cdnUrl + this
}

internal fun Throwable.rethrowIfBlacktoonCancelled() {
    if (Thread.currentThread().isInterrupted) throw this
    val seen = HashSet<Throwable>()
    var cause: Throwable? = this
    while (cause != null && seen.add(cause)) {
        if (cause is InterruptedException) {
            Thread.currentThread().interrupt()
            throw this
        }
        if (cause is CancellationException ||
            (cause is InterruptedIOException && cause !is SocketTimeoutException) ||
            (cause is IOException && cause.message.equals("Canceled", ignoreCase = true))
        ) {
            throw this
        }
        cause = cause.cause
    }
}
