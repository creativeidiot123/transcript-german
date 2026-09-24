package com.creativeidiot.transcriptgerman.gemini

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class GeminiApiKeyStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _isConfigured = MutableStateFlow(hasPersistedCiphertext())
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    suspend fun saveApiKey(value: String) {
        val apiKey = value.trim()
        require(apiKey.isNotEmpty()) { "API key must not be blank" }

        withContext(ioDispatcher) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKeyOrCreate())
            val ciphertext = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))

            val committed = preferences.edit()
                .putString(KEY_CIPHERTEXT, Base64.getEncoder().encodeToString(ciphertext))
                .putString(KEY_IV, Base64.getEncoder().encodeToString(cipher.iv))
                .commit()
            if (!committed) {
                throw IOException("Could not persist Gemini API key")
            }
            _isConfigured.value = true
        }
    }

    suspend fun clearApiKey() {
        withContext(ioDispatcher) {
            val committed = preferences.edit()
                .remove(KEY_CIPHERTEXT)
                .remove(KEY_IV)
                .commit()
            if (!committed) {
                throw IOException("Could not clear Gemini API key")
            }
            _isConfigured.value = false
        }
    }

    suspend fun apiKeyOrNull(): String? =
        withContext(ioDispatcher) {
            val ciphertextText = preferences.getString(KEY_CIPHERTEXT, null)
            val ivText = preferences.getString(KEY_IV, null)
            if (ciphertextText == null || ivText == null) {
                _isConfigured.value = false
                return@withContext null
            }

            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKeyOrCreate(),
                    GCMParameterSpec(
                        GCM_TAG_LENGTH_BITS,
                        Base64.getDecoder().decode(ivText),
                    ),
                )
                val plaintext = cipher.doFinal(Base64.getDecoder().decode(ciphertextText))
                String(plaintext, StandardCharsets.UTF_8)
                    .trim()
                    .takeIf(String::isNotEmpty)
                    .also { _isConfigured.value = it != null }
            } catch (_: Exception) {
                preferences.edit()
                    .remove(KEY_CIPHERTEXT)
                    .remove(KEY_IV)
                    .commit()
                _isConfigured.value = false
                null
            }
        }

    private fun hasPersistedCiphertext(): Boolean =
        preferences.contains(KEY_CIPHERTEXT) &&
            preferences.contains(KEY_IV)

    private fun secretKeyOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "gemini_credentials"
        const val KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_IV = "api_key_iv"
        const val KEY_ALIAS = "transcript_german_gemini_api_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
