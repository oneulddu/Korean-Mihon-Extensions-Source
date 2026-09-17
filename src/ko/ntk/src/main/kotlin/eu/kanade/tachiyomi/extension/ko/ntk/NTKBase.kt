package eu.kanade.tachiyomi.extension.ko.ntk

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.SharedPreferencesBaseUrlStorage
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.normalizeBaseUrl
import keiyoushi.utils.parseAs
import keiyoushi.utils.rewriteBaseUrl
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private class AdAcknowledgmentRequiredException(message: String) : IOException(message)

private class ImageApiRequestException(
    val code: Int,
    message: String,
) : IOException(message)

internal data class ClientSigningKey(
    val keyId: String,
    val origin: String,
    val fingerprint: String?,
    val privateKey: PrivateKey,
    val expiresAt: Long,
    val serverTimeOffsetMs: Long,
) {
    fun isValidFor(origin: String, fingerprint: String?, now: Long, renewalMarginMs: Long): Boolean = this.origin == origin && this.fingerprint == fingerprint && expiresAt > now + renewalMarginMs
}

abstract class NTKBase(
    override val name: String,
    protected val contentKind: String,
) : HttpSource(),
    ConfigurableSource {

    protected val apiHeaders
        get() = headers.newBuilder()
            .set("Accept", "application/json")
            .build()

    override val lang = "ko"
    override val supportsLatest = true
    protected val preferences by getPreferencesLazy()
    private val clientSigningKey = AtomicReference<ClientSigningKey?>(null)
    private val domain by lazy {
        NTKDomain(SharedPreferencesBaseUrlStorage(preferences), redirect = ::findRedirectBaseUrl)
    }

    // This getter is also called by Mihon's UI thread; only the interceptor may resolve.
    protected val rootUrl: String get() = domain.currentUrl()

    private val addressClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun findRedirectBaseUrl(candidates: List<String>): String? {
        for (candidate in candidates) {
            var current = candidate
            val visited = mutableSetOf<String>()
            for (hop in 0 until 5) {
                if (!visited.add(current)) break
                val next = runCatching {
                    addressClient.newCall(GET(current)).execute().use { response ->
                        if (response.isSuccessful) return current
                        if (response.code !in listOf(301, 302, 303, 307, 308)) return@use null
                        val location = response.header("Location") ?: return@use null
                        val target = response.request.url.resolve(location) ?: return@use null
                        normalizeBaseUrl(target.toString()) { NTKDomain.AUTOMATIC_HOST.matches(it.host) }
                    }
                }.getOrNull() ?: break
                current = next
            }
        }
        return null
    }

    protected open val webViewPath: String get() = contentKind
    override val baseUrl: String get() = "$rootUrl/$webViewPath"

    override fun mangaDetailsRequest(manga: SManga) = GET(rootUrl + manga.url, headers)
    override fun chapterListRequest(manga: SManga) = GET(rootUrl + manga.url, headers)

    override fun pageListRequest(chapter: SChapter): Request = GET(
        rootUrl + chapter.url,
        if (contentKind == "manhwa") {
            headers
        } else {
            headers.newBuilder().add(WEBVIEW_HTML_FALLBACK_HEADER, "true").build()
        },
    )

    private val imageBridgeScript = """
        (function() {
            if (window.__ntkImageCapture) return;
            window.__ntkImageCapture = true;
            window.__ntkDone = false;

            function signalAdAcknowledgment() {
                const scope = window.location.pathname;
                window.__ntk_ad_ack_scope = scope;
                try {
                    window.dispatchEvent(new CustomEvent("ntk-ad-ack-ready", { detail: { scope: scope } }));
                } catch (_) {}
            }

            function send(value) {
                if (window.__ntkDone) return;
                try {
                    const payload = typeof value === "string" ? JSON.parse(value) : value;
                    if (payload && (
                        payload.error === "ad_ack_required" ||
                        payload.error === "fingerprint_required" ||
                        payload.error === "browser_key_required"
                    )) {
                        signalAdAcknowledgment();
                        return;
                    }
                    if (payload && Array.isArray(payload.images) && payload.images.length > 0) {
                        window.__ntkDone = true;
                        window.TrojanTunnel.exfiltrateApi(JSON.stringify(payload));
                    }
                } catch (_) {
                    // Ignore unrelated responses.
                }
            }

            function requestUrl(input) {
                if (!input) return "";
                if (typeof input === "string") return input;
                if (input.url) return input.url;
                return input.toString ? input.toString() : "";
            }

            function shouldCapture(url) {
                try {
                    const parsed = new URL(url || "", window.location.href);
                    return parsed.origin === window.location.origin &&
                        /\/api\/(webtoon|manhwa)-images$/i.test(parsed.pathname);
                } catch (_) {
                    return false;
                }
            }

            function absoluteImageUrl(value) {
                if (!value || /^(data|blob):/i.test(value)) return "";
                try {
                    const parsed = new URL(value, window.location.href);
                    return /^(https?):$/i.test(parsed.protocol) ? parsed.href : "";
                } catch (_) {
                    return "";
                }
            }

            if (window.fetch) {
                const originalFetch = window.fetch.bind(window);
                window.fetch = function() {
                    const args = arguments;
                    return originalFetch.apply(window, args).then(function(response) {
                        const method = (args[1] && args[1].method) ||
                            (args[0] && args[0].method) || "GET";
                        if (response.ok && String(method).toUpperCase() === "POST" && shouldCapture(requestUrl(args[0]))) {
                            response.clone().text().then(send).catch(function() {});
                        }
                        return response;
                    });
                };
            }

            if (window.XMLHttpRequest) {
                const originalOpen = XMLHttpRequest.prototype.open;
                const originalSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__ntkImageUrl = requestUrl(url);
                    return originalOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener("load", function() {
                        if (shouldCapture(this.__ntkImageUrl)) send(this.responseText || "");
                    });
                    return originalSend.apply(this, arguments);
                };
            }

            let checks = 0;
            const timer = window.setInterval(function() {
                if (window.__ntkDone) {
                    window.clearInterval(timer);
                    return;
                }

                const container = document.querySelector(".vw-imgs");
                if (container) {
                    const nodes = Array.from(container.querySelectorAll("img.viewer-ratio-img, img.viewer-lazy-img"));
                    const expectedCount = parseInt(container.getAttribute("data-viewer-image-count") || "0", 10) || nodes.length;
                    const seen = new Set();
                    const images = nodes
                        .map(function(img, index) {
                            const src = absoluteImageUrl(img.currentSrc) ||
                                absoluteImageUrl(img.getAttribute("src")) ||
                                absoluteImageUrl(img.getAttribute("data-src"));
                            const pageMatch = (img.getAttribute("alt") || "").match(/\d+/);
                            return src ? {
                                src: src,
                                page: pageMatch ? parseInt(pageMatch[0], 10) : index + 1,
                            } : null;
                        })
                        .filter(function(image) {
                            if (!image || seen.has(image.src)) return false;
                            seen.add(image.src);
                            return true;
                        });

                    if (images.length > 0 && (!expectedCount || images.length >= expectedCount)) {
                        window.clearInterval(timer);
                        send({ images: images });
                    }
                }

                checks += 1;
                if (checks > 200) window.clearInterval(timer);
            }, 100);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private val trojanWebViewInterceptor = Interceptor { chain ->
        val request = chain.request()

        if (request.header(WEBVIEW_HTML_FALLBACK_HEADER) != null) {
            val cleanRequest = request.newBuilder()
                .removeHeader(WEBVIEW_HTML_FALLBACK_HEADER)
                .build()
            val response = chain.proceed(cleanRequest)
            val preview = response.peekBody(1_000_000).string()
            val shouldOpenWebView = response.code in CLOUDFLARE_HTML_ERROR_CODES ||
                isCloudflareChallengeHtml(preview) ||
                extractHtmlString(preview, "imagesToken") == null

            if (!shouldOpenWebView) {
                return@Interceptor response
            }

            val finalRequest = response.request
            response.close()
            loadChapterHtmlWithWebView(finalRequest)?.let { html ->
                return@Interceptor Response.Builder()
                    .request(finalRequest)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(html.toResponseBody(HTML_MEDIA_TYPE))
                    .build()
            }

            return@Interceptor chain.proceed(cleanRequest)
        }

        if (request.header("X-WebView-Intercept") == null) {
            return@Interceptor chain.proceed(request)
        }

        loadPageImagesPayloadWithWebView(request)?.let {
            val isJson = it.trim().startsWith("{")
            val mediaType = if (isJson) "application/json" else "text/html"
            return@Interceptor Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(it.toResponseBody(mediaType.toMediaType()))
                .build()
        }

        return@Interceptor chain.proceed(
            request.newBuilder()
                .removeHeader(WEBVIEW_IMAGE_FALLBACK_HEADER)
                .build(),
        )
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun loadPageImagesPayloadWithWebView(request: Request): String? {
        val chapterUrl = request.url.toString()
        val cookieManager = android.webkit.CookieManager.getInstance()
        val attempts = listOf(
            WebViewImageAttempt(originOf(chapterUrl), chapterUrl),
            WebViewImageAttempt(chapterUrl, chapterUrl, warmUpRoot = false),
        )

        attempts.forEach { attempt ->
            val result = WebViewResult<String>()
            val handler = Handler(Looper.getMainLooper())
            val webViewRef = AtomicReference<WebView?>(null)

            fun complete(payload: String) {
                if (payload.isBlank() || result.completed) return
                val parsed = runCatching { json.decodeFromString<PageImagesResponse>(payload) }.getOrNull()
                if (parsed?.images.isNullOrEmpty()) return
                result.complete(payload)
            }

            handler.post {
                if (result.completed) return@post
                val webView = WebView(Injekt.get<Application>())
                webViewRef.set(webView)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.loadsImagesAutomatically = true
                webView.settings.blockNetworkImage = false
                webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webView.settings.userAgentString = request.header("User-Agent") ?: DEFAULT_USER_AGENT
                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, 360, 640)

                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                webView.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun exfiltrateApi(payload: String) = complete(payload)
                    },
                    "TrojanTunnel",
                )

                var chapterNavigationStarted = !attempt.warmUpRoot
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        if (result.completed) return
                        if (chapterNavigationStarted) view.evaluateJavascript(imageBridgeScript, null)
                        super.onPageStarted(view, url, favicon)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (result.completed) return
                        if (!chapterNavigationStarted) {
                            chapterNavigationStarted = true
                            handler.postDelayed({
                                if (!result.completed) view.loadUrl(attempt.chapterUrl)
                            }, WEBVIEW_ROOT_WARMUP_DELAY_MS)
                        }
                        view.evaluateJavascript(imageBridgeScript, null)
                        super.onPageFinished(view, url)
                    }
                }

                webView.loadUrl(attempt.initialUrl)
            }

            awaitWebView(result, WEBVIEW_IMAGE_ATTEMPT_TIMEOUT_SECONDS, handler, webViewRef)
                ?.let { return it }
        }

        return null
    }

    private data class WebViewImageAttempt(
        val initialUrl: String,
        val chapterUrl: String,
        val warmUpRoot: Boolean = true,
    )

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun loadChapterHtmlWithWebView(
        request: Request,
        waitForAdAcknowledgment: Boolean = false,
    ): String? {
        val result = WebViewResult<String>()
        val handler = Handler(Looper.getMainLooper())
        val webViewRef = AtomicReference<WebView?>(null)

        fun completeWith(html: String) {
            if (result.completed) return
            if (
                html.isBlank() ||
                isCloudflareChallengeHtml(html) ||
                extractHtmlString(html, "imagesToken") == null
            ) {
                return
            }
            result.complete(html)
        }

        handler.post {
            if (result.completed) return@post
            val context = Injekt.get<Application>()
            val webView = WebView(context)
            webViewRef.set(webView)

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.loadsImagesAutomatically = true
            webView.settings.blockNetworkImage = false
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webView.settings.userAgentString = request.header("User-Agent") ?: DEFAULT_USER_AGENT

            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun collectHtml(html: String, adAcknowledged: Boolean) {
                        if (waitForAdAcknowledgment && !adAcknowledged) return
                        completeWith(html)
                    }
                },
                "NTKHtmlBridge",
            )

            fun collectHtmlFromPage() {
                if (result.completed) return
                webView.evaluateJavascript(
                    """
                        (function() {
                            try {
                                window.NTKHtmlBridge.collectHtml(
                                    document.documentElement.outerHTML || '',
                                    window.__ntk_ad_ack_scope === window.location.pathname,
                                );
                            } catch (_) {}
                        })();
                    """.trimIndent(),
                    null,
                )
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (result.completed) return
                    collectHtmlFromPage()
                    handler.postDelayed({ collectHtmlFromPage() }, 3_000)
                    handler.postDelayed({ collectHtmlFromPage() }, 8_000)
                    handler.postDelayed({ collectHtmlFromPage() }, 16_000)
                    super.onPageFinished(view, url)
                }
            }

            val requestHeaders = request.headers.toMultimap()
                .filterKeys { it.equals("Host", ignoreCase = true).not() }
                .mapValues { it.value.joinToString(",") }

            webView.loadUrl(request.url.toString(), requestHeaders)
            handler.postDelayed({ collectHtmlFromPage() }, 5_000)
            handler.postDelayed({ collectHtmlFromPage() }, 12_000)
            handler.postDelayed({ collectHtmlFromPage() }, 24_000)
        }

        return awaitWebView(result, 28, handler, webViewRef)
    }

    private val headerCleanerInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        if (originalRequest.header("Accept") == null) {
            requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        }

        chain.proceed(requestBuilder.build())
    }

    private val domainUpdateInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (!domain.ownsHost(request.url.host)) return@Interceptor chain.proceed(request)
        val target = domain.resolve()
        val rewritten = request.rewriteBaseUrl(target, domain::ownsHost)
        if ((request.method != "GET" || request.header("Cookie") != null) && request.url.host != rewritten.url.host) {
            // Tokens, browser signing keys and ad acknowledgment cookies belong to the
            // chapter's origin. Reload the chapter instead of replaying them on a new host.
            throw IOException("NTK address changed; reload the chapter before retrying")
        }
        val response = chain.proceed(
            rewritten.newBuilder()
                .header("Origin", target)
                .header("Referer", rewritten.header("Referer") ?: "$target/")
                .build(),
        )
        if (request.method == "GET" && response.isSuccessful && response.priorResponse != null) {
            domain.recordRedirect(target, originOf(response.request.url.toString()))
        }
        response
    }

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .addInterceptor(headerCleanerInterceptor)
            .addInterceptor(domainUpdateInterceptor)
            .addInterceptor(trojanWebViewInterceptor)
            .build()
    }

    @Serializable
    private data class WorksResponse(
        val works: List<Work>,
        val hasMore: Boolean,
    )

    @Serializable
    private data class Work(
        val sourceWorkId: String,
        val title: String? = null,
        val workTitle: String? = null,
        val thumbnailUrl: String? = null,
        val genre: String? = null,
        val author: String? = null,
    )

    @Serializable
    private data class PageImagesResponse(
        val images: List<PageImage>,
    )

    @Serializable
    private data class PageImage(
        val page: Int? = null,
        val src: String,
    )

    @Serializable
    private data class Episode(
        val sourceEpisodeId: JsonPrimitive,
        val title: String? = null,
        val epNo: Int? = null,
    )

    @Serializable
    private data class EpisodesResponse(
        val ok: Boolean? = null,
        val total: Int? = null,
        val page: Int? = null,
        val totalPages: Int? = null,
        val episodes: List<Episode>,
    )

    @Serializable
    private data class ImageApiRequest(
        val workId: String,
        val episodeId: String,
        val token: String,
        val nonce: String,
        val proof: String,
    )

    @Serializable
    private data class ClientPublicKey(
        val crv: String,
        val ext: Boolean,
        @SerialName("key_ops") val keyOps: List<String>,
        val kty: String,
        val x: String,
        val y: String,
    )

    @Serializable
    private data class ClientKeyRegistrationRequest(
        val publicKey: ClientPublicKey,
    )

    @Serializable
    private data class ClientKeyRegistrationResponse(
        val ok: Boolean = false,
        val keyId: String? = null,
        val expiresAt: Long? = null,
        val serverNow: Long? = null,
    )

    @Serializable
    private data class AdChallengeRequest(
        val path: String,
        val force: Boolean = true,
    )

    @Serializable
    private data class AdChallengeResponse(
        val ok: Boolean = false,
        val temporary: Boolean = false,
        val challenge: AdChallenge? = null,
    )

    @Serializable
    private data class AdChallenge(
        val token: String,
        val scope: String,
        val slotCount: Int,
        val minSeen: Int,
        val impressionUrls: List<String> = emptyList(),
    )

    @Serializable
    private data class AdAcknowledgmentRequest(
        val challengeToken: String,
        val total: Int,
        val visible: Int,
        val path: String,
        val td: Int = 0,
        val tp: String = "0",
        val ap: String = "0",
        val requestKeyId: String,
        val observationUrls: List<String>,
    )

    @Serializable
    private data class AdAcknowledgmentResponse(
        val ok: Boolean = false,
    )

    protected fun htmlCardParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val cardSelector = listOf("div.card-grid", "div.search-results-grid")
            .joinToString(", ") { "$it > a.card[href^=\"/$contentKind/\"]" }
        val mangas = document.select(cardSelector).map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = element.select("p.subject").text()
                thumbnail_url = element.select("div.thumb img:not(.platform-icon)").attr("abs:src")
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<WorksResponse>()
        val mangas = data.works.map { work ->
            SManga.create().apply {
                url = "/$contentKind/${work.sourceWorkId}"
                title = work.title ?: ""
                thumbnail_url = work.thumbnailUrl
                genre = work.genre
            }
        }
        return MangasPage(mangas, data.hasMore)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val jsonArrayStr = extractAllCards(document.select("script").map { it.data() }, json)
            ?: return MangasPage(emptyList(), false)
        val cards = json.decodeFromString<List<Work>>(jsonArrayStr)

        val seen = mutableSetOf<String>()
        val mangas = cards.mapNotNull { card ->
            if (seen.add(card.sourceWorkId)) {
                SManga.create().apply {
                    url = "/$contentKind/${card.sourceWorkId}"
                    title = card.workTitle ?: card.title ?: ""
                    thumbnail_url = card.thumbnailUrl
                    genre = card.genre
                    author = card.author
                }
            } else {
                null
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val contentType = response.header("Content-Type") ?: ""
        return if (contentType.contains("application/json")) {
            popularMangaParse(response)
        } else {
            htmlCardParse(response)
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.select("h1.hero-v2-title").text()
            author = document.select("div.hero-v2-author a").text()
            description = document.select("p.hero-v2-desc").text()
            thumbnail_url = document.select("div.hero-v2-thumb img").attr("abs:src")

            val statusText = document.select("span.pill-status").text()
            status = when {
                statusText.contains("연재중") -> SManga.ONGOING
                statusText.contains("완결") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            genre = document.select("a.hero-v2-tag").joinToString(", ") {
                it.text().replace("#", "").trim()
            }
        }
    }

    private val dateFormat = SimpleDateFormat("yy.MM.dd", Locale.KOREA)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val initialChapters = parseChapterRows(document)
        val mangaPath = response.request.url.encodedPath.trimEnd('/')
        val workId = response.request.url.pathSegments.getOrNull(1)

        val expectedCount = document.selectFirst(".ep-section-count")?.text()
            ?.filter(Char::isDigit)?.toIntOrNull()
        return episodeApiOrHtml(
            api = {
                workId?.let(::fetchAllEpisodes)?.let { episodes ->
                    episodesToChapters(mangaPath, episodes).takeIf {
                        expectedCount == null || it.size == expectedCount
                    }
                }
            },
            html = {
                val total = expectedCount
                    ?: throw IOException("NTK chapter list incomplete: cannot verify total episode count")
                val uniqueInitial = initialChapters.distinctBy(SChapter::url)
                if (uniqueInitial.size == total) {
                    uniqueInitial
                } else {
                    fetchAllChapterPages(mangaPath, total, uniqueInitial)
                }
            },
        )
    }

    private fun parseChapterRows(document: org.jsoup.nodes.Document): List<SChapter> = document.select("a.ep-row-v2-link[href]").mapNotNull { element ->
        val href = element.attr("href").trim()
        val title = (
            element.selectFirst(".ep-row-v2-title strong")
                ?: element.selectFirst(".ep-row-v2-title")
            )?.text()?.trim().orEmpty()
        if (href.isEmpty() || title.isEmpty()) {
            null
        } else {
            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = title
                date_upload = synchronized(dateFormat) { dateFormat.tryParse(element.select(".ep-row-v2-date").text()) }
            }
        }
    }

    private fun fetchAllChapterPages(
        mangaPath: String,
        totalEpisodes: Int,
        initialChapters: List<SChapter>,
    ): List<SChapter> {
        val chapters = initialChapters.toMutableList()
        if (totalEpisodes !in 0..(MAX_EPISODE_API_PAGES * EPISODES_PER_PAGE)) {
            throw IOException("NTK chapter list incomplete: invalid total episode count")
        }
        val totalPages = (totalEpisodes + EPISODES_PER_PAGE - 1) / EPISODES_PER_PAGE
        for (page in 2..totalPages) {
            val pageChapters = fetchChapterPage(mangaPath, page)
            if (pageChapters.isEmpty()) break
            chapters += pageChapters
        }
        return chapters.distinctBy(SChapter::url).also {
            requireCompleteChapters(totalEpisodes, it.size)
        }
    }

    private fun fetchChapterPage(mangaPath: String, page: Int): List<SChapter> = runCatching {
        client.newCall(GET("$rootUrl$mangaPath?epage=$page", headers)).execute().use { response ->
            if (response.isSuccessful) parseChapterRows(response.asJsoup()) else emptyList()
        }
    }.getOrDefault(emptyList())

    private fun fetchAllEpisodes(workId: String): List<Episode>? = runCatching {
        if (contentKind == "manhwa") {
            fetchAllManhwaEpisodes(workId)
        } else {
            val payload = fetchEpisodePage(workId)
            if (payload.total == null) throw IOException("NTK episode API failed: missing total")
            if (payload.total != payload.episodes.size) {
                throw IOException("NTK episode API failed: expected ${payload.total}, received ${payload.episodes.size}")
            }
            payload.episodes
        }
    }.getOrNull()

    private fun fetchAllManhwaEpisodes(workId: String): List<Episode> {
        val firstPage = fetchEpisodePage(workId, page = 1)
        val total = firstPage.total ?: throw IOException("NTK episode API failed: missing total")
        if (firstPage.page != null && firstPage.page != 1) {
            throw IOException("NTK episode API failed: invalid first page")
        }
        if (total !in 0..(MAX_EPISODE_API_PAGES * EPISODES_PER_PAGE)) {
            throw IOException("NTK episode API failed: invalid total")
        }
        val totalPages = firstPage.totalPages
            ?: ((total + EPISODES_PER_PAGE - 1) / EPISODES_PER_PAGE)
        if (totalPages !in 0..MAX_EPISODE_API_PAGES || (totalPages == 0 && firstPage.episodes.isNotEmpty())) {
            throw IOException("NTK episode API failed: invalid total pages")
        }

        val episodes = ArrayList<Episode>(total)
        episodes += firstPage.episodes
        for (page in 2..totalPages) {
            val payload = fetchEpisodePage(workId, page)
            if (payload.page != null && payload.page != page) {
                throw IOException("NTK episode API failed: expected page $page, received ${payload.page}")
            }
            if (payload.total != null && payload.total != total) {
                throw IOException("NTK episode API failed: total changed while paging")
            }
            if (payload.totalPages != null && payload.totalPages != totalPages) {
                throw IOException("NTK episode API failed: total pages changed while paging")
            }
            episodes += payload.episodes
        }
        if (total != episodes.size) {
            throw IOException("NTK episode API failed: expected $total, received ${episodes.size}")
        }
        return episodes
    }

    private fun fetchEpisodePage(workId: String, page: Int? = null): EpisodesResponse {
        val url = rootUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/$contentKind")
            .addPathSegment(workId)
            .addPathSegment("episodes")
            .apply {
                if (contentKind == "manhwa") {
                    addPathSegment("viewer-nav")
                    addQueryParameter("page", (page ?: 1).toString())
                }
            }
            .build()
        return client.newCall(GET(url, apiHeaders)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("NTK episode API failed: HTTP ${response.code}")
            val payload = json.decodeFromString<EpisodesResponse>(response.body.string())
            if (payload.ok != true) throw IOException("NTK episode API failed: invalid response")
            payload
        }
    }

    private fun episodesToChapters(mangaPath: String, episodes: List<Episode>): List<SChapter> {
        val seenIds = HashSet<String>(episodes.size)
        return episodes.map { episode ->
            val episodeId = episode.sourceEpisodeId.content.trim()
            val title = episode.title?.trim().orEmpty().ifEmpty {
                episode.epNo?.takeIf { contentKind == "webtoon" }?.let { "${it}화" }.orEmpty()
            }
            validateEpisode(episodeId, title, seenIds)

            SChapter.create().apply {
                url = rootUrl.toHttpUrl().newBuilder()
                    .addEncodedPathSegments(mangaPath.trim('/'))
                    .addPathSegment(episodeId)
                    .build()
                    .encodedPath
                name = title
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url
        val referer = chapterUrl.toString()
        val segments = chapterUrl.pathSegments
        val workId = segments.getOrNull(1)
            ?: throw Exception("NTK image API failed: missing work id from $referer")
        val episodeId = segments.getOrNull(2)
            ?: throw Exception("NTK image API failed: missing episode id from $referer")

        val html = response.body.string()
        runCatching {
            json.decodeFromString<PageImagesResponse>(html)
        }.getOrNull()?.takeIf { it.images.isNotEmpty() }?.let { webViewData ->
            return webViewData.toPages(chapterUrl, referer)
        }

        if (isCloudflareChallengeHtml(html)) {
            throw Exception("NTK image API failed: Cloudflare challenge page was returned for $referer")
        }

        val imagesToken = extractHtmlString(html, "imagesToken")
            ?: throw Exception("NTK image API failed: imagesToken not found for $referer")

        val nvCookie = extractCookieValue(response.headers("Set-Cookie"), "nv")
            ?.takeIf(::isValidNvCookie)
            ?: issueNvCookie(referer)

        val data = try {
            fetchPageImages(workId, episodeId, imagesToken, nvCookie, referer)
        } catch (_: AdAcknowledgmentRequiredException) {
            fetchPageImagesAfterAdAcknowledgment(
                workId,
                episodeId,
                imagesToken,
                nvCookie,
                referer,
            )
        } catch (error: ImageApiRequestException) {
            if (!shouldUseWebViewForImageApi(error.code)) throw error
            fetchPageImagesWithWebView(referer)
        }
        return data.toPages(chapterUrl, referer)
    }

    private fun PageImagesResponse.toPages(chapterUrl: okhttp3.HttpUrl, referer: String): List<Page> {
        val imageUrls = images
            .let { orderedImages(it, PageImage::page) }
            .mapNotNull { image ->
                validImageUrl(chapterUrl, image.src)
            }
            .distinct()

        if (imageUrls.isEmpty()) {
            throw IOException("NTK image API returned no valid images for $referer")
        }
        return imageUrls.mapIndexed { index, imageUrl -> Page(index, referer, imageUrl) }
    }

    private fun fetchPageImages(
        workId: String,
        episodeId: String,
        imagesToken: String,
        nvCookie: String,
        referer: String,
    ): PageImagesResponse {
        val userAgent = headers["User-Agent"] ?: DEFAULT_USER_AGENT

        fun buildRequest(): Request {
            val nonce = randomBase64Url(24)
            val proof = hmacSha256Base64Url(nvCookie, "$imagesToken.$nonce.$userAgent")
            val bodyText = json.encodeToString(
                ImageApiRequest(
                    workId = workId,
                    episodeId = episodeId,
                    token = imagesToken,
                    nonce = nonce,
                    proof = proof,
                ),
            )
            val endpointPath = "/api/$contentKind-images"
            val cookie = webViewCookieHeader(
                referer,
                "nv=$nvCookie",
                fingerprintCookie(referer),
            )
            val signatureHeaders = createClientSignatureHeaders(
                endpointPath,
                Request.Builder().url(referer).build().url.encodedPath,
                bodyText,
                referer,
                cookie,
            )

            val requestHeaders = headers.newBuilder()
                .set("Accept", "application/json")
                .set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .set("Cache-Control", "no-store")
                .set("Content-Type", "application/json")
                .set("Origin", originOf(referer))
                .set("Referer", referer)
                .set("User-Agent", userAgent)
                .set("x-images-client", "viewer-v1")
                .set("x-nv-session", nvCookie)
                .apply {
                    cookie?.let { set("Cookie", it) }
                    signatureHeaders.forEach { (name, value) -> set(name, value) }
                }
                .build()

            return Request.Builder()
                .url(originOf(referer) + endpointPath)
                .headers(requestHeaders)
                .post(bodyText.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        client.newCall(buildRequest()).execute().use { firstResponse ->
            val firstBody = firstResponse.body.string()
            if (isCloudflareApiResponse(firstResponse.code, firstBody)) {
                warmUpCloudflare(referer)
                client.newCall(buildRequest()).execute().use { retryResponse ->
                    return parseImageApiResponse(
                        retryResponse.code,
                        retryResponse.isSuccessful,
                        retryResponse.body.string(),
                        referer,
                    )
                }
            }
            return parseImageApiResponse(firstResponse.code, firstResponse.isSuccessful, firstBody, referer)
        }
    }

    private fun parseImageApiResponse(
        code: Int,
        isSuccessful: Boolean,
        responseBody: String,
        referer: String,
    ): PageImagesResponse {
        if (isAdAcknowledgmentRequiredResponse(responseBody)) {
            throw AdAcknowledgmentRequiredException("NTK image API requires ad acknowledgment for $referer")
        }
        if (!isSuccessful) {
            throw ImageApiRequestException(
                code,
                "NTK image API failed: HTTP $code for $referer: ${responseBody.take(500)}",
            )
        }
        return runCatching {
            json.decodeFromString<PageImagesResponse>(responseBody)
        }.getOrElse { error ->
            throw Exception("NTK image API failed: invalid image JSON for $referer: ${responseBody.take(500)}", error)
        }
    }

    private fun isAdAcknowledgmentRequiredResponse(responseBody: String): Boolean {
        val normalizedBody = responseBody.lowercase(Locale.US)
        return "ad acknowledgment required" in normalizedBody ||
            "\"error\":\"ad_" in normalizedBody ||
            "\"error\": \"ad_" in normalizedBody
    }

    private fun shouldUseWebViewForImageApi(code: Int): Boolean = code == HTTP_FORBIDDEN || code == HTTP_PRECONDITION_REQUIRED

    private fun fetchPageImagesAfterAdAcknowledgment(
        workId: String,
        episodeId: String,
        imagesToken: String,
        nvCookie: String,
        referer: String,
    ): PageImagesResponse {
        if (!acknowledgeAds(referer)) return fetchPageImagesWithWebView(referer)

        return try {
            fetchPageImages(workId, episodeId, imagesToken, nvCookie, referer)
        } catch (_: AdAcknowledgmentRequiredException) {
            fetchPageImagesWithWebView(referer)
        } catch (error: ImageApiRequestException) {
            if (!shouldUseWebViewForImageApi(error.code)) throw error
            fetchPageImagesWithWebView(referer)
        }
    }

    private fun acknowledgeAds(referer: String): Boolean = runCatching {
        val refererUrl = Request.Builder().url(referer).build().url
        val path = refererUrl.encodedPath
        val cookie = webViewCookieHeader(referer, fingerprintCookie(referer))
        val signingKey = getClientSigningKey(referer, cookie) ?: return@runCatching false
        val requestHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .set("Content-Type", "application/json")
            .set("Origin", originOf(referer))
            .set("Referer", referer)
            .apply { cookie?.let { set("Cookie", it) } }
            .build()

        val challengeBody = json.encodeToString(AdChallengeRequest(path))
        val challenge = client.newCall(
            Request.Builder()
                .url("${originOf(referer)}/api/ad/challenge")
                .headers(requestHeaders)
                .post(challengeBody.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return@runCatching false
            json.decodeFromString<AdChallengeResponse>(response.body.string())
        }
        val adChallenge = challenge.challenge
            ?.takeIf { challenge.ok && challenge.temporary && it.scope == path }
            ?: return@runCatching false
        val observationUrls = adChallenge.impressionUrls.take(adChallenge.minSeen.coerceAtLeast(1))
        if (observationUrls.size < adChallenge.minSeen) return@runCatching false

        observationUrls.forEach { observationUrl ->
            val url = refererUrl.resolve(observationUrl) ?: return@runCatching false
            val observed = client.newCall(
                GET(
                    url,
                    headers.newBuilder()
                        .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                        .set("Referer", referer)
                        .apply {
                            if (originOf(url.toString()) == originOf(referer)) cookie?.let { set("Cookie", it) }
                        }
                        .build(),
                ),
            ).execute().use { it.isSuccessful }
            if (!observed) return@runCatching false
        }

        val acknowledgmentBody = json.encodeToString(
            AdAcknowledgmentRequest(
                challengeToken = adChallenge.token,
                total = adChallenge.slotCount,
                visible = adChallenge.slotCount,
                path = path,
                requestKeyId = signingKey.keyId,
                observationUrls = observationUrls,
            ),
        )
        client.newCall(
            Request.Builder()
                .url("${originOf(referer)}/api/ad/ack")
                .headers(requestHeaders)
                .post(acknowledgmentBody.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).execute().use { response ->
            val acknowledgment = runCatching {
                json.decodeFromString<AdAcknowledgmentResponse>(response.body.string())
            }.getOrNull()
            val adAckValue = extractCookieValue(response.headers("Set-Cookie"), "ad_ack")
            if (!response.isSuccessful || acknowledgment?.ok != true || adAckValue == null) {
                return@use false
            }

            // CookieManager alone owns ad_ack, including server expiry, path and deletion.
            // Do not cache a second value or extend its lifetime with a fabricated Max-Age.
            android.webkit.CookieManager.getInstance().run {
                response.headers("Set-Cookie").forEach { setCookie(response.request.url.toString(), it) }
                flush()
            }
            true
        }
    }.getOrDefault(false)

    private fun createClientSignatureHeaders(
        endpointPath: String,
        scopePath: String,
        bodyText: String,
        referer: String,
        cookie: String?,
    ): Map<String, String> {
        if (contentKind != "manhwa") return emptyMap()

        val signingKey = getClientSigningKey(referer, cookie) ?: return emptyMap()
        val timestamp = System.currentTimeMillis() + signingKey.serverTimeOffsetMs
        val nonce = randomBase64Url(24)
        val bodyHash = sha256Base64Url(bodyText)
        val payload = listOf(
            "ntk-brsig-v1",
            "POST",
            endpointPath,
            scopePath,
            signingKey.keyId,
            timestamp.toString(),
            nonce,
            bodyHash,
        ).joinToString("\n")
        val signature = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initSign(signingKey.privateKey)
                update(payload.toByteArray(StandardCharsets.UTF_8))
                derEcdsaToP1363(sign())
            }
        }.getOrNull() ?: return emptyMap()

        return mapOf(
            "x-ntk-key-id" to signingKey.keyId,
            "x-ntk-ts" to timestamp.toString(),
            "x-ntk-nonce" to nonce,
            "x-ntk-sig" to base64Url(signature),
        )
    }

    private fun getClientSigningKey(referer: String, cookie: String?): ClientSigningKey? {
        val now = System.currentTimeMillis()
        val origin = originOf(referer)
        val fingerprint = cookie?.split(';')?.map(String::trim)
            ?.firstOrNull { it.startsWith("ntk_fp=") }
        fun ClientSigningKey.isCurrent(): Boolean = isValidFor(origin, fingerprint, System.currentTimeMillis(), CLIENT_KEY_RENEWAL_MARGIN_MS)
        clientSigningKey.get()?.takeIf { it.isCurrent() }?.let { return it }

        return synchronized(clientSigningKey) {
            clientSigningKey.get()?.takeIf { it.isCurrent() } ?: run {
                val keyPair = runCatching {
                    KeyPairGenerator.getInstance("EC").apply {
                        initialize(ECGenParameterSpec("secp256r1"))
                    }.generateKeyPair()
                }.getOrNull() ?: return@synchronized null
                val publicKey = keyPair.public as? ECPublicKey ?: return@synchronized null
                val requestBody = json.encodeToString(
                    ClientKeyRegistrationRequest(
                        ClientPublicKey(
                            crv = "P-256",
                            ext = true,
                            keyOps = listOf("verify"),
                            kty = "EC",
                            x = base64Url(unsignedCoordinate(publicKey.w.affineX.toByteArray())),
                            y = base64Url(unsignedCoordinate(publicKey.w.affineY.toByteArray())),
                        ),
                    ),
                )
                val registration = registerClientKey(requestBody, referer, cookie)
                    ?.takeIf(::isValidClientKeyRegistration)
                    ?: registerClientKeyWithWebView(requestBody, referer)
                    ?: return@synchronized null
                val keyId = registration.keyId ?: return@synchronized null
                val serverNow = registration.serverNow ?: now
                val serverExpiresAt = registration.expiresAt ?: serverNow + CLIENT_KEY_DEFAULT_TTL_MS
                ClientSigningKey(
                    keyId = keyId,
                    origin = origin,
                    fingerprint = fingerprint,
                    privateKey = keyPair.private,
                    expiresAt = now + (serverExpiresAt - serverNow),
                    serverTimeOffsetMs = serverNow - now,
                ).also(clientSigningKey::set)
            }
        }
    }

    private fun isValidClientKeyRegistration(registration: ClientKeyRegistrationResponse): Boolean = registration.ok && registration.keyId?.let(CLIENT_KEY_ID_REGEX::matches) == true

    private fun registerClientKey(
        requestBody: String,
        referer: String,
        cookie: String?,
    ): ClientKeyRegistrationResponse? = runCatching {
        val request = Request.Builder()
            .url("${originOf(referer)}/api/client-key/register")
            .headers(
                headers.newBuilder()
                    .set("Accept", "application/json")
                    .set("Content-Type", "application/json")
                    .set("Origin", originOf(referer))
                    .set("Referer", referer)
                    .apply { cookie?.let { set("Cookie", it) } }
                    .build(),
            )
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) return@use null
            json.decodeFromString<ClientKeyRegistrationResponse>(responseBody)
        }
    }.getOrNull()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun registerClientKeyWithWebView(
        requestBody: String,
        referer: String,
    ): ClientKeyRegistrationResponse? {
        val result = WebViewResult<ClientKeyRegistrationResponse>()
        val handler = Handler(Looper.getMainLooper())
        val webViewRef = AtomicReference<WebView?>(null)

        fun completeRegistration(responseBody: String) {
            if (result.completed) return
            val registration = runCatching {
                json.decodeFromString<ClientKeyRegistrationResponse>(responseBody)
            }.getOrNull() ?: return
            if (isValidClientKeyRegistration(registration)) result.complete(registration)
        }

        handler.post {
            if (result.completed) return@post
            val webView = WebView(Injekt.get<Application>())
            webViewRef.set(webView)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.loadsImagesAutomatically = true
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.settings.userAgentString = DEFAULT_USER_AGENT
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun complete(responseBody: String) = completeRegistration(responseBody)
                },
                "NTKClientKeyBridge",
            )

            fun submitRegistration() {
                if (result.completed) return
                if (runCatching { webView.url?.let(::originOf) }.getOrNull() != originOf(referer)) return
                webView.evaluateJavascript(
                    """
                        (function() {
                            fetch('/api/client-key/register', {
                                method: 'POST',
                                credentials: 'include',
                                cache: 'no-store',
                                headers: { 'Content-Type': 'application/json' },
                                body: ${json.encodeToString(requestBody)},
                            })
                                .then(function(response) { return response.text(); })
                                .then(function(body) { window.NTKClientKeyBridge.complete(body); })
                                .catch(function() {});
                        })();
                    """.trimIndent(),
                    null,
                )
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (result.completed) return
                    if (runCatching { originOf(url) }.getOrNull() != originOf(referer)) return
                    listOf(1_000L, 3_000L, 6_000L, 9_000L).forEach { delay ->
                        handler.postDelayed(::submitRegistration, delay)
                    }
                    super.onPageFinished(view, url)
                }
            }
            webView.loadUrl(referer)
        }

        return awaitWebView(result, CLIENT_KEY_WEBVIEW_TIMEOUT_SECONDS, handler, webViewRef)
    }

    private fun <T> awaitWebView(
        result: WebViewResult<T>,
        timeoutSeconds: Long,
        handler: Handler,
        webViewRef: AtomicReference<WebView?>,
    ): T? = result.await(timeoutSeconds, TimeUnit.SECONDS) {
        // Each attempt owns its Handler. Cancel creation/navigation/collection callbacks,
        // then dispose on the UI thread, including when the waiting thread is interrupted.
        handler.removeCallbacksAndMessages(null)
        handler.post {
            handler.removeCallbacksAndMessages(null)
            webViewRef.getAndSet(null)?.run {
                try {
                    stopLoading()
                    removeJavascriptInterface("TrojanTunnel")
                    removeJavascriptInterface("NTKHtmlBridge")
                    removeJavascriptInterface("NTKClientKeyBridge")
                    webViewClient = WebViewClient()
                } finally {
                    destroy()
                }
            }
            android.webkit.CookieManager.getInstance().flush()
        }
    }

    private fun unsignedCoordinate(value: ByteArray): ByteArray {
        val bytes = value.dropWhile { it == 0.toByte() }.toByteArray()
        if (bytes.size > P256_COORDINATE_SIZE) throw IllegalArgumentException("Invalid P-256 coordinate")
        return ByteArray(P256_COORDINATE_SIZE).also { target ->
            bytes.copyInto(target, P256_COORDINATE_SIZE - bytes.size)
        }
    }

    private fun sha256Base64Url(value: String): String = base64Url(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private fun derEcdsaToP1363(der: ByteArray): ByteArray {
        var index = 0
        if (der.getOrNull(index++) != ASN1_SEQUENCE) throw IllegalArgumentException("Invalid ECDSA signature")
        val (sequenceLength, sequenceStart) = readAsn1Length(der, index)
        index = sequenceStart
        if (index + sequenceLength != der.size) throw IllegalArgumentException("Invalid ECDSA signature length")

        val r = readAsn1Integer(der, index)
        index = r.nextIndex
        val s = readAsn1Integer(der, index)
        if (s.nextIndex != der.size) throw IllegalArgumentException("Invalid ECDSA signature")

        return ByteArray(P256_SIGNATURE_SIZE).also { signature ->
            r.value.copyInto(signature, P256_COORDINATE_SIZE - r.value.size)
            s.value.copyInto(signature, P256_SIGNATURE_SIZE - s.value.size)
        }
    }

    private data class Asn1Integer(
        val value: ByteArray,
        val nextIndex: Int,
    )

    private fun readAsn1Integer(bytes: ByteArray, index: Int): Asn1Integer {
        if (bytes.getOrNull(index) != ASN1_INTEGER) throw IllegalArgumentException("Invalid ECDSA integer")
        val (length, valueStart) = readAsn1Length(bytes, index + 1)
        val valueEnd = valueStart + length
        if (valueEnd > bytes.size) throw IllegalArgumentException("Invalid ECDSA integer length")
        val encodedValue = bytes.copyOfRange(valueStart, valueEnd)
        if (
            encodedValue.isEmpty() ||
            encodedValue[0].toInt() and 0x80 != 0 ||
            (encodedValue.size > 1 && encodedValue[0] == 0.toByte() && encodedValue[1].toInt() and 0x80 == 0)
        ) {
            throw IllegalArgumentException("Invalid ECDSA integer encoding")
        }
        val value = encodedValue
            .dropWhile { it == 0.toByte() }
            .toByteArray()
        if (value.isEmpty() || value.size > P256_COORDINATE_SIZE) {
            throw IllegalArgumentException("Invalid ECDSA integer value")
        }
        return Asn1Integer(value, valueEnd)
    }

    private fun readAsn1Length(bytes: ByteArray, index: Int): Pair<Int, Int> {
        val first = bytes.getOrNull(index)?.toInt()?.and(0xff)
            ?: throw IllegalArgumentException("Missing ASN.1 length")
        if (first and ASN1_LONG_FORM_FLAG == 0) return first to index + 1

        val count = first and ASN1_LENGTH_MASK
        if (count !in 1..4 || index + count >= bytes.size) throw IllegalArgumentException("Invalid ASN.1 length")
        var length = 0
        repeat(count) { offset ->
            length = length shl 8 or (bytes[index + 1 + offset].toInt() and 0xff)
        }
        return length to index + 1 + count
    }

    private fun fetchPageImagesWithWebView(referer: String): PageImagesResponse {
        val request = GET(
            referer,
            headers.newBuilder().add(WEBVIEW_IMAGE_FALLBACK_HEADER, "true").build(),
        )
        return client.newCall(request).execute().use { response ->
            parseImageApiResponse(
                response.code,
                response.isSuccessful,
                response.body.string(),
                referer,
            )
        }
    }

    private fun issueNvCookie(referer: String): String {
        val userAgent = headers["User-Agent"] ?: DEFAULT_USER_AGENT

        fun buildRequest(): Request {
            val requestHeaders = headers.newBuilder()
                .set("Accept", "application/json")
                .set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .set("Cache-Control", "no-store")
                .set("Origin", originOf(referer))
                .set("Referer", referer)
                .set("User-Agent", userAgent)
                .apply {
                    webViewCookieHeader(referer)?.let { set("Cookie", it) }
                }
                .build()

            return Request.Builder()
                .url("${originOf(referer)}/api/nv-issue")
                .headers(requestHeaders)
                .post("".toRequestBody(null))
                .build()
        }

        client.newCall(buildRequest()).execute().use { firstResponse ->
            val nv = extractCookieValue(firstResponse.headers("Set-Cookie"), "nv")
            if (firstResponse.isSuccessful && nv != null && isValidNvCookie(nv)) {
                return nv
            }

            val firstBody = firstResponse.body.string()
            if (isCloudflareApiResponse(firstResponse.code, firstBody)) {
                warmUpCloudflare(referer)
                client.newCall(buildRequest()).execute().use { retryResponse ->
                    val retryNv = extractCookieValue(retryResponse.headers("Set-Cookie"), "nv")
                    if (retryResponse.isSuccessful && retryNv != null && isValidNvCookie(retryNv)) {
                        return retryNv
                    }
                    val retryBody = retryResponse.body.string().take(500)
                    throw Exception(
                        "NTK image API failed: nv cookie issue failed with HTTP ${retryResponse.code}: $retryBody",
                    )
                }
            }

            throw Exception(
                "NTK image API failed: nv cookie issue failed with HTTP ${firstResponse.code}: ${firstBody.take(500)}",
            )
        }
    }

    private fun warmUpCloudflare(referer: String) {
        val userAgent = headers["User-Agent"] ?: DEFAULT_USER_AGENT
        val request = Request.Builder()
            .url(referer)
            .headers(
                headers.newBuilder()
                    .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .set("Referer", originOf(referer))
                    .set("User-Agent", userAgent)
                    .build(),
            )
            .get()
            .build()

        loadChapterHtmlWithWebView(request)
    }

    private fun extractHtmlString(html: String, key: String): String? {
        val keyStart = html.indexOf(key).takeIf { it >= 0 } ?: return null
        val tail = html.substring(keyStart + key.length, minOf(html.length, keyStart + key.length + 4_096))
            .replace("\\u0022", "\"")
        val separator = tail.indexOf(':').takeIf { it >= 0 } ?: return null
        val valueTail = tail.substring(separator + 1).trimStart()

        return when {
            valueTail.startsWith("\\\"") -> {
                val valueEnd = valueTail.indexOf("\\\"", startIndex = 2)
                valueEnd.takeIf { it > 2 }?.let { valueTail.substring(2, it) }
            }
            valueTail.startsWith('"') -> {
                val valueEnd = valueTail.indexOf('"', startIndex = 1)
                valueEnd.takeIf { it > 1 }?.let { valueTail.substring(1, it) }
            }
            else -> null
        }
    }

    private fun isCloudflareChallengeHtml(html: String): Boolean {
        val lower = html.lowercase(Locale.US)
        return "challenge-platform" in lower ||
            "cf-mitigated" in lower ||
            "just a moment" in lower ||
            "보안 확인 수행 중" in html
    }

    private fun originOf(url: String): String = url.toHttpUrl().run { "$scheme://$host${if (port == 443) "" else ":$port"}" }

    private fun webViewCookieHeader(url: String, vararg extraCookies: String): String? {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookiePairs = listOfNotNull(
            cookieManager.getCookie(originOf(url)),
            cookieManager.getCookie(url),
        ) + extraCookies
        val cookieMap = linkedMapOf<String, String>()
        cookiePairs.flatMap { it.split(';') }
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { cookie ->
                val name = cookie.substringBefore('=').trim()
                val value = cookie.substringAfter('=').trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    cookieMap[name] = value
                }
            }

        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            .takeIf { it.isNotBlank() }
    }

    private fun fingerprintCookie(referer: String): String {
        val origin = originOf(referer)
        val cookieManager = android.webkit.CookieManager.getInstance()
        val existing = cookieManager.getCookie(origin)
            ?.split(';')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("ntk_fp=") }
            ?.substringAfter('=')
            ?.takeIf(FINGERPRINT_REGEX::matches)
        val fingerprint = existing ?: randomHex(FINGERPRINT_BYTES)
        val cookie = "ntk_fp=$fingerprint"
        cookieManager.setCookie(
            origin,
            "$cookie; Path=/; Max-Age=$FINGERPRINT_MAX_AGE_SECONDS; SameSite=Lax; Secure",
        )
        cookieManager.flush()
        return cookie
    }

    private fun randomHex(size: Int): String {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return buildString(size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
    }

    private fun isCloudflareApiResponse(code: Int, body: String): Boolean {
        val trimmed = body.trimStart()
        return code == HTTP_SERVICE_UNAVAILABLE ||
            isCloudflareChallengeHtml(body) ||
            trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
    }

    private fun extractCookieValue(setCookieHeaders: List<String>, name: String): String? {
        val prefix = "$name="
        return setCookieHeaders.firstNotNullOfOrNull { header ->
            header.split(';')
                .firstOrNull { it.trim().startsWith(prefix) }
                ?.trim()
                ?.removePrefix(prefix)
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun isValidNvCookie(value: String): Boolean = value.substringBefore('.').length >= 40

    private fun randomBase64Url(size: Int): String {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun base64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun hmacSha256Base64Url(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return Base64.encodeToString(
            mac.doFinal(message.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }

    override fun imageRequest(page: Page) = GET(
        page.imageUrl!!,
        headers.newBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            .set("Referer", page.url.ifBlank { rootUrl })
            .build(),
    )

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = NTKDomain.MANUAL_KEY
            title = "수동 접속 주소 (전체 HTTPS URL)"
            summary = domain.summary()
            setDefaultValue("")
            setOnPreferenceChangeListener { preference, newValue ->
                val value = newValue as? String ?: return@setOnPreferenceChangeListener false
                if (!domain.setManual(value)) return@setOnPreferenceChangeListener false
                text = domain.manualUrl().orEmpty()
                preference.summary = domain.summary()
                false
            }
        }.also(screen::addPreference)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val secureRandom = SecureRandom()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val HTML_MEDIA_TYPE = "text/html; charset=utf-8".toMediaType()
        private val CLOUDFLARE_HTML_ERROR_CODES = listOf(403, 503)
        private const val WEBVIEW_HTML_FALLBACK_HEADER = "X-WebView-Html-Fallback"
        private const val WEBVIEW_IMAGE_FALLBACK_HEADER = "X-WebView-Intercept"
        private const val WEBVIEW_IMAGE_ATTEMPT_TIMEOUT_SECONDS = 20L
        private const val WEBVIEW_ROOT_WARMUP_DELAY_MS = 3_000L
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_PRECONDITION_REQUIRED = 428
        private const val HTTP_SERVICE_UNAVAILABLE = 503
        private const val CLIENT_KEY_DEFAULT_TTL_MS = 60 * 60 * 1000L
        private const val CLIENT_KEY_RENEWAL_MARGIN_MS = 5 * 60 * 1000L
        private const val CLIENT_KEY_WEBVIEW_TIMEOUT_SECONDS = 12L
        private const val P256_COORDINATE_SIZE = 32
        private const val P256_SIGNATURE_SIZE = P256_COORDINATE_SIZE * 2
        private const val ASN1_LONG_FORM_FLAG = 0x80
        private const val ASN1_LENGTH_MASK = 0x7f
        private val ASN1_SEQUENCE = 0x30.toByte()
        private val ASN1_INTEGER = 0x02.toByte()
        private val CLIENT_KEY_ID_REGEX = Regex("^[A-Za-z0-9_-]{43}$")
        private val FINGERPRINT_REGEX = Regex("^[a-fA-F0-9]{16,}$")
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val FINGERPRINT_BYTES = 16
        private const val FINGERPRINT_MAX_AGE_SECONDS = 31_536_000
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val EPISODES_PER_PAGE = 100
        private const val MAX_EPISODE_API_PAGES = 1_000
        const val PAGE_SIZE = 49
    }
}
