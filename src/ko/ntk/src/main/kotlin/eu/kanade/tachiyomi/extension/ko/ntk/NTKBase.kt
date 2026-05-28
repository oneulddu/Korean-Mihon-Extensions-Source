package eu.kanade.tachiyomi.extension.ko.ntk

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    protected val rootUrl: String
        get() {
            val stored = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
            val domainNumber = stored.trimStart('0').ifEmpty { "0" }
            if (domainNumber != stored) {
                preferences.edit().putString(PREF_DOMAIN_KEY, domainNumber).apply()
            }
            return "https://sbxh$domainNumber.com"
        }

    protected open val webViewPath: String get() = contentKind
    override val baseUrl: String get() = "$rootUrl/$webViewPath"

    override fun mangaDetailsRequest(manga: SManga) = GET(rootUrl + manga.url, headers)
    override fun chapterListRequest(manga: SManga) = GET(rootUrl + manga.url, headers)

    override fun pageListRequest(chapter: SChapter) = GET(
        url = rootUrl + chapter.url,
        headers = headers.newBuilder().add("X-WebView-Intercept", "true").build(),
    )

    private val imageBridgeScript = """
        (function() {
            function sendPayloadText(text) {
                try {
                    const payload = JSON.parse(text);
                    if (payload && Array.isArray(payload.images) && payload.images.length > 0) {
                        window.TrojanTunnel.exfiltrateApi(JSON.stringify(payload));
                    }
                } catch (_) {
                    // Ignore non-image API responses.
                }
            }

            function absoluteUrl(src) {
                try {
                    return new URL(src, window.location.href).href;
                } catch (_) {
                    return src;
                }
            }

            function imageSrc(img) {
                const srcset = img.getAttribute("srcset") || img.getAttribute("data-srcset") || "";
                const firstSrcset = srcset.split(",").map(item => item.trim().split(/\s+/)[0]).find(Boolean);
                return img.currentSrc ||
                    img.src ||
                    img.getAttribute("data-src") ||
                    img.getAttribute("data-original") ||
                    img.getAttribute("data-lazy-src") ||
                    img.getAttribute("data-url") ||
                    firstSrcset ||
                    img.getAttribute("src");
            }

            function isPageAlt(img) {
                return /^page\s*\d+$/i.test((img.getAttribute("alt") || "").trim());
            }

            function isBadImage(img, src) {
                const meta = [
                    src,
                    img.getAttribute("alt"),
                    img.getAttribute("class"),
                    img.getAttribute("id"),
                    img.closest("a") && img.closest("a").href,
                ].filter(Boolean).join(" ").toLowerCase();

                if (!src || /^(data|blob):/i.test(src)) return true;
                if (/\.(svg|ico)(\?|#|$)/i.test(src)) return true;
                if (/(logo|icon|avatar|profile|thumb|thumbnail|placeholder|comment|emoji|emoticon|platform)/i.test(meta)) return true;

                if (isPageAlt(img)) return false;

                // 광고 배너는 대체로 매우 넓고 낮거나, 외부 도박/배너 키워드를 포함합니다.
                if (/(banner|advert|\bad\b|casino|bet|slot|sports|telegram|register|code=|agentcode|referral)/i.test(meta)) return true;

                const width = img.naturalWidth || img.width || img.clientWidth || 0;
                const height = img.naturalHeight || img.height || img.clientHeight || 0;
                if (width > 0 && height > 0) {
                    if (width < 240 || height < 240) return true;
                    if (width / Math.max(height, 1) > 3.2) return true;
                }

                return false;
            }

            function uniqueImages(srcs) {
                const seen = new Set();
                return srcs
                    .filter(Boolean)
                    .map(absoluteUrl)
                    .filter(src => {
                        if (!src || seen.has(src)) return false;
                        seen.add(src);
                        return true;
                    });
            }

            function rememberImages(srcs) {
                const images = uniqueImages(srcs);
                const current = window.__ntkBestImages || [];
                const merged = uniqueImages(current.concat(images));
                if (merged.length >= current.length) {
                    window.__ntkBestImages = merged;
                }
                sendCandidateImages(images);
                return window.__ntkBestImages || [];
            }

            function sendCandidateImages(srcs) {
                const images = uniqueImages(srcs);
                if (images.length > 0) {
                    window.TrojanTunnel.collectImages(JSON.stringify(images));
                }
            }

            function emitBestImages() {
                const images = window.__ntkBestImages || [];
                sendCandidateImages(images);
            }

            function requestUrl(input) {
                if (!input) return "";
                if (typeof input === "string") return input;
                if (input.url) return input.url;
                return input.toString ? input.toString() : "";
            }

            function shouldCapture(url) {
                return /\/api\/[^?#]*(image|page|chapter|episode)/i.test(url || "");
            }

            window.__ntkCollectPageImages = function() {
                const allImages = Array.from(document.querySelectorAll("img"));
                const pageAltImages = allImages
                    .filter(isPageAlt)
                    .map(imageSrc);

                if (pageAltImages.length > 0) {
                    rememberImages(pageAltImages);
                }

                const selectors = [
                    "main img",
                    "article img",
                    ".viewer img",
                    ".episode-viewer img",
                    "[class*='viewer'] img",
                    "[class*='episode'] img",
                    "[class*='chapter'] img",
                    "img[data-src]",
                    "img[data-original]",
                    "img[src*='http']",
                ];

                const candidates = Array.from(document.querySelectorAll(selectors.join(",")))
                    .filter(img => !isBadImage(img, imageSrc(img)))
                    .map(imageSrc);

                rememberImages(candidates);
            };

            window.__ntkScrollAndCollect = function() {
                const maxY = Math.max(
                    document.documentElement.scrollHeight || 0,
                    document.body && document.body.scrollHeight || 0,
                );
                const step = Math.max(Math.floor((window.innerHeight || 900) * 0.85), 600);
                let y = window.scrollY || 0;

                window.__ntkCollectPageImages();
                const timer = window.setInterval(function() {
                    y = Math.min(y + step, maxY);
                    window.scrollTo(0, y);
                    window.__ntkCollectPageImages();
                    if (y >= maxY) {
                        window.clearInterval(timer);
                        window.setTimeout(window.__ntkCollectPageImages, 400);
                    }
                }, 450);
            };

            if (!window.__ntkImageBridgeInstalled) {
                window.__ntkImageBridgeInstalled = true;
                window.__ntkBestImages = [];

                if (window.fetch) {
                    const originalFetch = window.fetch.bind(window);
                    window.fetch = async function() {
                        const response = await originalFetch.apply(window, arguments);
                        const url = requestUrl(arguments[0]);
                        if (shouldCapture(url)) {
                            response.clone().text().then(sendPayloadText).catch(function() {});
                        }
                        return response;
                    };
                }

                if (window.XMLHttpRequest) {
                    const originalOpen = XMLHttpRequest.prototype.open;
                    const originalSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__ntkUrl = requestUrl(url);
                        return originalOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        this.addEventListener("load", function() {
                            if (shouldCapture(this.__ntkUrl)) {
                                sendPayloadText(this.responseText || "");
                            }
                        });
                        return originalSend.apply(this, arguments);
                    };
                }
            }

            [0, 500, 1200, 2500, 4500, 7000, 10000, 14000].forEach(delay => {
                window.setTimeout(window.__ntkCollectPageImages, delay);
            });
            window.setTimeout(emitBestImages, 15000);
        })();
    """.trimIndent()

    private val imageBridgeCollectScript = """
        (function() {
            if (window.__ntkCollectPageImages) {
                window.__ntkCollectPageImages();
                window.setTimeout(window.__ntkCollectPageImages, 500);
                window.setTimeout(window.__ntkCollectPageImages, 1500);
            }
            if (window.__ntkScrollAndCollect) {
                window.setTimeout(window.__ntkScrollAndCollect, 800);
            }
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private val trojanWebViewInterceptor = Interceptor { chain ->
        val request = chain.request()

        if (request.header("X-WebView-Intercept") == null) {
            return@Interceptor chain.proceed(request)
        }

        val finalPayload = AtomicReference<String?>(null)
        val isComplete = AtomicBoolean(false)
        val capturedImageUrls = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())

        fun imageListPayload(urls: List<String>): String {
            val images = urls.distinct().joinToString(",") { url ->
                "{\"src\":${json.encodeToString(url)}}"
            }
            return "{\"images\":[$images]}"
        }

        fun completeWith(payload: String) {
            if (payload.isNotBlank() && isComplete.compareAndSet(false, true)) {
                finalPayload.set(payload)
                latch.countDown()
            }
        }

        fun isBlockedImageUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            if (lower.startsWith("data:") || lower.startsWith("blob:")) return true
            if (
                "logo" in lower || "icon" in lower || "avatar" in lower || "profile" in lower ||
                "thumb" in lower || "thumbnail" in lower
            ) {
                return true
            }
            if (
                "banner" in lower || "advert" in lower || "casino" in lower || "bet" in lower ||
                "telegram" in lower || "agentcode" in lower || "referral" in lower
            ) {
                return true
            }
            return false
        }

        fun shouldKeepCapturedImage(url: String, accept: String = ""): Boolean {
            if (isBlockedImageUrl(url)) return false
            val lower = url.lowercase(Locale.US)
            if (accept.lowercase(Locale.US).contains("image")) return true
            if ("toonflix.app" in lower || "11toon" in lower) return true
            return lower.contains("image") ||
                lower.contains("webtoon") ||
                lower.contains("manhwa") ||
                lower.contains("page") ||
                lower.matches(Regex(".*\\.(jpg|jpeg|png|webp|avif)(\\?.*)?$"))
        }

        fun collectImageUrls(payload: String) {
            if (payload.isBlank()) return
            runCatching {
                json.decodeFromString<List<String>>(payload)
            }.getOrNull()?.forEach { url ->
                if (shouldKeepCapturedImage(url)) {
                    capturedImageUrls.addIfAbsent(url)
                }
            }
        }

        fun completeWithCapturedImages() {
            if (!isComplete.get() && capturedImageUrls.isNotEmpty()) {
                completeWith(imageListPayload(capturedImageUrls))
            }
        }

        handler.post {
            val context = Injekt.get<Application>()
            val webView = WebView(context)

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.loadsImagesAutomatically = true
            webView.settings.blockNetworkImage = false
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, 1080, 1920)

            webView.settings.userAgentString = request.header("User-Agent")
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun exfiltrateApi(html: String) {
                        completeWith(html)
                    }

                    @JavascriptInterface
                    fun collectImages(imagesJson: String) {
                        collectImageUrls(imagesJson)
                    }
                },
                "TrojanTunnel",
            )

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    view.evaluateJavascript(imageBridgeScript, null)

                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(imageBridgeScript, null)
                    view.evaluateJavascript(imageBridgeCollectScript, null)
                    handler.postDelayed({ view.evaluateJavascript(imageBridgeCollectScript, null) }, 3_000)
                    handler.postDelayed({ view.evaluateJavascript(imageBridgeCollectScript, null) }, 7_000)
                    handler.postDelayed(
                        {
                            view.evaluateJavascript(
                                "if (window.__ntkBestImages) window.TrojanTunnel.collectImages(JSON.stringify(window.__ntkBestImages));",
                                null,
                            )
                        },
                        18_000,
                    )

                    super.onPageFinished(view, url)
                }

                override fun shouldInterceptRequest(view: WebView, webRequest: WebResourceRequest): WebResourceResponse? {
                    val imageUrl = webRequest.url?.toString().orEmpty()
                    val accept = webRequest.requestHeaders?.get("Accept").orEmpty()
                    if (shouldKeepCapturedImage(imageUrl, accept)) {
                        capturedImageUrls.addIfAbsent(imageUrl)
                    }
                    return super.shouldInterceptRequest(view, webRequest)
                }
            }

            webView.loadUrl(request.url.toString())

            handler.postDelayed({ webView.evaluateJavascript(imageBridgeScript, null) }, 1_000)
            handler.postDelayed({ webView.evaluateJavascript(imageBridgeCollectScript, null) }, 4_000)
            handler.postDelayed({ webView.evaluateJavascript(imageBridgeCollectScript, null) }, 9_000)
            handler.postDelayed({ completeWithCapturedImages() }, 23_000)
        }

        latch.await(28, TimeUnit.SECONDS)

        completeWithCapturedImages()

        finalPayload.get()?.let {
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

        throw Exception("WebView timed out loading ${request.url}")
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
        val response = chain.proceed(request)

        val finalUrl = response.request.url.toString()
        val matchResult = """sbxh(\d+)\.com""".toRegex().find(finalUrl)

        if (matchResult != null) {
            val newDomainNumber = matchResult.groupValues[1]
            val currentDomainNumber = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
            if (newDomainNumber != currentDomainNumber) {
                preferences.edit().putString(PREF_DOMAIN_KEY, newDomainNumber).apply()
            }
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
        val src: String,
    )

    protected fun htmlCardParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.card-grid > a.card").map { element ->
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

        val rscData = document.select("script")
            .map { it.data() }
            .firstOrNull { "allCards" in it }
            ?: return MangasPage(emptyList(), false)

        val rawContent = rscData
            .substringAfter("[1,\"")
            .substringBeforeLast("\"])")

        val unescaped = rawContent
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\/", "/")

        val marker = "\"allCards\":"
        val markerIdx = unescaped.indexOf(marker)
        if (markerIdx < 0) return MangasPage(emptyList(), false)

        val arrayStart = markerIdx + marker.length
        var depth = 0
        var arrayEnd = arrayStart
        for (i in arrayStart until unescaped.length) {
            when (unescaped[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        arrayEnd = i + 1
                        break
                    }
                }
            }
        }

        val jsonArrayStr = unescaped.substring(arrayStart, arrayEnd)
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
        return document.select("ul.ep-list-v2 > li.ep-row-v2").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.select("a.ep-row-v2-link").attr("href"))
                name = element.select("div.ep-row-v2-title strong").text()
                date_upload = dateFormat.tryParse(element.select("span.ep-row-v2-date").text())
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<PageImagesResponse>()
        val referer = response.request.url.toString()
        return data.images.mapIndexed { i, image ->
            val imageUrl = response.request.url.resolve(image.src)?.toString() ?: image.src
            Page(i, referer, imageUrl)
        }
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
            key = PREF_DOMAIN_KEY
            title = "도메인 번호 (sbxh#.com)"
            summary = "현재 도메인 번호: ${preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)}\n숫자만 입력하세요 (예: 1, 2, 300)"
            setDefaultValue(PREF_DOMAIN_DEFAULT)
        }.also(screen::addPreference)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val PREF_DOMAIN_KEY = "pref_domain_key"
        private const val PREF_DOMAIN_DEFAULT = "3"
        const val PAGE_SIZE = 49
    }
}
