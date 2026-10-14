package com.codingagent.core

import java.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Owner-created typed and spoken approval phrases.
 * Only salted PBKDF2 verifiers are persisted; speech normalization is separate
 * so typed passphrases remain case-sensitive.
 */
internal class OwnerApprovalPhraseStore(
    private val readSecret: (String) -> String?,
    private val writeSecret: (String, String?) -> Unit
) {
    fun isConfigured(): Boolean =
        readSecret(TYPED_KEY) != null && readSecret(SPOKEN_KEY) != null

    @Synchronized
    fun enroll(typedPhrase: String, spokenPhrase: String) {
        val typed = typedPhrase.trim()
        val spoken = normalizeSpoken(spokenPhrase)
        require(typed.isNotEmpty()) { "Typed owner passphrase cannot be blank" }
        require(spoken.isNotEmpty()) { "Spoken owner passphrase cannot be blank" }
        require(normalizeSpoken(typed) != spoken) {
            "Create different typed and spoken passphrases so they are independent factors"
        }
        val typedVerifier = createVerifier(typed)
        val spokenVerifier = createVerifier(spoken)
        writeSecret(TYPED_KEY, typedVerifier)
        writeSecret(SPOKEN_KEY, spokenVerifier)
    }

    fun verifyTyped(input: String): Boolean = verify(TYPED_KEY, input.trim())

    fun verifySpoken(recognizedPhrase: String): Boolean =
        verify(SPOKEN_KEY, normalizeSpoken(recognizedPhrase))

    private fun verify(key: String, candidate: String): Boolean {
        if (candidate.isEmpty()) return false
        val stored = readSecret(key) ?: return false
        val parts = stored.split(':', limit = 2)
        if (parts.size != 2) return false
        return runCatching {
            val salt = Base64.getDecoder().decode(parts[0])
            val expected = Base64.getDecoder().decode(parts[1])
            MessageDigest.isEqual(expected, derive(candidate, salt))
        }.getOrDefault(false)
    }

    private fun createVerifier(phrase: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val digest = derive(phrase, salt)
        return Base64.getEncoder().withoutPadding().encodeToString(salt) + ":" +
            Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun derive(phrase: String, salt: ByteArray): ByteArray {
        val chars = phrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, DERIVED_KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun normalizeSpoken(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private companion object {
        const val TYPED_KEY = "owner_approval_typed_phrase_v1"
        const val SPOKEN_KEY = "owner_approval_spoken_phrase_v1"
        const val SALT_BYTES = 16
        const val DERIVED_KEY_BITS = 256
        const val PBKDF2_ITERATIONS = 210_000
    }
}
