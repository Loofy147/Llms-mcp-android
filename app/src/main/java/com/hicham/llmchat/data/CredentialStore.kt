package com.hicham.llmchat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small protected credential boundary for API/MCP authorization material.
 *
 * The ciphertext may live in ordinary app preferences, but the encryption key
 * is non-exportable Android Keystore material. Callers should still avoid
 * putting returned plaintext credentials into logs, evidence, or model input.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readApiKey(): String? = decrypt(prefs.getString(KEY_API, null))

    fun writeApiKey(value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(KEY_API).apply()
        } else {
            prefs.edit().putString(KEY_API, encrypt(value)).apply()
        }
    }

    fun readMcpToken(serverName: String): String? = decrypt(prefs.getString(mcpKey(serverName), null))

    fun writeMcpToken(serverName: String, value: String?) {
        val key = mcpKey(serverName)
        val editor = prefs.edit()
        if (value.isNullOrBlank()) {
            editor.remove(key)
        } else {
            editor.putString(key, encrypt(value))
        }
        editor.apply()
    }

    fun deleteMcpToken(serverName: String) {
        prefs.edit().remove(mcpKey(serverName)).apply()
    }

    /** One-time migration helper for the legacy plaintext API key. */
    fun migrateLegacyApiKey(legacyValue: String?): Boolean {
        if (readApiKey() != null || legacyValue.isNullOrBlank()) return false
        writeApiKey(legacyValue)
        return true
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        return try {
            val parts = encoded.split(":", limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun mcpKey(serverName: String): String = "mcp:${serverName.trim()}"

    companion object {
        private const val PREFS_NAME = "llm_chat_credentials"
        private const val KEY_API = "anthropic_api_key"
        private const val KEY_ALIAS = "llm_chat_credentials_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
