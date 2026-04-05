package com.example.photonid.logic

import java.util.LinkedList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║                    PHOTON ID ENGINE v2.0                        ║
 * ║       Corneal-Glint Liveness Detection — Kotlin Port            ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * Core liveness detection engine.
 * Pipeline: recordChallenge() → recordFrame() → analyze() → generateSecurePacket()
 */

// ── Constants ──────────────────────────────────────────────────────
const val CHALLENGE_FREQ_MS = 600L         // ms between color flashes
const val BASELINE_EVERY = 3               // black calibration frame every N colored flashes
const val HISTORY_LEN = 200
const val HUMAN_CONFIDENCE_THRESHOLD = 80f
const val DEEPFAKE_CONFIDENCE_THRESHOLD = 10f
const val MIN_SAMPLES_FOR_DECISION = 20

// Anti-stagnation
const val STAGNATION_WINDOW = 80
const val STAGNATION_STD_DEV_LIMIT = 0.5f

// Light adaptation
const val EWMA_ALPHA = 0.15f
const val MIN_SIGNAL_THRESHOLD = 10f
const val SATURATION_THRESHOLD = 250f

// Latency model (ms)
const val BASE_LATENCY_MS = 180f
const val MAX_JITTER_MS = 400f
const val DEEPFAKE_LATENCY_THRESHOLD = 600f

/**
 * Color palette in RGB order (not BGR like Python/OpenCV).
 * Index 0 = black baseline; 1-6 = challenge colors.
 */
val COLOR_PALETTE_RGB: List<Triple<Int, Int, Int>> = listOf(
    Triple(0, 0, 0),         // 0 Black (baseline)
    Triple(255, 0, 0),       // 1 Red
    Triple(0, 255, 0),       // 2 Green
    Triple(0, 0, 255),       // 3 Blue
    Triple(0, 255, 255),     // 4 Cyan
    Triple(255, 0, 255),     // 5 Magenta
    Triple(255, 255, 0),     // 6 Yellow
)

// ── Data Structures ────────────────────────────────────────────────

data class FrameData(
    val timestampMs: Long,       // System.nanoTime() / 1_000_000
    val rgb: FloatArray,         // [R, G, B]
    val glintIntensity: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameData) return false
        return timestampMs == other.timestampMs && rgb.contentEquals(other.rgb)
    }
    override fun hashCode() = timestampMs.hashCode()
}

data class ChallengeData(
    val timestampMs: Long,
    val label: Int               // index into COLOR_PALETTE_RGB
)

class LatencyStats {
    val measurements = LinkedList<Float>()
    var mean = 150f
    var stdDev = 50f
    var adaptiveThreshold = 450f

    private val maxSize = 30

    fun addMeasurement(latencyMs: Float) {
        measurements.addLast(latencyMs)
        if (measurements.size > maxSize) measurements.removeFirst()
        if (measurements.size > 5) {
            mean = measurements.average().toFloat()
            val variance = measurements.map { (it - mean) * (it - mean) }.average().toFloat()
            stdDev = sqrt(variance)
            adaptiveThreshold = mean + 3f * stdDev
        }
    }
}

// ── Engine ─────────────────────────────────────────────────────────

class PhotonIDEngine {

    val knox = MockKnoxVault()

    // History buffers
    private val challengeHistory = LinkedList<ChallengeData>()
    private val frameHistory = LinkedList<FrameData>()

    // Baseline
    private var baselineRgb = floatArrayOf(0f, 0f, 0f)
    private var baselineLocked = false
    private var baselineSamples = 0

    // Latency
    val latencyStats = LatencyStats()

    // Confidence samples
    private val samples = LinkedList<Float>()
    private val maxSamples = 100
    private val confidenceTrace = LinkedList<Float>()

    // ── Public state (read by UI) ──────────────────────────────────
    var confidence: Float = 0f; private set
    var status: String = "INITIALIZING"; private set
    var debugMsg: String = ""; private set
    var deepfakeRisk: Float = 0f; private set
    var isCertain: Boolean = false; private set
    var isHuman: Boolean = false; private set
    var securePacket: Map<String, Any>? = null; private set

    // ── Reset ──────────────────────────────────────────────────────
    fun reset() {
        challengeHistory.clear()
        frameHistory.clear()
        baselineRgb = floatArrayOf(0f, 0f, 0f)
        baselineLocked = false
        baselineSamples = 0
        latencyStats.measurements.clear()
        latencyStats.mean = 150f
        latencyStats.stdDev = 50f
        latencyStats.adaptiveThreshold = 450f
        samples.clear()
        confidenceTrace.clear()
        confidence = 0f
        status = "INITIALIZING"
        debugMsg = ""
        deepfakeRisk = 0f
        isCertain = false
        isHuman = false
        securePacket = null
        encryptedPacket = null
    }

