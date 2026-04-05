package com.example.photonid.logic

import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Samsung Knox TrustZone simulation.
 *
 * In production this would call the Knox SDK hardware-backed keystore.
 * Here we use HMAC-SHA256 with a fixed test key.
 */
class MockKnoxVault {

    private val hardwareSecret = "SAMSUNG_HARDWARE_ROOT_KEY_XYZ".toByteArray(Charsets.UTF_8)

    val deviceId = "SM-S918B_GALAXY_S23_ULTRA"
    val knoxVersion = "3.9"
    val warrantyBit = "0x0"

    fun getIntegrityStatus(): Map<String, String> = mapOf(
        "device" to deviceId,
        "knox_version" to knoxVersion,
        "warranty_void" to warrantyBit,
        "bootloader" to "LOCKED"
    )

    fun signTelemetry(telemetry: Map<String, Any>): Map<String, Any> {
        val payloadStr = JSONObject(telemetry.toSortedMap()).toString()

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hardwareSecret, "HmacSHA256"))
        val signature = mac.doFinal(payloadStr.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return mapOf(
            "payload" to telemetry,
            "signature" to signature,
            "attestation" to getIntegrityStatus(),
            "timestamp_ms" to System.currentTimeMillis()
        )
    }
}
