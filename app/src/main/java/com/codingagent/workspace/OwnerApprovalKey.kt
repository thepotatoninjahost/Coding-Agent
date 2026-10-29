package com.codingagent.workspace

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import com.codingagent.workspace.ChangeRecord

/**
 * Signs an approval challenge with an Android Keystore key that requires fresh
 * owner authentication for every signature operation. The signature binds the
 * proposal ID, approval ordinal, issue time, and random nonce.
 */
object OwnerApprovalKey {
    private const val KEY_ALIAS = "coding-agent-owner-approval-signing-v3"
    private const val LOCAL_ALIAS = "coding-agent-owner-approval-local-v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val DOMAIN = "coding-agent-owner-approval-v1"
    private val isJvmTestRuntime: Boolean
        get() = System.getProperty("java.runtime.name")
            ?.contains("Android", ignoreCase = true) == false

    private var testKeyPair: KeyPair? = null

    class Challenge internal constructor(
        val proposalId: String,
        val approvalNumber: Int,
        val issuedAt: Long,
        val nonce: String,
        val proposalFingerprint: String,
        val signature: Signature
    ) {
        internal fun payload(): ByteArray =
            canonical(proposalId, approvalNumber, issuedAt, nonce, proposalFingerprint)
    }

    fun fingerprint(proposalId: String, changes: List<ChangeRecord>): String {
        require(proposalId.isNotBlank() && changes.isNotEmpty())
        val canonicalChanges = changes.joinToString("\n") { change ->
            listOf(change.path, change.operation.name, change.beforeChecksum, change.afterChecksum)
                .joinToString("\u0000")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest("$DOMAIN\n$proposalId\n$canonicalChanges".toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun prepare(proposalId: String, approvalNumber: Int, now: Long, proposalFingerprint: String): Challenge {
        require(proposalId.isNotBlank() && approvalNumber in 1..2)
        require(proposalFingerprint.matches(Regex("[a-f0-9]{64}")))
        val nonce = UUID.randomUUID().toString()
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey())
        return Challenge(proposalId, approvalNumber, now, nonce, proposalFingerprint, signature)
    }

    fun complete(challenge: Challenge, authenticatedSignature: Signature): OwnerApprovalToken {
        authenticatedSignature.update(challenge.payload())
        val signature = authenticatedSignature.sign()
        return OwnerApprovalToken.fromSigned(
            challenge.proposalId,
            challenge.approvalNumber,
            challenge.issuedAt,
            challenge.nonce,
            challenge.proposalFingerprint,
            signature
        )
    }

    fun verify(
        proposalId: String,
        approvalNumber: Int,
        issuedAt: Long,
        nonce: String,
        proposalFingerprint: String,
        signatureBytes: ByteArray,
        testOnly: Boolean
    ): Boolean {
        val keys = if (testOnly) {
            if (!isJvmTestRuntime) return false
            listOf(testPair().public)
        } else {
            listOfNotNull(
                keyStore().getCertificate(KEY_ALIAS)?.publicKey,
                keyStore().getCertificate(LOCAL_ALIAS)?.publicKey
            )
        }
        if (keys.isEmpty()) return false
        val payload = canonical(proposalId, approvalNumber, issuedAt, nonce, proposalFingerprint)
        return keys.any { publicKey ->
            runCatching {
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publicKey)
                verifier.update(payload)
                verifier.verify(signatureBytes)
            }.getOrDefault(false)
        }
    }

    /** JVM-only test capability; production Android code cannot call this successfully. */
    fun testToken(proposalId: String, approvalNumber: Int, now: Long, proposalFingerprint: String = ""): OwnerApprovalToken {
        check(isJvmTestRuntime) { "Synthetic approval tokens are disabled on Android" }
        val nonce = UUID.randomUUID().toString()
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(testPair().private)
        signature.update(canonical(proposalId, approvalNumber, now, nonce, proposalFingerprint))
        return OwnerApprovalToken.fromTestSignature(
            proposalId, approvalNumber, now, nonce, proposalFingerprint, signature.sign()
        )
    }

    private fun privateKey(): java.security.PrivateKey {
        if (isJvmTestRuntime) return testPair().private
        val store = keyStore()
        val existing = store.getKey(KEY_ALIAS, null) as? java.security.PrivateKey
        if (existing != null) return existing
        check(store.getCertificate(KEY_ALIAS) == null) {
            "Owner approval signing key is incomplete; refusing to replace it automatically"
        }
        val authenticators = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, authenticators)
                .build()
        )
        return generator.generateKeyPair().private
    }

    /** A signature that does not need a screen lock. Used only when the phone has none. */
    fun prepareLocal(proposalId: String, approvalNumber: Int, now: Long, proposalFingerprint: String): Challenge {
        require(proposalId.isNotBlank() && approvalNumber in 1..2)
        require(proposalFingerprint.matches(Regex("[a-f0-9]{64}")))
        val nonce = UUID.randomUUID().toString()
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(localPrivateKey())
        return Challenge(proposalId, approvalNumber, now, nonce, proposalFingerprint, signature)
    }

    private fun localPrivateKey(): java.security.PrivateKey {
        if (isJvmTestRuntime) return testPair().private
        val store = keyStore()
        val existing = store.getKey(LOCAL_ALIAS, null) as? java.security.PrivateKey
        if (existing != null) return existing
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                LOCAL_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKeyPair().private
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun testPair(): KeyPair = synchronized(this) {
        testKeyPair ?: KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }.also { testKeyPair = it }
    }

    private fun canonical(proposalId: String, approvalNumber: Int, issuedAt: Long, nonce: String, proposalFingerprint: String): ByteArray =
        "$DOMAIN\n$proposalId\n$approvalNumber\n$issuedAt\n$nonce\n$proposalFingerprint".toByteArray(UTF_8)
}
