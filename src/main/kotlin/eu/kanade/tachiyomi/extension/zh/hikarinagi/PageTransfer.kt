package eu.kanade.tachiyomi.extension.zh.hikarinagi

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class PageTransfer(
    private val nonce: ByteArray,
    private val aad: String,
    val mimeType: String,
) {
    val payload: String
        get() = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)

    fun decrypt(body: ByteArray): ByteArray {
        if (body.size < 28) throw IOException("Hikarinagi 图片响应不完整")
        val key = ByteArray(32) { (nonce[it].toInt() xor nonce[it + 32].toInt()).toByte() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, body, 0, 12))
                updateAAD(aad.toByteArray(Charsets.UTF_8))
                doFinal(body, 12, body.size - 12)
            }
        } catch (e: GeneralSecurityException) {
            throw IOException("Hikarinagi 图片校验失败", e)
        }
    }

    companion object {
        private val random = SecureRandom()

        fun create(manga: String, chapter: String, page: String, mimeType: String): PageTransfer {
            val nonce = ByteArray(64).also(random::nextBytes)
            return PageTransfer(nonce, "manga:page:$manga:$chapter:$page", mimeType)
        }
    }
}
