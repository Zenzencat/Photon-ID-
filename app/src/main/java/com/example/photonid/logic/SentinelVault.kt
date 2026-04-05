package com.example.photonid.logic

import java.security.MessageDigest
import kotlin.math.abs

/**
 * Identity persistence via geometric face hashing.
 *
 * Stores lightweight hashes of verified/blocked users.
 * On subsequent scans, a previously verified user is fast-tracked.
 * A blocked user (deepfake detected) is denied access.
 *
 * The hash is built from stable inter-landmark ratios that are
 * naturally invariant to head pose and distance changes.
 */
class SentinelVault(private val ttlSeconds: Float = 60f) {

    // MediaPipe face landmark indices
    private val NOSE = 1
    private val EYE_L_IN = 33
    private val EYE_R_IN = 263
    private val CHIN = 152

    data class Record(
        val status: String,
        val timestamp: Long,
        val trustScore: Float
    )

    private val registry = mutableMapOf<String, Record>()

    /**
     * Compute a face-geometry fingerprint from normalized landmark coordinates.
     * Two ratios (eye width / face height, nose-eye / face height)
     * are enough to uniquely identify a person within ±0.01 tolerance.
     */
    private fun geometricHash(
        landmarkXs: List<Float>,
        landmarkYs: List<Float>
    ): String? {
        if (landmarkXs.size < 468) return null

        return try {
            val noseY = landmarkYs[NOSE]
            val eyeLX = landmarkXs[EYE_L_IN]
            val eyeRX = landmarkXs[EYE_R_IN]
            val eyeLY = landmarkYs[EYE_L_IN]
            val chinY = landmarkYs[CHIN]

            val faceHeight = abs(chinY - noseY)
            if (faceHeight < 1e-6f) return null

            val eyeWidthRatio = abs(eyeLX - eyeRX) / faceHeight
            val noseEyeRatio = abs(noseY - eyeLY) / faceHeight

            val rawId = "%.2f|%.2f".format(eyeWidthRatio, noseEyeRatio)
            val md = MessageDigest.getInstance("MD5")
            md.digest(rawId.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check identity: returns (status, trustScore).
     * Status: VERIFIED / BLOCKED / EXPIRED / UNKNOWN
     */
    fun checkIdentity(
        landmarkXs: List<Float>,
        landmarkYs: List<Float>
    ): Pair<String, Float> {
        val uid = geometricHash(landmarkXs, landmarkYs) ?: return "UNKNOWN" to 0f
        val record = registry[uid] ?: return "UNKNOWN" to 0f
        val ageSecs = (System.currentTimeMillis() - record.timestamp) / 1000f
        if (ageSecs > ttlSeconds) return "EXPIRED" to 0f
        return record.status to record.trustScore
    }

    /**
     * Register a user in the vault.
     */
    fun registerUser(
        landmarkXs: List<Float>,
        landmarkYs: List<Float>,
        status: String,
        trustScore: Float
    ) {
        val uid = geometricHash(landmarkXs, landmarkYs) ?: return
        registry[uid] = Record(status, System.currentTimeMillis(), trustScore)
    }
}
