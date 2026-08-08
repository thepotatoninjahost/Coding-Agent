package com.codingagent.ui.session

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

class SecretStore(private val context: Context) {
    fun put(name: String, value: String) {
        require(NAME_PATTERN.matches(name)) { "Invalid secret name" }
        require(value.length <= MAX_VALUE_LENGTH) { "Secret is too long" }
        val encrypted = encrypt(value)
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(name, encrypted)
            .commit()
            .also { check(it) { "Could not persist encrypted secret" } }
    }

    fun get(name: String): String? {
        require(NAME_PATTERN.matches(name)) { "Invalid secret name" }
        val encoded = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(name, null) ?: return null
        return decrypt(encoded)
    }

    fun remove(name: String) {
        require(NAME_PATTERN.matches(name)) { "Invalid secret name" }
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().remove(name).commit()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH) { "Encrypted secret payload is invalid" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH_BITS, payload.copyOfRange(0, IV_LENGTH)))
        return cipher.doFinal(payload.copyOfRange(IV_LENGTH, payload.size)).toString(StandardCharsets.UTF_8)
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
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val FILE_NAME = "coding_agent_secrets"
        private const val KEY_ALIAS = "coding_agent_model_secrets_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val MAX_VALUE_LENGTH = 4096
        private val NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    }
}
