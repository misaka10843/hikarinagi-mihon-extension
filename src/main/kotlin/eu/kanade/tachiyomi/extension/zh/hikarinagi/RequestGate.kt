package eu.kanade.tachiyomi.extension.zh.hikarinagi

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

internal class RequestGate(
    private val gapMs: Long = 1_500,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallNow: () -> Long = System::currentTimeMillis,
) : Interceptor {
    private val lock = ReentrantLock(true)
    private var nextRequest: Long? = null
    private var cooldown: Long? = null
    private var failures = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                checkCanceled(chain)
            }
            try {
                while (true) {
                    checkCanceled(chain)
                    cooldown?.let { deadline ->
                        val wait = deadline - now()
                        if (wait > 0) throw IOException("Hikarinagi 正在暂停请求，请在 ${(wait + 999) / 1_000} 秒后重试")
                        cooldown = null
                    }
                    val wait = nextRequest?.minus(now()) ?: 0
                    if (wait <= 0) break
                    Thread.sleep(wait.coerceAtMost(100))
                }
                val request = chain.request()
                val transfer = request.tag(PageTransfer::class.java)
                try {
                    val outbound = request.newBuilder().tag(NetworkAttempt::class.java, NetworkAttempt()).build()
                    return chain.proceed(outbound).use { response ->
                        val body = response.body
                        val mime = body?.contentType()
                        val json = mime?.subtype?.endsWith("json") == true
                        val urgent = response.code == 429 || response.code == 503 ||
                            response.header("Retry-After") != null ||
                            response.header("X-RateLimit-Remaining") == "0"
                        if (urgent) pause(response, 120)
                        val bytes = body?.readBounded() ?: ByteArray(0)
                        val apiRequest = request.url.encodedPath.startsWith("/api/v3/")
                        val errorCode = if (apiRequest && json) {
                            runCatching {
                                val envelope = JSONObject(bytes.decodeToString())
                                if (envelope.optBoolean("success", true)) null
                                else envelope.optJSONObject("error")?.optString("code")
                            }.getOrNull()
                        } else {
                            null
                        }
                        when {
                            response.code == 429 || errorCode == "COMMON_RATE_LIMITED" -> {
                                if (!urgent) pause(response, 120)
                                throw SiteResponseException("Hikarinagi 已限制请求，请稍后重试")
                            }
                            response.code == 503 || response.code == 502 || response.code == 504 ||
                                errorCode == "SITE_MAINTENANCE" || errorCode == "COMMON_SERVICE_UNAVAILABLE" -> {
                                if (!urgent) pause(response, 60)
                                throw SiteResponseException("Hikarinagi 暂时不可用，扩展已暂停请求")
                            }
                            errorCode == "USER_BANNED" || errorCode == "AUTH_FAMILY_BLOCKED" -> {
                                pause(response, 1800)
                                throw SiteResponseException("Hikarinagi 限制了当前会话，请在 WebView 查看站点提示")
                            }
                            response.code == 401 || errorCode in loginErrors -> {
                                throw SessionRejectedException()
                            }
                            response.code == 403 || errorCode == "AUTH_FORBIDDEN" -> {
                                pause(response, 300)
                                throw SiteResponseException("Hikarinagi 拒绝访问，扩展已暂停请求。请在 WebView 确认权限")
                            }
                            response.isRedirect -> {
                                throw SiteResponseException("Hikarinagi 返回重定向，请在 WebView 确认站点状态")
                            }
                        }
                        if (!response.isSuccessful) {
                            throw SiteResponseException("Hikarinagi 请求失败（HTTP ${response.code}）")
                        }
                        if (transfer != null && (json || mime?.type == "text") ||
                            apiRequest && transfer == null && mime?.subtype == "html"
                        ) {
                            pause(response, 60)
                            throw SiteResponseException("Hikarinagi 返回了异常响应，扩展已暂停请求。请在 WebView 确认站点状态")
                        }
                        val decoded = transfer?.decrypt(bytes)
                        if (!urgent) failures = 0
                        response.newBuilder()
                            .removeHeader("Content-Encoding")
                            .header("Content-Length", (decoded ?: bytes).size.toString())
                            .apply { if (transfer != null) header("Content-Type", transfer.mimeType) }
                            .body((decoded ?: bytes).toResponseBody(transfer?.mimeType?.toMediaTypeOrNull() ?: mime))
                            .build()
                    }
                } catch (e: IOException) {
                    if (e !is SiteResponseException && !chain.call().isCanceled() && cooldown == null) {
                        pause(null, 30)
                    }
                    throw e
                } finally {
                    nextRequest = now() + gapMs
                }
            } finally {
                lock.unlock()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Hikarinagi 请求已取消")
        }
    }

    private fun pause(response: Response?, seconds: Long) {
        val local = seconds * (1L shl failures.coerceAtMost(4))
        failures = (failures + 1).coerceAtMost(4)
        val retryAfter = response?.header("Retry-After")?.let(::retrySeconds) ?: 0
        val reset = response?.header("X-RateLimit-Reset")?.let(::resetSeconds) ?: 0
        val wait = maxOf(local, retryAfter, reset).coerceAtMost(Long.MAX_VALUE / 4_000)
        val deadline = now() + wait * 1_000
        cooldown = maxOf(cooldown ?: deadline, deadline)
    }

    private fun retrySeconds(value: String): Long? = value.trim().toLongOrNull() ?: runCatching {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() -
            wallNow() / 1_000
    }.getOrNull()

    private fun resetSeconds(value: String): Long? = value.trim().toLongOrNull()?.let {
        when {
            it >= 1_000_000_000_000 -> (it - wallNow()) / 1_000 + 1
            it >= 1_000_000_000 -> it - wallNow() / 1_000
            else -> it
        }
    }

    private fun checkCanceled(chain: Interceptor.Chain) {
        if (chain.call().isCanceled()) throw InterruptedIOException("Hikarinagi 请求已取消")
    }

    private fun ResponseBody.readBounded(): ByteArray {
        val limit = 32L * 1024 * 1024
        if (contentLength() > limit) throw IOException("Hikarinagi 响应超过 32 MiB")
        val buffer = Buffer()
        val source = source()
        while (buffer.size <= limit) {
            val toRead = minOf(8192, limit + 1 - buffer.size)
            if (source.read(buffer, toRead) == -1L) return buffer.readByteArray()
        }
        throw IOException("Hikarinagi 响应超过 32 MiB")
    }

    internal open class SiteResponseException(message: String) : IOException(message)
    internal class SessionRejectedException :
        SiteResponseException("请在阅读器的 Hikarinagi 图源 WebView 登录官网，然后重试")

    companion object {
        private val loginErrors = setOf("AUTH_UNAUTHENTICATED", "AUTH_TOKEN_EXPIRED", "AUTH_TOKEN_INVALID")

        val singleExchange = Interceptor { chain ->
            val request = chain.request()
            request.tag(NetworkAttempt::class.java)?.start()
            val response = chain.proceed(request)
            if (response.code == 503 && response.header("Retry-After") == "0") {
                response.newBuilder().header("Retry-After", "1").build()
            } else {
                response
            }
        }
    }

    private class NetworkAttempt {
        private val started = AtomicBoolean()

        fun start() {
            if (!started.compareAndSet(false, true)) throw IOException("Hikarinagi 已停止自动重试")
        }
    }
}
