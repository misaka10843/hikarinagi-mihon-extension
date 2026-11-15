package eu.kanade.tachiyomi.extension.zh.hikarinagi

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RequestGateTest {
    private val request = Request.Builder().url("https://www.hikarinagi.org/api/v3/mangas").build()

    @Test
    fun holdsQueueUntilBodyIsReadAndGapHasElapsed() {
        val started = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        var ended = 0L
        var gap = 0L
        val client = client(RequestGate(gapMs = 80)) { req ->
            if (count.incrementAndGet() == 1) {
                val body = object : ResponseBody() {
                    private val buffered = object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            started.countDown()
                            check(release.await(3, TimeUnit.SECONDS))
                            ended = System.nanoTime()
                            return -1
                        }

                        override fun timeout() = Timeout.NONE
                        override fun close() = Unit
                    }.buffer()

                    override fun contentType() = null
                    override fun contentLength() = -1L
                    override fun source() = buffered
                }
                response(req).body(body).build()
            } else {
                gap = System.nanoTime() - ended
                response(req).build()
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit { client.newCall(request).execute().close() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val second = pool.submit {
                secondStarted.countDown()
                client.newCall(request).execute().close()
            }
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertEquals(1, count.get())
            release.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            assertTrue(gap >= TimeUnit.MILLISECONDS.toNanos(75))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun honorsRetryAfterAndStopsFurtherNetworkCalls() {
        var calls = 0
        var time = 0L
        val gate = RequestGate(gapMs = 0, now = { time })
        val client = client(gate) { req ->
            calls++
            if (calls == 1) response(req, 429).header("Retry-After", "180").build() else response(req).build()
        }
        assertThrows(IOException::class.java) { client.newCall(request).execute() }
        time = 179_000
        assertTrue(assertThrows(IOException::class.java) { client.newCall(request).execute() }.message!!.contains("1 秒"))
        assertEquals(1, calls)
        time = 180_000
        client.newCall(request).execute().close()
        assertEquals(2, calls)
    }

    @Test
    fun stopsEvenWhenTheBusinessRateLimitUsesStatus200() {
        var calls = 0
        val client = client(RequestGate(gapMs = 0)) { req ->
            calls++
            response(req).body(
                """{"success":false,"error":{"code":"COMMON_RATE_LIMITED"}}"""
                    .toResponseBody("application/json".toMediaType()),
            ).build()
        }
        repeat(2) { assertThrows(IOException::class.java) { client.newCall(request).execute() } }
        assertEquals(1, calls)
    }

    @Test
    fun decryptsAndAuthenticatesThePage() {
        val transfer = PageTransfer.create("7", "12", "13", "image/png")
        val nonce = Base64.getUrlDecoder().decode(transfer.payload)
        val key = ByteArray(32) { (nonce[it].toInt() xor nonce[it + 32].toInt()).toByte() }
        val iv = ByteArray(12) { it.toByte() }
        val plaintext = byteArrayOf(3, 5, 8)
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD("manga:page:7:12:13".toByteArray())
            iv + doFinal(plaintext)
        }
        val client = client(RequestGate(gapMs = 0)) { req -> response(req).body(encrypted.toResponseBody()).build() }
        val image = request.newBuilder().tag(PageTransfer::class.java, transfer).build()
        client.newCall(image).execute().use {
            assertEquals("image/png", it.header("Content-Type"))
            assertArrayEquals(plaintext, it.body!!.bytes())
        }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { transfer.decrypt(encrypted) }
    }

    private fun client(gate: RequestGate, respond: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(gate).addInterceptor(Interceptor { respond(it.request()) }).build()

    private fun response(request: Request, code: Int = 200): Response.Builder =
        Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(code)
            .message("test").body("".toResponseBody())
}