    // ── Record methods ─────────────────────────────────────────────
    fun recordChallenge(colorIdx: Int) {
        challengeHistory.addLast(ChallengeData(nowMs(), colorIdx))
        if (challengeHistory.size > HISTORY_LEN) challengeHistory.removeFirst()
    }

    fun recordFrame(rgb: FloatArray) {
        frameHistory.addLast(FrameData(nowMs(), rgb.copyOf(), rgb.max()))
        if (frameHistory.size > HISTORY_LEN) frameHistory.removeFirst()
    }

    // ── Knox signing + encryption ─────────────────────────────────
    var encryptedPacket: String? = null; private set

    fun generateSecurePacket(): Map<String, Any> {
        val telemetry = mapOf(
            "is_human" to isHuman,
            "confidence_pct" to (Math.round(confidence * 100f) / 100f),
            "avg_latency_ms" to (Math.round(latencyStats.mean * 10f) / 10f),
            "deepfake_risk" to (Math.round(deepfakeRisk * 1000f) / 1000f),
            "sample_count" to samples.size,
            "engine_version" to "2.0.0"
        )
        val packet = knox.signTelemetry(telemetry)
        securePacket = packet

        // Encrypt the entire signed packet with AES-256-GCM (Android Keystore)
        try {
            encryptedPacket = PhotonCrypto.encryptMap(packet)
        } catch (e: Exception) {
            // Encryption may fail on first boot; packet is still signed
            encryptedPacket = null
        }

        return packet
    }

    // ── Baseline update ────────────────────────────────────────────
    private fun updateBaseline(rgb: FloatArray) {
        if (baselineLocked) return
        if (baselineSamples == 0) {
            baselineRgb = rgb.copyOf()
        } else {
            for (i in 0..2) {
                baselineRgb[i] = (1f - EWMA_ALPHA) * baselineRgb[i] + EWMA_ALPHA * rgb[i]
            }
        }
        baselineSamples++
        if (baselineSamples >= 10) {
            baselineLocked = true
        }
    }

    // ── Main analysis ──────────────────────────────────────────────
    fun analyze() {
        if (challengeHistory.isEmpty() || frameHistory.size < 3) return

        val challenge = challengeHistory.last

        // Black frame → update baseline
        if (challenge.label == 0) {
            if (frameHistory.isNotEmpty()) {
                updateBaseline(frameHistory.last.rgb)
            }
            return
        }

        // Search for best matching glint frame in latency window
        val searchMinMs = 20
        val searchMaxMs = 600

        var bestMatch: FrameData? = null
        var bestScore = -1f
        var bestLatency = 0f

        for (frame in frameHistory.reversed()) {
            val dtMs = frame.timestampMs - challenge.timestampMs
            if (dtMs < searchMinMs) continue
            if (dtMs > searchMaxMs) break

            val delta = FloatArray(3) { max(0f, frame.rgb[it] - baselineRgb[it]) }

            // Reject saturated
            if (frame.rgb.max() > SATURATION_THRESHOLD) {
                status = "ADAPTING LIGHT"
                debugMsg = "Sensor saturated"
                return
            }

            if (delta.max() < MIN_SIGNAL_THRESHOLD) continue

            val expected = COLOR_PALETTE_RGB[challenge.label]
            val score = colorScore(delta, expected)

            val latencyPenalty = abs(dtMs - latencyStats.mean) / 100f
            val adjustedScore = score - latencyPenalty * 0.3f

            if (adjustedScore > bestScore) {
                bestScore = adjustedScore
                bestMatch = frame
                bestLatency = dtMs.toFloat()
            }
        }

        // No match
        if (bestMatch == null) {
            debugMsg = if (baselineRgb.max() < 80f) "Signal weak" else "No match"
            addSample(0f)
            return
        }

        val delta = FloatArray(3) { max(0f, bestMatch.rgb[it] - baselineRgb[it]) }
        val totalDelta = delta.sum()

        if (totalDelta < MIN_SIGNAL_THRESHOLD) {
            debugMsg = "Weak signal"
            addSample(0f)
            return
        }

        val expected = COLOR_PALETTE_RGB[challenge.label]
        if (!isColorDominant(delta, expected, totalDelta)) {
            debugMsg = "Color not dominant"
            addSample(0.2f)
            return
        }

        // Update adaptive latency model
        latencyStats.addMeasurement(bestLatency)

        // Graduated latency risk scoring
        if (bestLatency > DEEPFAKE_LATENCY_THRESHOLD) {
            val risk = min(1f, (bestLatency - 500f) / 200f)
            deepfakeRisk = risk
            debugMsg = "HIGH LATENCY: ${bestLatency.toInt()} ms"
            addSample(0.5f)
        } else if (bestLatency > latencyStats.adaptiveThreshold + 100f) {
            deepfakeRisk = 0.2f
            debugMsg = "Latency spike: ${bestLatency.toInt()} ms"
            addSample(0.7f)
        } else {
            deepfakeRisk = 0f
            debugMsg = "OK: ${bestLatency.toInt()} ms"
            addSample(1f)
        }
    }

