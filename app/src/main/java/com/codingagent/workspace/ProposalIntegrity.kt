package com.codingagent.workspace

import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

internal object ProposalIntegrity {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "coding-agent-pending-proposals-v1"
    private const val ALGORITHM = "HmacSHA256"

    @Volatile private var testKey: SecretKey? = null

    fun mac(payload: String): String =
        Base64.getEncoder().encodeToString(hmac(payload.toByteArray(StandardCharsets.UTF_8)))

    fun verify(payload: String, encodedMac: String): Boolean {
        if (encodedMac.isBlank()) return false
        return constantTimeEquals(mac(payload), encodedMac)
    }

    private fun hmac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(key())
        return mac.doFinal(data)
    }

    private fun key(): SecretKey {
        if (System.getProperty("java.runtime.name")?.contains("Android", ignoreCase = true) == false) {
            return testKey ?: synchronized(this) {
                testKey ?: SecretKeySpec(
                    ByteArray(32) { index -> (index * 31 + 17).toByte() },
                    ALGORITHM
                ).also { testKey = it }
            }
        }
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(ALGORITHM, KEYSTORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_SIGN or
                    android.security.keystore.KeyProperties.PURPOSE_VERIFY
            ).setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256).build()
        )
        return generator.generateKey()
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val a = left.toByteArray(StandardCharsets.UTF_8)
        val b = right.toByteArray(StandardCharsets.UTF_8)
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }
}
