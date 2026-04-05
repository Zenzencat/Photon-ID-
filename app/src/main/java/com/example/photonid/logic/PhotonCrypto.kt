package com.example.photonid.logic

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption backed by Android Keystore.
 *
 * All sensitive eKYC data (telemetry packets, face hashes, attestation
 * results) are encrypted before storage or transmission.
 *
 * The key lives in the hardware-backed Android Keystore and never
 * leaves the secure element — even root cannot extract it.
 */
object PhotonCrypto {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "PhotonID_MasterKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128 // bits

    init {
        ensureKeyExists()
    }

    // ── Key Management ─────────────────────────────────────────
    private fun ensureKeyExists() {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val keyGen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER
            )
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            keyGen.generateKey()
        }
    }

    private fun getKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretEntry).secretKey
    }

    // ── Encrypt ────────────────────────────────────────────────
    /**
     * Encrypt plaintext string → Base64-encoded "iv:ciphertext" string.
     */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$ivB64:$ctB64"
    }

    /**
     * Encrypt a Map to an encrypted JSON string.
     */
    fun encryptMap(data: Map<String, Any>): String {
        val json = JSONObject(data).toString()
        return encrypt(json)
    }

    // ── Decrypt ────────────────────────────────────────────────
    /**
     * Decrypt "iv:ciphertext" → plaintext string.
     */
    fun decrypt(encrypted: String): String {
        val parts = encrypted.split(":", limit = 2)
        require(parts.size == 2) { "Invalid encrypted format" }

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getKey(),
            GCMParameterSpec(GCM_TAG_LENGTH, iv)
        )

        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * Decrypt to a JSONObject.
     */
    fun decryptToJson(encrypted: String): JSONObject {
        return JSONObject(decrypt(encrypted))
    }
}
