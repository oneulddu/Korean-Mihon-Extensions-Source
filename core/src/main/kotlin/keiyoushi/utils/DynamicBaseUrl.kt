package keiyoushi.utils

import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

interface BaseUrlStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getLong(key: String): Long
    fun putLong(key: String, value: Long)
    fun remove(vararg keys: String)
}

class SharedPreferencesBaseUrlStorage(
    private val preferences: SharedPreferences,
) : BaseUrlStorage {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun getLong(key: String): Long = preferences.getLong(key, 0L)

    override fun putLong(key: String, value: Long) {
        preferences.edit().putLong(key, value).apply()
    }

    override fun remove(vararg keys: String) {
        preferences.edit().apply {
            keys.forEach(::remove)
        }.apply()
    }
}

data class BaseUrlCacheKeys(
    val cachedUrl: String,
    val fetchedAt: String,
    val attemptedAt: String,
)

class DynamicBaseUrlResolver(
    private val storage: BaseUrlStorage,
    private val keys: BaseUrlCacheKeys,
    private val fallbackBaseUrl: () -> String,
    private val isAllowedAutomaticUrl: (HttpUrl) -> Boolean,
    private val discoverBaseUrl: () -> String?,
    private val redirectBaseUrl: () -> String?,
    private val onAutomaticUrlResolved: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val cacheDurationMs: Long = DEFAULT_CACHE_DURATION_MS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) {
    private val refreshLock = Any()
    private var generation = 0L
    private var inFlight: Refresh? = null

    private data class Refresh(val generation: Long, val task: FutureTask<String>)

    fun resolve(): String {
        while (true) {
            var startRefresh = false
            val refresh = synchronized(refreshLock) {
                val currentTime = now()
                val cached = cachedBaseUrl()
                if (cached != null && isRecent(storage.getLong(keys.fetchedAt), currentTime, cacheDurationMs)) {
                    return cached.also(onAutomaticUrlResolved)
                }

                inFlight ?: run {
                    if (isRecent(storage.getLong(keys.attemptedAt), currentTime, retryDelayMs)) {
                        return (cached ?: normalizedFallback()).also(onAutomaticUrlResolved)
                    }
                    val refreshGeneration = generation
                    startRefresh = true
                    Refresh(refreshGeneration, FutureTask { refreshCache(refreshGeneration, cached) })
                        .also { inFlight = it }
                }
            }

            // Only the owner performs discovery. Other callers share its result without
            // holding the state lock, so settings can invalidate an in-flight lookup.
            if (startRefresh) refresh.task.run()
            val result = try {
                refresh.task.get()
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            } finally {
                synchronized(refreshLock) {
                    if (refresh.task.isDone && inFlight === refresh) inFlight = null
                }
            }
            synchronized(refreshLock) {
                if (refresh.generation == generation) return result
            }
        }
    }

    private fun refreshCache(refreshGeneration: Long, previousUrl: String?): String {
        val discovered = lookup(discoverBaseUrl) ?: lookup(redirectBaseUrl)
        return synchronized(refreshLock) {
            if (refreshGeneration != generation) {
                return@synchronized cachedBaseUrl() ?: normalizedFallback()
            }
            val completedAt = now()
            storage.putLong(keys.attemptedAt, completedAt)
            if (discovered != null) {
                storage.putString(keys.cachedUrl, discovered)
                storage.putLong(keys.fetchedAt, completedAt)
            }
            (discovered ?: previousUrl ?: normalizedFallback()).also(onAutomaticUrlResolved)
        }
    }

    private fun lookup(discover: () -> String?): String? = try {
        discover()?.let(::normalizeAutomaticBaseUrl)
    } catch (error: IOException) {
        if (Thread.currentThread().isInterrupted) throw error
        null
    }

    private fun isRecent(timestamp: Long, currentTime: Long, duration: Long): Boolean = timestamp > 0 && currentTime - timestamp in 0 until duration

    fun cachedBaseUrl(): String? = storage.getString(keys.cachedUrl)?.let(::normalizeAutomaticBaseUrl)

    fun clearCache() {
        synchronized(refreshLock) {
            generation++
            storage.remove(keys.cachedUrl, keys.fetchedAt, keys.attemptedAt)
        }
    }

    /** Accept only a redirect from the address still in use, never a late old response. */
    fun recordRedirect(fromBaseUrl: String, toBaseUrl: String): Boolean {
        val from = normalizeAutomaticBaseUrl(fromBaseUrl) ?: return false
        val target = normalizeAutomaticBaseUrl(toBaseUrl) ?: return false
        return synchronized(refreshLock) {
            if (from == target || (cachedBaseUrl() ?: normalizedFallback()) != from) return@synchronized false
            generation++
            storage.putString(keys.cachedUrl, target)
            storage.putLong(keys.fetchedAt, now())
            storage.putLong(keys.attemptedAt, now())
            onAutomaticUrlResolved(target)
            true
        }
    }

    private fun normalizedFallback(): String = normalizeAutomaticBaseUrl(fallbackBaseUrl())
        ?: error("Invalid fallback Base URL: ${fallbackBaseUrl()}")

    private fun normalizeAutomaticBaseUrl(value: String): String? = normalizeBaseUrl(value, isAllowedAutomaticUrl)

    companion object {
        const val DEFAULT_CACHE_DURATION_MS = 12 * 60 * 60 * 1000L
        const val DEFAULT_RETRY_DELAY_MS = 15 * 60 * 1000L
    }
}

fun normalizeBaseUrl(
    value: String,
    isAllowedUrl: (HttpUrl) -> Boolean = { true },
): String? {
    val url = value.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
    if (
        url.scheme != "https" ||
        url.port != 443 ||
        url.encodedPath != "/" ||
        url.query != null ||
        url.fragment != null ||
        url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        !isAllowedUrl(url)
    ) {
        return null
    }
    return url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
}

fun shouldInvalidateNumberedDomainCache(
    cachedBaseUrl: String?,
    legacyDomainNumber: String?,
    minimumDomainNumber: Int,
    hostNumberRegex: Regex,
): Boolean {
    val cachedDomainNumber = cachedBaseUrl
        ?.toHttpUrlOrNull()
        ?.host
        ?.let { hostNumberRegex.matchEntire(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        ?: legacyDomainNumber?.toIntOrNull()

    return cachedDomainNumber == null || cachedDomainNumber < minimumDomainNumber
}

fun Request.rewriteBaseUrl(
    targetBaseUrl: String,
    shouldRewriteHost: (String) -> Boolean,
): Request {
    if (!shouldRewriteHost(url.host)) return this

    val target = targetBaseUrl.toHttpUrlOrNull() ?: return this
    val rewrittenUrl = url.newBuilder()
        .scheme(target.scheme)
        .host(target.host)
        .port(target.port)
        .build()
    val builder = newBuilder().url(rewrittenUrl)

    listOf("Referer", "Origin").forEach { header ->
        this.header(header)
            ?.toHttpUrlOrNull()
            ?.takeIf { shouldRewriteHost(it.host) }
            ?.let { headerUrl ->
                val rewrittenHeaderUrl = headerUrl.newBuilder()
                    .scheme(target.scheme)
                    .host(target.host)
                    .port(target.port)
                    .build()
                val value = if (header == "Origin") {
                    rewrittenHeaderUrl.newBuilder().encodedPath("/").query(null).fragment(null)
                        .build().toString().trimEnd('/')
                } else {
                    rewrittenHeaderUrl.toString()
                }
                builder.header(header, value)
            }
    }

    return builder.build()
}
