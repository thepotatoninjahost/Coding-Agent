package com.codingagent.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

internal class KeystoreSecretStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(STORE_FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun getString(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return decrypt(key, encoded)
    }

    @Synchronized
    fun putString(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, encrypt(key, value))
        check(editor.commit()) { "Unable to persist secure local state" }
    }

    private fun encrypt(key: String, value: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(key.toByteArray(UTF_8))
        return Base64.encodeToString(
            iv + cipher.doFinal(value.toByteArray(UTF_8)),
            Base64.NO_WRAP
        )
    }

    private fun decrypt(key: String, encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "Invalid secure local state" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(key.toByteArray(UTF_8))
        return cipher.doFinal(ciphertext).toString(UTF_8)
    }

    private fun secretKey(): java.security.Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        keyStore.getKey(KEY_ALIAS, null)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val STORE_FILE = "coding_agent_secure_v2"
        private const val KEY_ALIAS = "coding_agent_secure_v2"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        const val PROJECT_PATH = "project_path"
        const val LAST_RESEARCH = "last_research_query"
        const val MODEL_SETTINGS = "model_settings_v1"
    }
}

internal object LegacyEncryptedPreferencesMigration {
    private const val LEGACY_FILE = "coding_agent_session_encrypted_v1"
    private const val LEGACY_MASTER_KEY = "_androidx_security_master_key_"
    private const val KEY_KEYSET_ALIAS = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    private const val VALUE_KEYSET_ALIAS = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    private const val NULL_VALUE = "__NULL__"
    private const val MIGRATED_MARKER = "legacy_encrypted_preferences_migrated_v2"

    fun migrateIfNeeded(context: Context, target: KeystoreSecretStore) {
        val appContext = context.applicationContext
        if (target.getString(MIGRATED_MARKER) == "true") return

        val legacy = appContext.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        val entries = legacy.all.filterKeys { it != KEY_KEYSET_ALIAS && it != VALUE_KEYSET_ALIAS }
        if (entries.isEmpty()) {
            target.putString(MIGRATED_MARKER, "true")
            return
        }

        try {
            val values = decryptLegacyEntries(appContext, entries)
            values.forEach { (key, value) -> target.putString(key, value) }
            values.forEach { (key, value) ->
                check(target.getString(key) == value) {
                    "Secure settings migration verification failed for $key"
                }
            }
            target.putString(MIGRATED_MARKER, "true")
            check(appContext.deleteSharedPreferences(LEGACY_FILE)) {
                "Unable to remove legacy encrypted preferences after migration"
            }
            deleteLegacyMasterKey()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Unable to migrate existing encrypted settings without data loss", e
            )
        }
    }

    private fun decryptLegacyEntries(
        context: Context,
        entries: Map<String, *>
    ): Map<String, String?> {
        DeterministicAeadConfig.register()
        AeadConfig.register()
        val keyHandle = AndroidKeysetManager.Builder()
            .withKeyTemplate(KeyTemplates.get("AES256_SIV"))
            .withSharedPref(context, KEY_KEYSET_ALIAS, LEGACY_FILE)
            .withMasterKeyUri("android-keystore://$LEGACY_MASTER_KEY")
            .build().keysetHandle
        val valueHandle = AndroidKeysetManager.Builder()
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withSharedPref(context, VALUE_KEYSET_ALIAS, LEGACY_FILE)
            .withMasterKeyUri("android-keystore://$LEGACY_MASTER_KEY")
            .build().keysetHandle
        val keyAead = keyHandle.getPrimitive(DeterministicAead::class.java)
        val valueAead = valueHandle.getPrimitive(Aead::class.java)

        return entries.entries.associate { (encryptedKey, rawValue) ->
            val encryptedValue = rawValue as? String
                ?: error("Unexpected legacy preference value type")
            val clearKey = String(
                keyAead.decryptDeterministically(
                    Base64.decode(encryptedKey, Base64.DEFAULT),
                    LEGACY_FILE.toByteArray(UTF_8)
                ),
                UTF_8
            ).let { if (it == NULL_VALUE) null else it }
            requireNotNull(clearKey) { "Legacy null preference key is unsupported" }

            val plaintext = valueAead.decrypt(
                Base64.decode(encryptedValue, Base64.DEFAULT),
                encryptedKey.toByteArray(UTF_8)
            )
            clearKey to decodeLegacyString(plaintext)
        }
    }

    private fun decodeLegacyString(bytes: ByteArray): String? {
        val buffer = ByteBuffer.wrap(bytes)
        check(buffer.int == 0) { "Unexpected legacy preference type" }
        val length = buffer.int
        check(length >= 0 && length <= buffer.remaining()) { "Invalid legacy preference length" }
        val value = String(bytes, buffer.position(), length, UTF_8)
        return if (value == NULL_VALUE) null else value
    }

    private fun deleteLegacyMasterKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(LEGACY_MASTER_KEY)) keyStore.deleteEntry(LEGACY_MASTER_KEY)
    }
}