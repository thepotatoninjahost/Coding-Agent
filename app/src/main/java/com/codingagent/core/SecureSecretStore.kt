package com.codingagent.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSecretStore(private val context: Context) {
    private val lock = Any()
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun put(name: String, value: String) {
        require(name.matches(SECRET_NAME_PATTERN)) { "Invalid secret name" }
        synchronized(lock) {
            if (value.isBlank()) {
                check(preferences.edit().remove(name).commit()) { "Could not remove stored secret" }
                return
            }
            val iv = ByteArray(IV_BYTES).also { java.security.SecureRandom().nextBytes(it) }
            val cipher = cipher(Cipher.ENCRYPT_MODE, key(), iv)
            val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            val encoded = android.util.Base64.encodeToString(iv + ciphertext, android.util.Base64.NO_WRAP)
            check(preferences.edit().putString(name, encoded).commit()) { "Could not persist encrypted secret" }
        }
    }

    fun get(name: String): String? {
        require(name.matches(SECRET_NAME_PATTERN)) { "Invalid secret name" }
        synchronized(lock) {
            val encoded = preferences.getString(name, null) ?: return null
            val payload = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            require(payload.size > IV_BYTES) { "Stored secret payload is invalid" }
            val iv = payload.copyOfRange(0, IV_BYTES)
            val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
            val plaintext = cipher(Cipher.DECRYPT_MODE, key(), iv).doFinal(ciphertext)
            return String(plaintext, StandardCharsets.UTF_8)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun cipher(mode: Int, key: SecretKey, iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(mode, key, GCMParameterSpec(TAG_BITS, iv)) }

    companion object {
        private val SECRET_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]{2,63}")
        private const val PREFERENCES = "coding_agent_secure_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "coding-agent-model-secrets-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
