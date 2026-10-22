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
internal fun normalizeOwnerSpokenPhrase(value: String): String {
    val cleaned = buildString {
        value.trim().lowercase().forEach { character ->
            append(if (character.isLetterOrDigit() || character.isWhitespace()) character else ' ')
        }
    }
    return cleaned.trim().replace(Regex("\\s+"), " ")
}

internal class OwnerApprovalPhraseStore(
    private val readSecret: (String) -> String?,
    private val writeSecret: (String, String?) -> Unit
) {
    fun isConfigured(): Boolean = runCatching {
        readSecret(TYPED_KEY) != null && readSecret(SPOKEN_KEY) != null
    }.getOrDefault(false)

    @Synchronized
    fun enroll(typedPhrase: String, spokenPhrase: String) {
        val typed = typedPhrase.trim()
        val spoken = normalizeSpoken(spokenPhrase)
        require(typed.isNotEmpty()) { "Type some words. That line cannot be empty." }
        require(spoken.isNotEmpty()) { "Type the words you will say. That line cannot be empty." }
        require(normalizeSpoken(typed) != spoken) {
            "Use different words for typing and for speaking. They should not be the same."
        }
        val typedVerifier = createVerifier(typed)
        val spokenVerifier = createVerifier(spoken)
        writeSecret(TYPED_KEY, typedVerifier)
        writeSecret(SPOKEN_KEY, spokenVerifier)
    }

    fun verifyTyped(input: String): Boolean = verify(TYPED_KEY, input.trim())

    fun verifySpoken(recognizedPhrase: String, challengeWord: String): Boolean {
        val transcript = normalizeOwnerSpokenPhrase(recognizedPhrase)
        val suffix = normalizeOwnerSpokenPhrase(challengeWord)
        if (transcript.isBlank() || suffix.isBlank()) return false
        val challengeSuffix = " " + suffix
        if (!transcript.endsWith(challengeSuffix)) return false
        val phraseOnly = transcript.removeSuffix(challengeSuffix).trim()
        return verify(SPOKEN_KEY, phraseOnly)
    }

    private fun verify(key: String, candidate: String): Boolean {
        if (candidate.isEmpty()) return false
        return runCatching {
            val stored = readSecret(key) ?: return false
            val parts = stored.split(':', limit = 2)
            if (parts.size != 2) return false
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

    private fun normalizeSpoken(value: String): String = normalizeOwnerSpokenPhrase(value)

    private companion object {
        const val TYPED_KEY = "owner_approval_typed_phrase_v1"
        const val SPOKEN_KEY = "owner_approval_spoken_phrase_v1"
        const val SALT_BYTES = 16
        const val DERIVED_KEY_BITS = 256
        const val PBKDF2_ITERATIONS = 210_000
    }
}
