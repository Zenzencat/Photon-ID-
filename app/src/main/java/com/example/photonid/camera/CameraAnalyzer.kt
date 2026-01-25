package com.example.photonid.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mlkit.vision.facemesh.FaceMeshPoint
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class CameraAnalyzer(
    private val onIrisDetected: (irisColor: Triple<Int, Int, Int>, faceBrightness: Int) -> Unit
) : ImageAnalysis.Analyzer {

    // ... (rest of class) ...

    // Helper to get simple Y channel brightness
    private fun getAverageBrightness(
        image: ImageProxy,
        centerX: Float,
        centerY: Float,
        radius: Int,
        rotation: Int
    ): Int {
         val yPlane = image.planes[0].buffer
         val width = image.width
         val height = image.height
         
         var bufX = centerX.toInt()
         var bufY = centerY.toInt()

         when (rotation) {
             90 -> {
                 bufX = centerY.toInt()
                 bufY = width - centerX.toInt()
             }
             270 -> {
                 bufX = height - centerY.toInt()
                 bufY = centerX.toInt()
             }
             180 -> {
                 bufX = width - centerX.toInt()
                 bufY = height - centerY.toInt()
             }
         }
         
         val startX = max(0, bufX - radius)
         val endX = min(width - 1, bufX + radius)
         val startY = max(0, bufY - radius)
         val endY = min(height - 1, bufY + radius)

         var sumY = 0L
         var count = 0
         val yRowStride = image.planes[0].rowStride
         val yPixelStride = image.planes[0].pixelStride // usually 1

         // Optimization: Sample every 4th pixel for speed
         for (y in startY..endY step 4) {
             for (x in startX..endX step 4) {
                 val yIdx = y * yRowStride + x * yPixelStride
                 val Y = (yPlane.get(yIdx).toInt() and 0xFF)
                 sumY += Y
                 count++
             }
         }
         
         return if (count > 0) (sumY / count).toInt() else 0
    }

    private fun getAverageColor(
    // ...

    private val detector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build()
    )

    private var lastProcessTime = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        // Throttle to ~20FPS processing to avoid overheating
        if (currentTime - lastProcessTime < 50) {
            imageProxy.close()
            return
        }
        lastProcessTime = currentTime

        val mediaImage = imageProxy.image
        if (mediaImage != null && mediaImage.format == ImageFormat.YUV_420_888) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            detector.process(inputImage)
                .addOnSuccessListener { faceMeshes ->
                    if (faceMeshes.isNotEmpty()) {
                        val faceMesh = faceMeshes[0]
                        
                        // Right Iris indices: 474, 475, 476, 477 (Approximation or specific landmarks)
                        // ML Kit Face Mesh standard 468 points.
                        // Right Iris center is often approximated. 
                        // We will use the Eye region points to define a bounding box.
                        // Right eye bounding box points: 33, 133 (corners).
                        // Better: Use specific Iris points if available or estimate from eye center.
                        // For simplicity in this prototype, we'll take the center of the Right Eye.
                        
                        // Right eye center approx: index 468 (Right Iris Center) is only available in some models?
                        // Actually standard 468 mesh: 
                        // Right Eye: 362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398
                        // We can just average the eye points to get the center.
                        
                        // BUT, to see reflection, we need the IRIS.
                        // Let's use landmark 473 (Left Iris) and 468 (Right Iris) if available? 
                        // The default model might not support refined iris.
                        // Let's safe-bet on averaging points 362 and 263 (corners of right eye) to get center, 
                        // and take a small box around it.
                        
                        val rightEyeCorner1 = faceMesh.getPoints(362).firstOrNull()
                        val rightEyeCorner2 = faceMesh.getPoints(263).firstOrNull()

                        if (rightEyeCorner1 != null && rightEyeCorner2 != null) {
                            val centerX = (rightEyeCorner1.position.x + rightEyeCorner2.position.x) / 2
                            val centerY = (rightEyeCorner1.position.y + rightEyeCorner2.position.y) / 2
                            
                            // Iris Analysis
                            val avgColor = getAverageColor(
                                imageProxy, 
                                centerX, 
                                centerY, 
                                radius = 10, 
                                rotation = imageProxy.imageInfo.rotationDegrees
                            )
                            
                            // Face Brightness Analysis (using the Face Bounding Box)
                            // We can use the face bounding box to sample brightness
                            val faceBox = faceMesh.boundingBox
                            // Map center of face to image coordinates?
                            // Simplified: Just use the center of the face mesh (approx nose)
                            val nose = faceMesh.getPoints(1).firstOrNull() // Tip of nose
                            val faceBrightness = if (nose != null) {
                                getAverageBrightness(
                                    imageProxy,
                                    nose.position.x,
                                    nose.position.y,
                                    radius = 50, // Larger area for face
                                    rotation = imageProxy.imageInfo.rotationDegrees
                                )
                            } else {
                                0 // Default
                            }

                            onIrisDetected(avgColor, faceBrightness)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("CameraAnalyzer", "Face detection failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun getAverageColor(
        image: ImageProxy,
        centerX: Float,
        centerY: Float,
        radius: Int,
        rotation: Int
    ): Triple<Int, Int, Int> {
        val yPlane = image.planes[0].buffer
        val uPlane = image.planes[1].buffer
        val vPlane = image.planes[2].buffer

        val width = image.width
        val height = image.height
        
        // Map logical coordinates to buffer coordinates based on rotation
        // ImageProxy buffer is always unrotated (landscape usually).
        // InputImage coordinates are rotated.
        
        var bufX = centerX.toInt()
        var bufY = centerY.toInt()

        when (rotation) {
            90 -> {
                bufX = centerY.toInt()
                bufY = width - centerX.toInt()
            }
            270 -> {
                bufX = height - centerY.toInt()
                bufY = centerX.toInt()
            }
            180 -> {
                bufX = width - centerX.toInt()
                bufY = height - centerY.toInt()
            }
        }
        
        // Boundaries
        val startX = max(0, bufX - radius)
        val endX = min(width - 1, bufX + radius)
        val startY = max(0, bufY - radius)
        val endY = min(height - 1, bufY + radius)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0

        // YUV NV21 layout hypothesis (standard for Camera2 usually) or YUV_420_888
        // Y plane stride
        val yRowStride = image.planes[0].rowStride
        val yPixelStride = image.planes[0].pixelStride // usually 1
        
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        for (y in startY..endY) {
            for (x in startX..endX) {
                // Y Index
                val yIdx = y * yRowStride + x * yPixelStride
                val Y = (yPlane.get(yIdx).toInt() and 0xFF)

                // UV Index (subsampled 2x2)
                val uvX = x / 2
                val uvY = y / 2
                val uvIdx = uvY * uvRowStride + uvX * uvPixelStride
                
                // V is usually first in NV21? 
                // For YUV_420_888, planes[1] is U, planes[2] is V.
                val U = (uPlane.get(uvIdx).toInt() and 0xFF) - 128
                val V = (vPlane.get(uvIdx).toInt() and 0xFF) - 128

                // YUV to RGB Conversion
                val R = (Y + 1.370705 * V).toInt().coerceIn(0, 255)
                val G = (Y - 0.337633 * U - 0.698001 * V).toInt().coerceIn(0, 255)
                val B = (Y + 1.732446 * U).toInt().coerceIn(0, 255)

                sumR += R
                sumG += G
                sumB += B
                count++
            }
        }

        return if (count > 0) {
            Triple((sumR / count).toInt(), (sumG / count).toInt(), (sumB / count).toInt())
        } else {
            Triple(0, 0, 0)
        }
    }
}