    // ── Color scoring ──────────────────────────────────────────────
    private fun colorScore(delta: FloatArray, expectedRgb: Triple<Int, Int, Int>): Float {
        val r = delta[0]; val g = delta[1]; val b = delta[2]
        val eR = expectedRgb.first; val eG = expectedRgb.second; val eB = expectedRgb.third

        return when {
            eR > eG && eR > eB -> r - (g + b) / 2f   // Red dominant
            eG > eB && eG > eR -> g - (r + b) / 2f   // Green dominant
            eB > eG && eB > eR -> b - (r + g) / 2f   // Blue dominant
            else -> (r + g + b) / 3f                   // Mixed
        }
    }

    private fun isColorDominant(delta: FloatArray, expectedRgb: Triple<Int, Int, Int>, total: Float): Boolean {
        if (total == 0f) return false
        val r = delta[0]; val g = delta[1]; val b = delta[2]
        val eR = expectedRgb.first; val eG = expectedRgb.second; val eB = expectedRgb.third

        return when {
            eR > eG && eR > eB -> (r / total) > 0.5f
            eG > eB && eG > eR -> (g / total) > 0.5f
            eB > eG && eB > eR -> (b / total) > 0.5f
            else -> {
                // Mixed: check expected channels together dominate
                var expectedSum = 0f
                if (eR > 100) expectedSum += r
                if (eG > 100) expectedSum += g
                if (eB > 100) expectedSum += b
                (expectedSum / total) > 0.4f
            }
        }
    }

    // ── Metrics ────────────────────────────────────────────────────
    private fun addSample(value: Float) {
        samples.addLast(value)
        if (samples.size > maxSamples) samples.removeFirst()
        updateMetrics()
    }

    private fun updateMetrics() {
        val n = samples.size
        if (n < MIN_SAMPLES_FOR_DECISION) {
            confidence = 0f
            status = "COLLECTING DATA..."
            isCertain = false
            return
        }

        // Recency-weighted average
        var weightedSum = 0f
        var weightTotal = 0f
        samples.forEachIndexed { i, s ->
            val w = 1f + i.toFloat() / n
            weightedSum += s * w
            weightTotal += w
        }
        confidence = (weightedSum / weightTotal) * 100f

        // Track for stagnation
        confidenceTrace.addLast(confidence)
        if (confidenceTrace.size > STAGNATION_WINDOW) confidenceTrace.removeFirst()

        // Anti-stagnation
        if (confidenceTrace.size >= STAGNATION_WINDOW) {
            val mean = confidenceTrace.average().toFloat()
            val variance = confidenceTrace.map { (it - mean) * (it - mean) }.average().toFloat()
            val stdDev = sqrt(variance)
            if (stdDev < STAGNATION_STD_DEV_LIMIT) {
                status = "🚨 DEEPFAKE (STATIC)"
                isCertain = true
                isHuman = false
                deepfakeRisk = 1f
                return
            }
        }

        // Threshold decisions
        when {
            confidence > HUMAN_CONFIDENCE_THRESHOLD -> {
                status = "✓ HUMAN DETECTED"
                isCertain = true
                isHuman = true
                deepfakeRisk = 0f
            }
            confidence < DEEPFAKE_CONFIDENCE_THRESHOLD && n > 25 -> {
                status = "🚨 DEEPFAKE DETECTED"
                isCertain = true
                isHuman = false
                deepfakeRisk = 1f
            }
            deepfakeRisk > 0.95f -> {
                status = "🚨 DEEPFAKE DETECTED"
                isCertain = true
                isHuman = false
            }
            else -> {
                status = "ANALYZING..."
                isCertain = false
            }
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
