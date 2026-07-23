package keiyoushi.utils

import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

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

    fun resolve(): String {
        val currentTime = now()
        val cached = cachedBaseUrl()
        if (cached != null && currentTime - storage.getLong(keys.fetchedAt) < cacheDurationMs) {
            onAutomaticUrlResolved(cached)
            return cached
        }

        if (currentTime - storage.getLong(keys.attemptedAt) < retryDelayMs) {
            return (cached ?: normalizedFallback()).also(onAutomaticUrlResolved)
        }

        return synchronized(refreshLock) {
            val synchronizedTime = now()
            val synchronizedCached = cachedBaseUrl()
            if (
                synchronizedCached != null &&
                synchronizedTime - storage.getLong(keys.fetchedAt) < cacheDurationMs
            ) {
                onAutomaticUrlResolved(synchronizedCached)
                return@synchronized synchronizedCached
            }

            if (synchronizedTime - storage.getLong(keys.attemptedAt) < retryDelayMs) {
                return@synchronized (synchronizedCached ?: normalizedFallback()).also(onAutomaticUrlResolved)
            }

            storage.putLong(keys.attemptedAt, synchronizedTime)
            val discovered = discoverBaseUrl()?.let(::normalizeAutomaticBaseUrl)
                ?: redirectBaseUrl()?.let(::normalizeAutomaticBaseUrl)

            if (discovered != null) {
                storage.putString(keys.cachedUrl, discovered)
                storage.putLong(keys.fetchedAt, synchronizedTime)
            }

            (discovered ?: synchronizedCached ?: normalizedFallback()).also(onAutomaticUrlResolved)
        }
    }

    fun cachedBaseUrl(): String? = storage.getString(keys.cachedUrl)?.let(::normalizeAutomaticBaseUrl)

    fun clearCache() {
        storage.remove(keys.cachedUrl, keys.fetchedAt, keys.attemptedAt)
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
                builder.header(
                    header,
                    headerUrl.newBuilder()
                        .scheme(target.scheme)
                        .host(target.host)
                        .port(target.port)
                        .build()
                        .toString(),
                )
            }
    }

    return builder.build()
}
