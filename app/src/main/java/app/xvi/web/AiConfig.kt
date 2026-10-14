package app.xvi.web

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AI assistant settings, kept apart from the other preferences. The API key is encrypted with a
 * key that never leaves the Android Keystore, so the preferences file alone does not reveal it.
 */
internal class AiConfig(context: Context) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(ENABLED, true)
        set(value) = prefs.edit { putBoolean(ENABLED, value) }
    /** Any model name the service knows; on DeepSeek usually one of [DeepSeek.models]. */
    var model: String
        get() = prefs.getString(MODEL, null)?.trim()?.takeIf { it.isNotEmpty() } ?: DeepSeek.DEFAULT_MODEL
        set(value) = prefs.edit { putString(MODEL, value.trim()) }
    /** The API address, [DeepSeek.BASE] unless another OpenAI-compatible service was chosen. */
    var baseUrl: String
        get() = prefs.getString(BASE_URL, null)?.let(DeepSeek::normalizeBase) ?: DeepSeek.BASE
        set(value) = prefs.edit { if (DeepSeek.isDefault(value)) remove(BASE_URL) else putString(BASE_URL, value) }

    /** Null when unset, or when the Keystore lost its key (for example after a device restore). */
    var apiKey: String?
        get() = prefs.getString(API_KEY, null)?.let { runCatching { decrypt(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { if (value.isNullOrBlank()) remove(API_KEY) else putString(API_KEY, encrypt(value.trim())) }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val sealed = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, sealed) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ai-api-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENABLED = "enabled"
        const val MODEL = "model"
        const val BASE_URL = "baseUrl"
        const val API_KEY = "apiKey"
    }
}
