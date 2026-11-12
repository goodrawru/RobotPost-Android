package ru.robotpost.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the YouTube browser session encrypted with an Android Keystore key.
 * A short-lived Netscape cookie file is created only while yt-dlp is running.
 */
object YouTubeAuthStore {
    private const val KEY_ALIAS = "robotpost.youtube.session.v1"
    private const val ENCRYPTED_FILE = "youtube-session.enc"
    private val authCookieNames = setOf(
        "SAPISID", "SID", "HSID", "SSID", "APISID",
        "__Secure-1PSID", "__Secure-3PSID"
    )

    fun saveFromWebView(context: Context, cookiesByHost: Map<String, String>): Boolean {
        val rows = buildString {
            append("# Netscape HTTP Cookie File\n")
            cookiesByHost.forEach { (host, header) ->
                val domain = when (host.lowercase()) {
                    "youtube.com" -> ".youtube.com"
                    "google.com" -> ".google.com"
                    else -> return@forEach
                }
                header.split(';').forEach { token ->
                    val separator = token.indexOf('=')
                    if (separator <= 0) return@forEach
                    val name = token.substring(0, separator).trim()
                    val value = token.substring(separator + 1).trim()
                    if (name.isBlank() || value.isBlank() ||
                        name.any { it == '\t' || it == '\r' || it == '\n' } ||
                        value.any { it == '\t' || it == '\r' || it == '\n' }
                    ) return@forEach
                    append(domain).append("\tTRUE\t/\tTRUE\t0\t")
                        .append(name).append('\t').append(value).append('\n')
                }
            }
        }
        if (!containsAuthenticationCookie(rows)) return false
        return runCatching {
            encryptedFile(context).writeBytes(encrypt(rows.toByteArray(Charsets.UTF_8)))
            true
        }.getOrDefault(false)
    }

    fun hasCookies(context: Context): Boolean =
        readDecrypted(context)?.let(::containsAuthenticationCookie) == true

    fun clear(context: Context) {
        encryptedFile(context).delete()
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    fun <T> withCookieFile(context: Context, action: (String) -> T): T {
        val contents = readDecrypted(context)
        if (contents.isNullOrBlank() || !containsAuthenticationCookie(contents)) return action("")
        val file = File.createTempFile("robotpost-youtube-", ".cookies", context.cacheDir)
        return try {
            file.writeText(contents, Charsets.UTF_8)
            action(file.absolutePath)
        } finally {
            runCatching {
                val length = file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                file.outputStream().use { it.write(ByteArray(length)) }
                file.delete()
            }
        }
    }

    private fun containsAuthenticationCookie(contents: String): Boolean {
        return contents.lineSequence().any { line ->
            line.split('\t').getOrNull(5) in authCookieNames
        }
    }

    private fun encryptedFile(context: Context) =
        File(context.noBackupFilesDir, ENCRYPTED_FILE)

    private fun readDecrypted(context: Context): String? = runCatching {
        val file = encryptedFile(context)
        if (!file.isFile) return null
        decrypt(file.readBytes()).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText)
        return cipher.iv + encrypted
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > 12) { "Invalid YouTube session data" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, payload.copyOfRange(0, 12))
        )
        return cipher.doFinal(payload.copyOfRange(12, payload.size))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
