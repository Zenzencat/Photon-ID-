package com.example.photonid.camera

import android.content.Context
import android.graphics.ImageFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerOptions
import kotlin.math.max
import kotlin.math.min

/**
 * Result data from a single frame analysis.
 *
 * @param glintRgb     [R, G, B] from the brightest corneal glint region
 * @param landmarkXs   Normalized X coordinates of all 478 landmarks
 * @param landmarkYs   Normalized Y coordinates of all 478 landmarks
 * @param faceBrightness Average Y-channel brightness in the face region (0–255)
 * @param faceDetected  Whether a face was found
 */
data class AnalysisResult(
    val glintRgb: FloatArray,
    val landmarkXs: List<Float>,
    val landmarkYs: List<Float>,
    val faceBrightness: Int,
    val faceDetected: Boolean,
    val faceCount: Int = 0,
    val multiFaceRejected: Boolean = false
)

/**
 * CameraX ImageAnalysis.Analyzer that uses MediaPipe Face Landmarker
 * with iris refinement to extract corneal glint colors.
 */
class CameraAnalyzer(
    context: Context,
    private val onResult: (AnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "CameraAnalyzer"
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val GLINT_RADIUS = 12
        // Iris landmark indices (MediaPipe with refine_landmarks=true)
        private const val LEFT_IRIS = 468
        private const val RIGHT_IRIS = 473
    }

    private var faceLandmarker: FaceLandmarker? = null
    private var lastProcessTime = 0L

    init {
        try {
            val options = FaceLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(3)  // detect up to 3 for multi-face rejection
                .setMinFaceDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.i(TAG, "FaceLandmarker initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init FaceLandmarker", e)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        // Throttle to ~20 FPS
        if (currentTime - lastProcessTime < 50) {
            imageProxy.close()
            return
        }
        lastProcessTime = currentTime

        val mediaImage = imageProxy.image
        if (mediaImage == null || mediaImage.format != ImageFormat.YUV_420_888) {
            imageProxy.close()
            return
        }

        val landmarker = faceLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }

        try {
            // Convert YUV to Bitmap for MediaPipe
            val bitmap = imageProxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()

            val result = landmarker.detect(mpImage)
            val faceCount = result.faceLandmarks().size

            if (faceCount > 0) {
                // ── MULTI-FACE REJECTION ────────────────────────────
                if (faceCount > 1) {
                    onResult(
                        AnalysisResult(
                            glintRgb = floatArrayOf(0f, 0f, 0f),
                            landmarkXs = emptyList(),
                            landmarkYs = emptyList(),
                            faceBrightness = 0,
                            faceDetected = true,
                            faceCount = faceCount,
                            multiFaceRejected = true
                        )
                    )
                } else {

                val landmarks = result.faceLandmarks()[0]

                val xs = landmarks.map { it.x() }
                val ys = landmarks.map { it.y() }

                val width = bitmap.width
                val height = bitmap.height

                // Extract corneal glint RGB from iris regions
                val glintRgb = extractGlintRgb(bitmap, landmarks, width, height)

                // Face brightness from nose region
                val noseX = (landmarks[1].x() * width).toInt().coerceIn(0, width - 1)
                val noseY = (landmarks[1].y() * height).toInt().coerceIn(0, height - 1)
                val faceBrightness = getRegionBrightness(bitmap, noseX, noseY, 50)

                    onResult(
                        AnalysisResult(
                            glintRgb = glintRgb,
                            landmarkXs = xs,
                            landmarkYs = ys,
                            faceBrightness = faceBrightness,
                            faceDetected = true,
                            faceCount = 1,
                            multiFaceRejected = false
                        )
                    )
                }
            } else {
                onResult(
                    AnalysisResult(
                        glintRgb = floatArrayOf(0f, 0f, 0f),
                        landmarkXs = emptyList(),
                        landmarkYs = emptyList(),
                        faceBrightness = 0,
                        faceDetected = false,
                        faceCount = 0,
                        multiFaceRejected = false
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Analysis error", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Extract brightest corneal glint colour from both eyes.
     * Uses MediaPipe iris landmarks (468 = left iris, 473 = right iris).
     * Returns [R, G, B] for the eye with stronger signal.
     */
    private fun extractGlintRgb(
        bitmap: android.graphics.Bitmap,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        w: Int,
        h: Int
    ): FloatArray {
        var bestRgb = floatArrayOf(0f, 0f, 0f)
        var bestSum = 0f

        for (idx in listOf(LEFT_IRIS, RIGHT_IRIS)) {
            if (idx >= landmarks.size) continue

            val lm = landmarks[idx]
            val cx = (lm.x() * w).toInt()
            val cy = (lm.y() * h).toInt()
            val x1 = max(0, cx - GLINT_RADIUS)
            val x2 = min(w - 1, cx + GLINT_RADIUS)
            val y1 = max(0, cy - GLINT_RADIUS)
            val y2 = min(h - 1, cy + GLINT_RADIUS)

            if (x2 <= x1 || y2 <= y1) continue

            // Extract pixel data from ROI
            val roiW = x2 - x1 + 1
            val roiH = y2 - y1 + 1
            val pixels = IntArray(roiW * roiH)
            bitmap.getPixels(pixels, 0, roiW, x1, y1, roiW, roiH)

            // Calculate brightness (grayscale) for each pixel
            val brightness = pixels.map { pixel ->
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                (r + g + b) / 3
            }

            // Top 10% brightness mask (90th percentile)
            val sorted = brightness.sorted()
            val threshold = sorted[(sorted.size * 0.9).toInt().coerceIn(0, sorted.size - 1)]

            var sumR = 0f; var sumG = 0f; var sumB = 0f; var count = 0
            for (i in pixels.indices) {
                if (brightness[i] >= threshold) {
                    sumR += (pixels[i] shr 16) and 0xFF
                    sumG += (pixels[i] shr 8) and 0xFF
                    sumB += pixels[i] and 0xFF
                    count++
                }
            }

            if (count == 0) continue

            val rgb = floatArrayOf(sumR / count, sumG / count, sumB / count)
            val s = rgb.sum()
            if (s > bestSum) {
                bestSum = s
                bestRgb = rgb
            }
        }

        return bestRgb
    }

    /**
     * Get average brightness (0–255) in a square region around (cx, cy).
     */
    private fun getRegionBrightness(
        bitmap: android.graphics.Bitmap,
        cx: Int,
        cy: Int,
        radius: Int
    ): Int {
        val x1 = max(0, cx - radius)
        val x2 = min(bitmap.width - 1, cx + radius)
        val y1 = max(0, cy - radius)
        val y2 = min(bitmap.height - 1, cy + radius)

        if (x2 <= x1 || y2 <= y1) return 0

        val w = x2 - x1 + 1
        val h = y2 - y1 + 1
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x1, y1, w, h)

        // Sample every 4th pixel for speed
        var sum = 0L; var count = 0
        for (i in pixels.indices step 4) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            sum += (r + g + b) / 3
            count++
        }

        return if (count > 0) (sum / count).toInt() else 0
    }

    fun close() {
        faceLandmarker?.close()
    }
}
