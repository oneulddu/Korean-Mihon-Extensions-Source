package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import okhttp3.Call
import rx.Observable

/** Each request has its own lock and immutable list, so unrelated browses can load concurrently. */
internal class WolfBrowseCache<T> {
    private class Entry<T> {
        var items: List<T>? = null
    }

    private val entries = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)

    fun page(key: String, page: Int, load: () -> List<T>): Pair<List<T>, Boolean> {
        require(page >= 1) { "page must be positive" }
        val entry = synchronized(entries) {
            entries.getOrPut(key) { Entry() }.also {
                while (entries.size > 32) entries.remove(entries.keys.first())
            }
        }
        return synchronized(entry) {
            val start = (page.toLong() - 1) * 20
            val previous = entry.items
            val items = if (page == 1 || previous == null || start >= previous.size) {
                load().toList().also { entry.items = it }
            } else {
                previous
            }
            val from = start.coerceAtMost(items.size.toLong()).toInt()
            val end = (from + 20).coerceAtMost(items.size)
            items.subList(from, end) to (end < items.size)
        }
    }
}

/** A subscription owns its Call, including while it waits for a cached browse lock. */
internal fun <T> cancellableWolfCall(newCall: () -> Call, action: (Call) -> T): Observable<T> = Observable.defer {
    val call = newCall()
    Observable.fromCallable { action(call) }.doOnUnsubscribe { call.cancel() }
}
