package keiyoushi.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Validate the entire guide token before upgrading official HTTP links to HTTPS. */
fun normalizeDiscoveredBaseUrl(value: String, allowedHost: (String) -> Boolean): String? {
    val text = value.trim()
    val raw = when {
        text.startsWith("//") -> "https:$text"
        text.contains("://") -> text
        else -> "https://$text"
    }.toHttpUrlOrNull() ?: return null
    if (raw.port != (if (raw.scheme == "https") 443 else 80)) return null
    return normalizeBaseUrl(raw.newBuilder().scheme("https").port(443).build().toString()) { allowedHost(it.host) }
}

fun findDiscoveredBaseUrl(text: String, allowedHost: (String) -> Boolean): String? = Regex("""[^\s<>"']+""").findAll(text)
    .firstNotNullOfOrNull { normalizeDiscoveredBaseUrl(it.value, allowedHost) }
