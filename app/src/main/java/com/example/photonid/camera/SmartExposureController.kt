package com.example.photonid.camera

/**
 * Simple face-brightness-based ISO adjustment.
 *
 * Tries to keep the captured face brightness within a target range
 * (100–200 in Y channel) so the corneal glint extraction gets a
 * clean, unsaturated signal.
 */
class SmartExposureController {

    companion object {
        private const val TARGET_BRIGHTNESS_LOW = 100
        private const val TARGET_BRIGHTNESS_HIGH = 200
        private const val ISO_MIN = 100
        private const val ISO_MAX = 3200
        private const val STEP = 50
    }

    /**
     * Given current face brightness (0–255) and current ISO value,
     * returns a new ISO if adjustment is needed, or null if within range.
     */
    fun calculateNewIso(faceBrightness: Int, currentIso: Int): Int? {
        if (faceBrightness < TARGET_BRIGHTNESS_LOW && currentIso < ISO_MAX) {
            return (currentIso + STEP).coerceAtMost(ISO_MAX)
        }
        if (faceBrightness > TARGET_BRIGHTNESS_HIGH && currentIso > ISO_MIN) {
            return (currentIso - STEP).coerceAtLeast(ISO_MIN)
        }
        return null // Already in range
    }
}
