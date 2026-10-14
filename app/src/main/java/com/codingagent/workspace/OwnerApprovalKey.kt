package com.codingagent.workspace

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Signs an approval challenge with an Android Keystore key that requires fresh
 * owner authentication for every signature operation. The signature binds the
 * proposal ID, approval ordinal, issue time, and random nonce.
 */
internal object OwnerApprovalKey {
    private const val KEY_ALIAS = "coding-agent-owner-approval-signing-v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val DOMAIN = "coding-agent-owner-approval-v1"
    private val isJvmTestRuntime: Boolean
        get() = System.getProperty("java.runtime.name")
            ?.contains("Android", ignoreCase = true) == false

    private var testKeyPair: KeyPair? = null

    internal class Challenge internal constructor(
        val proposalId: String,
        val approvalNumber: Int,
        val issuedAt: Long,
        val nonce: String,
        val signature: Signature
    ) {
        internal fun payload(): ByteArray = canonical(proposalId, approvalNumber, issuedAt, nonce)
    }

    fun prepare(proposalId: String, approvalNumber: Int, now: Long): Challenge {
        require(proposalId.isNotBlank())
        require(approvalNumber in 1..2)
        val nonce = UUID.randomUUID().toString()
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey())
        return Challenge(proposalId, approvalNumber, now, nonce, signature)
    }

    fun complete(challenge: Challenge, authenticatedSignature: Signature): OwnerApprovalToken {
        authenticatedSignature.update(challenge.payload())
        val signature = authenticatedSignature.sign()
        return OwnerApprovalToken.fromSigned(
            challenge.proposalId,
            challenge.approvalNumber,
            challenge.issuedAt,
            challenge.nonce,
            signature
        )
    }

    fun verify(
        proposalId: String,
        approvalNumber: Int,
        issuedAt: Long,
        nonce: String,
        signatureBytes: ByteArray,
        testOnly: Boolean
    ): Boolean = runCatching {
        val publicKey = if (testOnly) {
            if (!isJvmTestRuntime) return false
            testPair().public
        } else {
            keyStore().getCertificate(KEY_ALIAS)?.publicKey ?: return false
        }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(canonical(proposalId, approvalNumber, issuedAt, nonce))
        verifier.verify(signatureBytes)
    }.getOrDefault(false)

    /** JVM-only test capability; production Android code cannot call this successfully. */
    fun testToken(proposalId: String, approvalNumber: Int, now: Long): OwnerApprovalToken {
        check(isJvmTestRuntime) { "Synthetic approval tokens are disabled on Android" }
        val nonce = UUID.randomUUID().toString()
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(testPair().private)
        signature.update(canonical(proposalId, approvalNumber, now, nonce))
        return OwnerApprovalToken.fromTestSignature(
            proposalId, approvalNumber, now, nonce, signature.sign()
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

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun testPair(): KeyPair = synchronized(this) {
        testKeyPair ?: KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }.also { testKeyPair = it }
    }

    private fun canonical(proposalId: String, approvalNumber: Int, issuedAt: Long, nonce: String): ByteArray =
        "$DOMAIN\n$proposalId\n$approvalNumber\n$issuedAt\n$nonce".toByteArray(UTF_8)
}
