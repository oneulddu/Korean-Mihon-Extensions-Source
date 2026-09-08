package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.schedulers.Schedulers
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WolfCancellationTest {
    @Test
    fun unsubscribeCancelsHttpAndReleasesTheBrowseLock() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val call = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
            val cache = WolfBrowseCache<String>()
            val finished = CountDownLatch(1)
            val subscription = cancellableWolfCall({ call }) {
                try {
                    cache.page("browse", 1) {
                        it.execute().use { response -> listOf(response.body.string()) }
                    }
                } finally {
                    finished.countDown()
                }
            }.subscribeOn(Schedulers.io()).subscribe({}, {})
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    assertTrue(socket.getInputStream().read() >= 0)
                    subscription.unsubscribe()
                    assertTrue(call.isCanceled())
                    assertTrue(finished.await(5, TimeUnit.SECONDS))
                    assertTrue(cache.page("browse", 1) { listOf("new") }.first == listOf("new"))
                }
            } finally {
                subscription.unsubscribe()
                call.cancel()
            }
        }
    }
}
