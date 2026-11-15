package eu.kanade.tachiyomi.extension.zh.hikarinagi

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.ConnectionPool
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Suppress("OVERRIDE_DEPRECATION")
class Hikarinagi : HttpSource(), ConfigurableSource {
    override val name = "Hikarinagi"
    override val lang = "zh"
    override val baseUrl = "https://www.hikarinagi.org"
    override val supportsLatest = true

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val site = baseUrl.toHttpUrl()
        val jar = client.cookieJar
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = "hikarinagi_session_import"
            title = "Hikarinagi 登录 / 导入会话"
            summary = sessionSummary()
            dialogTitle = "导入 Hikarinagi 登录 Cookie"
            dialogMessage = "粘贴官网 Cookie 导入会话。输入「验证」检查当前会话，输入「清除」退出。验证后重新打开设置查看结果。"
            setDefaultValue("")
            setOnPreferenceChangeListener { preference, input ->
                val stored = suwayomiPreferences?.invoke(preference) as? SharedPreferences
                stored?.edit()?.remove(preference.key)?.commit()
                val value = input.toString().trim()
                if (value == "验证") {
                    verifySession()
                } else if (value == "清除") {
                    val expired = jar.loadForRequest(site).filter { it.name in sessionCookieNames }.map {
                        it.newBuilder().expiresAt(0).build()
                    }
                    jar.saveFromResponse(site, expired)
                    sessionCheck = null
                } else {
                    val imported = value.replaceFirst(Regex("^Cookie:\\s*", RegexOption.IGNORE_CASE), "")
                        .split(';').mapNotNull { field ->
                            val separator = field.indexOf('=')
                            if (separator < 1) return@mapNotNull null
                            val name = field.substring(0, separator).trim()
                            val token = field.substring(separator + 1).trim()
                            if (name !in sessionCookieNames || !cookieValue.matches(token)) return@mapNotNull null
                            Cookie.Builder().name(name).value(token).hostOnlyDomain(site.host)
                                .path("/").secure().httpOnly()
                                .expiresAt(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
                                .build()
                        }.distinctBy { it.name }
                    if (imported.any { it.name != "hikari_id_token" }) {
                        jar.saveFromResponse(site, imported)
                        sessionCheck = null
                    }
                }
                false
            }
        })
    }

    private fun sessionSummary(): String {
        val fingerprint = sessionFingerprint()
        val status = if (fingerprint == null) {
            "未保存登录会话"
        } else {
            sessionCheck?.takeIf { it.fingerprint == fingerprint }?.message ?: "已保存会话，尚未验证"
        }
        return "$status。粘贴 Cookie 导入，输入「验证」检查，输入「清除」退出。"
    }

    private fun sessionFingerprint(): String? {
        val cookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            .filter { it.name == "hikari_access_token" || it.name == "hikari_refresh_token" }
            .sortedBy { it.name }
        if (cookies.isEmpty()) return null
        val values = cookies.joinToString("\n") { "${it.name}=${it.value}" }
        return java.util.Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(values.toByteArray()),
        )
    }

    private fun verifySession() {
        val fingerprint = sessionFingerprint() ?: return
        val pending = SessionCheck(fingerprint, "正在验证")
        if (sessionCheck == pending) return
        sessionCheck = pending
        client.newCall(api("/user/me")).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val result = if (e is RequestGate.SessionRejectedException) "官网拒绝会话，请重新登录"
                else "暂时无法验证，请稍后重试"
                if (sessionCheck === pending) sessionCheck = SessionCheck(fingerprint, result)
            }

            override fun onResponse(call: Call, response: Response) {
                val user = runCatching { response.json() as? JSONObject }.getOrNull()
                val account = user?.text("nickname", "name")?.take(60)
                val result = if (user?.optLong("id", 0)?.let { it > 0 } == true) {
                    "最近一次验证成功" + (account?.let { "，账号 $it" } ?: "")
                } else {
                    "官网未返回有效账号信息"
                }
                val current = sessionFingerprint()
                if (sessionCheck === pending && current != null) sessionCheck = SessionCheck(current, result)
            }
        })
    }

    private data class SessionCheck(val fingerprint: String, val message: String)

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionPool(ConnectionPool())
            .cache(null)
            .addInterceptor(gate)
            .addNetworkInterceptor(RequestGate.singleExchange)
            .build()
    }

    override fun popularMangaRequest(page: Int) = catalogRequest(page, "heat")
    override fun latestUpdatesRequest(page: Int) = catalogRequest(page, "latest_chapter_at")
    override fun popularMangaParse(response: Response) = catalogPage(response)
    override fun latestUpdatesParse(response: Response) = catalogPage(response)

    private fun catalogRequest(page: Int, sort: String): Request = api(
        "/mangas",
        "page" to page.toString(),
        "page_size" to "20",
        "readable" to "true",
        "sort_field" to sort,
        "sort_order" to "desc",
    )

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        if (query.isBlank()) {
            popularMangaRequest(page)
        } else {
            api("/search", "q" to query.trim(), "types" to "manga", "page" to page.toString(), "page_size" to "20")
        }

    override fun searchMangaParse(response: Response): MangasPage = catalogPage(response)

    private fun catalogPage(response: Response): MangasPage {
        val result = response.json() as JSONObject
        val meta = result.getJSONObject("meta")
        val mangas = result.getJSONArray("items").objects()
            .filter { it.optString("type", "manga") == "manga" }
            .map(::parseManga)
        return MangasPage(mangas, meta.getInt("page") < meta.getInt("total_pages"))
    }

    override fun mangaDetailsRequest(manga: SManga) = api(manga.url)
    override fun mangaDetailsParse(response: Response) = parseManga(response.json() as JSONObject)
    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url
    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    private fun parseManga(data: JSONObject): SManga = SManga.create().apply {
        url = "/mangas/${data.getLong("id")}"
        title = data.text("name_cn", "name", "title") ?: url
        description = data.text("summary_cn", "summary", "summary_en")
        val cover = data.optJSONArray("covers")?.optJSONObject(0)?.optJSONObject("media")
            ?: data.optJSONObject("cover")
        thumbnail_url = cover?.text("src")?.let { "https://images.yurari.moe/".toHttpUrl().resolve(it) }
            ?.takeIf { it.isHttps }?.toString()
        author = data.optJSONObject("author")?.text("name_cn", "name")
        status = when (data.text("serial_status")) {
            "SERIALIZING" -> SManga.ONGOING
            "FINISHED" -> SManga.COMPLETED
            "PAUSED" -> SManga.ON_HIATUS
            "ABANDONED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListRequest(manga: SManga) = api("${manga.url}/chapters")

    override fun chapterListParse(response: Response): List<SChapter> {
        val manga = response.request.url.pathSegments[3]
        return (response.json() as JSONArray).objects()
            .asReversed()
            .filter { it.optBoolean("readable") }
            .map { item ->
                val number = item.text("chapter_number")
                val volume = item.text("volume_number")
                val type = item.text("chapter_type")
                val label = item.text("title") ?: when {
                    type == "VOLUME" && volume != null -> "第 ${volume} 卷"
                    type == "VOLUME" -> "单行本"
                    number != null -> "第 ${number} 话"
                    type == "EXTRA" -> "番外"
                    else -> "短篇"
                }
                val ordinal = item.optInt("ordinal", 1)
                val suffix = if (ordinal > 1) " ($ordinal)" else ""
                SChapter.create().apply {
                    url = "/mangas/$manga/read/${item.getLong("id")}"
                    name = label + suffix
                    chapter_number = (if (type == "VOLUME") volume else number)?.toFloatOrNull() ?: -1F
                    date_upload = parseDate(item.text("publication_date", "created_at"))
                }
            }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val segments = getChapterUrl(chapter).toHttpUrl().pathSegments
        return api("/reader/mangas/${segments[1]}/chapters/${segments[3]}")
    }

    override fun pageListParse(response: Response): List<Page> {
        val endpoint = response.request.url
        val pages = (response.json() as JSONObject).getJSONArray("pages").objects()
        if (pages.isEmpty()) throw IOException("Hikarinagi 本章没有可读页面")
        return pages.sortedBy { it.getInt("page_number") }.mapIndexed { index, item ->
            val image = endpoint.newBuilder().addPathSegments("pages/${item.getLong("id")}/content").build()
            Page(index, item.text("mime_type") ?: "", image.toString())
        }
    }

    override fun imageRequest(page: Page): Request {
        val url = requireNotNull(page.imageUrl).toHttpUrl()
        val match = pageEndpoint.matchEntire(url.encodedPath)
        if (url.host != baseUrl.toHttpUrl().host || !url.isHttps || url.port != 443 || match == null) {
            throw IOException("Hikarinagi 图片地址无效")
        }
        val (manga, chapter, id) = match.destructured
        val mime = page.url.takeIf { it.startsWith("image/") } ?: "application/octet-stream"
        val transfer = PageTransfer.create(manga, chapter, id, mime)
        return Request.Builder().url(url).headers(headers)
            .header("Accept", "application/octet-stream")
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/mangas/$manga/read/$chapter")
            .tag(PageTransfer::class.java, transfer)
            .post(JSONObject().put("p", transfer.payload).toString().toRequestBody(jsonType))
            .build()
    }

    override fun imageUrlParse(response: Response): String = response.use {
        throw IOException("Hikarinagi 缺少页清单中的图片地址")
    }

    private fun api(path: String, vararg query: Pair<String, String>): Request {
        val url = "$baseUrl/api/v3$path".toHttpUrl().newBuilder()
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return Request.Builder().url(url.build()).headers(headers)
            .header("Accept", "application/json").build()
    }

    private fun Response.json(): Any = use { response ->
        val text = response.body?.string() ?: throw IOException("Hikarinagi 响应为空")
        val value = try {
            JSONTokener(text).nextValue()
        } catch (e: org.json.JSONException) {
            throw IOException("Hikarinagi 返回了无效的 JSON", e)
        }
        when {
            value !is JSONObject || !value.has("success") -> value
            value.optBoolean("success") -> value.get("data")
            else -> throw IOException(value.optJSONObject("error")?.text("message") ?: "Hikarinagi API 请求失败")
        }
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    private fun JSONObject.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> if (isNull(key)) null else optString(key).takeIf(String::isNotBlank) }

    private fun parseDate(value: String?): Long {
        if (value == null) return 0
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
            runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
                .getOrDefault(0)
        }
    }

    companion object {
        private val gate = RequestGate()
        @Volatile private var sessionCheck: SessionCheck? = null
        private val suwayomiPreferences = runCatching { Preference::class.java.getMethod("getSharedPreferences") }.getOrNull()
        private val sessionCookieNames = setOf("hikari_access_token", "hikari_refresh_token", "hikari_id_token")
        private val cookieValue = Regex("[A-Za-z0-9._~%+=/-]+")
        private val jsonType = "application/json".toMediaType()
        private val pageEndpoint = Regex("/api/v3/reader/mangas/([0-9]+)/chapters/([0-9]+)/pages/([0-9]+)/content")
    }
}
