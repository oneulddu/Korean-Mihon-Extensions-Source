package keiyoushi.utils

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AutomaticDomainInterceptorTest {
    private val strings = mutableMapOf("url" to "https://site1.com")
    private val longs = mutableMapOf("fetched" to 999_000L)
    private var lookups = 0
    private var manual: String? = null
    private val requests = mutableListOf<Request>()
    private val resolver = DynamicBaseUrlResolver(
        storage = object : BaseUrlStorage {
            override fun getString(key: String) = strings[key]
            override fun putString(key: String, value: String) {
                strings[key] = value
            }
            override fun getLong(key: String) = longs[key] ?: 0L
            override fun putLong(key: String, value: Long) {
                longs[key] = value
            }
            override fun remove(vararg keys: String) {
                keys.forEach {
                    strings.remove(it)
                    longs.remove(it)
                }
            }
        },
        keys = BaseUrlCacheKeys("url", "fetched", "attempted"),
        fallbackBaseUrl = { "https://site1.com" },
        isAllowedAutomaticUrl = { allowed(it.host) },
        discoverBaseUrl = {
            lookups++
            "https://site2.com"
        },
        redirectBaseUrl = { null },
        now = { 1_000_000L },
    )
    private fun allowed(host: String) = host.matches(Regex("site[0-9]+\\.com"))
    private fun client(migration: String? = null, reply: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            AutomaticDomainInterceptor(
                resolver = { resolver },
                manualBaseUrl = { manual },
                isAutomaticHost = ::allowed,
                rewrite = { it.rewriteBaseUrl(manual ?: resolver.resolve(), ::allowed) },
                migrationTarget = { migration },
            ),
        ).addInterceptor {
            requests += it.request()
            reply(it.request())
        }.build()
    private fun response(request: Request, code: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("test").body("body".toResponseBody()).build()
    private fun request(host: String = "site1.com") = Request.Builder().url("https://$host/chapter/1?q=2")
        .header("Referer", "https://site1.com/title/1").header("Origin", "https://site1.com").build()

    @Test fun freshDeadOriginRecoversOnceAndKeepsPathQueryAndHeaders() {
        client { if (it.url.host == "site1.com") throw IOException("reset") else response(it) }
            .newCall(request()).execute().use { assertEquals(200, it.code) }
        assertEquals(1, lookups)
        assertEquals(listOf("site1.com", "site2.com"), requests.map { it.url.host })
        assertEquals("https://site2.com/chapter/1?q=2", requests.last().url.toString())
        assertEquals("https://site2.com/title/1", requests.last().header("Referer"))
        assertEquals("https://site2.com", requests.last().header("Origin"))
    }

    @Test fun gatewayFailureRetriesButMissingChapterAndManualHostDoNot() {
        client { response(it, if (it.url.host == "site1.com") 503 else 200) }.newCall(request()).execute().close()
        assertEquals(1, lookups)
        requests.clear()
        client { response(it, 404) }.newCall(request()).execute().use { assertEquals(404, it.code) }
        assertEquals(1, requests.size)
        manual = "https://site1.com"
        requests.clear()
        client { response(it, 503) }.newCall(request()).execute().use { assertEquals(503, it.code) }
        assertEquals(1, requests.size)
        assertEquals(1, lookups)
    }

    @Test fun changedManualSettingPreventsRecoveryAndExternalCdnStaysUntouched() {
        client {
            manual = "https://manual.example"
            response(it, 503)
        }.newCall(request()).execute().close()
        assertEquals(0, lookups)
        assertEquals(1, requests.size)
        manual = null
        requests.clear()
        client { response(it, 503) }.newCall(request("cdn.example")).execute().close()
        assertEquals("cdn.example", requests.single().url.host)
        assertEquals(0, lookups)
    }

    @Test fun explicitMigrationAndSuccessfulHttpRedirectUpdateCache() {
        client(migration = "https://site2.com") { response(it) }.newCall(request()).execute().close()
        assertEquals(listOf("site1.com", "site2.com"), requests.map { it.url.host })
        assertEquals("https://site2.com", strings["url"])
        assertEquals(0, lookups)
        client { response(it.newBuilder().url("https://site3.com/chapter/1").build()) }.newCall(request()).execute().close()
        assertEquals("https://site3.com", strings["url"])
    }

    @Test fun cancelledCallDoesNotDiscoverOrRetry() {
        val client = OkHttpClient.Builder().addInterceptor(
            AutomaticDomainInterceptor({ resolver }, { null }, ::allowed, { it }),
        ).addInterceptor {
            it.call().cancel()
            throw IOException("cancelled")
        }.build()
        try {
            client.newCall(request()).execute()
            throw AssertionError("Expected cancellation")
        } catch (_: IOException) {
            assertEquals(0, lookups)
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun retryRecordsItsFinalRedirectWithoutAThirdRequest() {
        client { if (it.url.host == "site1.com") response(it, 503) else response(it.newBuilder().url("https://site3.com/chapter/1").build()) }
            .newCall(request()).execute().close()
        assertEquals(2, requests.size)
        assertEquals("https://site3.com", strings["url"])
    }

    @Test fun migrationBodyReadFailureClosesResponseAndRecovers() {
        var closed = false
        val client = OkHttpClient.Builder().addInterceptor(
            AutomaticDomainInterceptor(
                { resolver },
                { null },
                ::allowed,
                { it.rewriteBaseUrl(resolver.resolve(), ::allowed) },
                migrationTarget = { throw IOException("body reset") },
            ),
        ).addInterceptor {
            requests += it.request()
            if (requests.size == 1) {
                val body = object : okhttp3.ResponseBody() {
                    private val buffer = okio.Buffer().writeUtf8("incomplete")
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength() = 10L
                    override fun source(): okio.BufferedSource = buffer
                    override fun close() {
                        closed = true
                        super.close()
                    }
                }
                response(it.request()).newBuilder().body(body).build()
            } else {
                response(it.request())
            }
        }.build()
        client.newCall(request()).execute().close()
        assertTrue(closed)
        assertEquals(2, requests.size)
        assertEquals("site2.com", requests.last().url.host)
    }
}
