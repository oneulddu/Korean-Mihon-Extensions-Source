package eu.kanade.tachiyomi.extension.ko.jjaptoon

import keiyoushi.utils.rewriteBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal class JjaptoonRequestRewriter(
    private val automaticHostRegex: Regex,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var manualHost: String? = null
    private var previousManualHost: String? = null
    private var changedAt = 0L

    @Synchronized
    fun updateManual(baseUrl: String?) {
        val host = baseUrl?.toHttpUrl()?.host
        if (manualHost == host) return
        previousManualHost = manualHost
        manualHost = host
        changedAt = now()
    }

    fun rewrite(request: Request, manualBaseUrl: String?, resolveAutomatic: () -> String): Request {
        val managedHosts = synchronized(this) {
            updateManual(manualBaseUrl)
            // Keep only the immediately preceding manual host briefly for already queued image requests.
            val previous = previousManualHost.takeIf { now() - changedAt in 0 until TimeUnit.MINUTES.toMillis(15) }
            setOfNotNull(manualHost, previous)
        }
        fun isManaged(host: String): Boolean = host.matches(automaticHostRegex) || host in managedHosts
        if (!isManaged(request.url.host)) return request
        return request.rewriteBaseUrl(manualBaseUrl ?: resolveAutomatic(), ::isManaged)
    }
}
