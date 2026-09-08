package eu.kanade.tachiyomi.extension.ko.ntk

import keiyoushi.utils.BaseUrlCacheKeys
import keiyoushi.utils.BaseUrlStorage
import keiyoushi.utils.DynamicBaseUrlResolver
import keiyoushi.utils.normalizeBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class NTKDomain(
    private val storage: BaseUrlStorage,
    private val now: () -> Long = System::currentTimeMillis,
    redirect: (List<String>) -> String?,
) {
    private var previousManualHost: String? = null
    private var previousManualExpiresAt = 0L
    private val resolver = DynamicBaseUrlResolver(
        storage = storage,
        keys = BaseUrlCacheKeys(CACHE_KEY, "domain_fetched_at", "domain_attempted_at"),
        fallbackBaseUrl = { DEFAULT_URL },
        isAllowedAutomaticUrl = { AUTOMATIC_HOST.matches(it.host) },
        // No official address portal has been verified for NTK. Recover using validated
        // current/default redirects, then the last good cache, then the build default.
        discoverBaseUrl = { null },
        redirectBaseUrl = { redirect(listOfNotNull(cachedUrl(), DEFAULT_URL).distinct()) },
    )

    init {
        val legacy = storage.getString("pref_domain_key")?.trim()
            ?.takeIf { it.matches(Regex("[0-9]+")) }?.trimStart('0')?.takeIf { it.isNotEmpty() }
        val previousDefault = storage.getString("pref_domain_default_key")
        if (storage.getString(CACHE_KEY) == null && legacy != null) {
            val number = if (legacy == "3" && previousDefault != DEFAULT_NUMBER) DEFAULT_NUMBER else legacy
            // The old numeric setting mixed automatic updates and user edits. Never
            // infer manual intent from it; preserve it only as a stale automatic cache.
            storage.putString(CACHE_KEY, "https://sbxh$number.com")
        }
        if (previousDefault != DEFAULT_NUMBER && cachedUrl() == "https://sbxh3.com") {
            resolver.clearCache()
        }
        storage.remove("pref_domain_key")
        storage.putString("pref_domain_default_key", DEFAULT_NUMBER)
    }

    fun manualUrl(): String? = storage.getString(MANUAL_KEY)?.let { normalizeBaseUrl(it) }

    fun cachedUrl(): String? = resolver.cachedBaseUrl()

    fun currentUrl(): String = manualUrl() ?: cachedUrl() ?: DEFAULT_URL

    fun resolve(): String {
        manualUrl()?.let { return it }
        val automatic = resolver.resolve()
        // A settings edit can occur while redirect I/O is in flight. Automatic results
        // only update the automatic cache and cannot replace the newly selected URL.
        return manualUrl() ?: automatic
    }

    @Synchronized
    fun recordRedirect(fromBaseUrl: String, toBaseUrl: String): Boolean = manualUrl() == null && resolver.recordRedirect(fromBaseUrl, toBaseUrl)

    @Synchronized
    fun setManual(value: String): Boolean {
        val normalized = if (value.isBlank()) "" else normalizeBaseUrl(value) ?: return false
        val previousHost = manualUrl()?.toHttpUrl()?.host
        val nextHost = normalized.takeIf(String::isNotEmpty)?.toHttpUrl()?.host
        if (previousHost != nextHost) {
            previousManualHost = previousHost
            previousManualExpiresAt = now() + 15 * 60 * 1000L
        }
        storage.putString(MANUAL_KEY, normalized)
        return true
    }

    @Synchronized
    fun ownsHost(host: String): Boolean = AUTOMATIC_HOST.matches(host) ||
        host == manualUrl()?.toHttpUrl()?.host ||
        (host == previousManualHost && now() < previousManualExpiresAt)

    fun summary(): String = if (manualUrl() != null) {
        "현재 수동 주소: ${currentUrl()}\n비우면 자동 탐색으로 돌아갑니다."
    } else {
        "현재 자동 주소: ${currentUrl()}\n탐색 출처: 현재/기본 주소의 HTTP 리다이렉트 (실패 시 캐시·기본 주소)"
    }

    companion object {
        const val MANUAL_KEY = "manual_base_url"
        const val CACHE_KEY = "domain_cached_url"
        private const val DEFAULT_NUMBER = "9"
        const val DEFAULT_URL = "https://sbxh$DEFAULT_NUMBER.com"
        val AUTOMATIC_HOST = Regex("sbxh[1-9][0-9]*\\.com")
    }
}
