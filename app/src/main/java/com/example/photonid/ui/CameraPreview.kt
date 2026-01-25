package com.example.photonid.ui

import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.photonid.camera.CameraAnalyzer
import com.example.photonid.camera.ManualCameraHelper
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    iso: Float,
    exposure: Float,
    isSmartAeEnabled: Boolean,
    onIrisDetected: (Triple<Int, Int, Int>) -> Unit,
    onSmartIsoChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val smartController = remember { SmartExposureController() }
    
    // Keep track of the camera control to update manual settings
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }

    // Update manual controls when sliders change OR Smart AE changes
    LaunchedEffect(iso, exposure, cameraControl) {
        val control = cameraControl
        if (control != null) {
            ManualCameraHelper.setManualControls(control, iso.toInt(), exposure)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480)) // Lower res for performance
                    .build()
                
                imageAnalysis.setAnalyzer(executor, CameraAnalyzer { irisColor, faceBrightness ->
                    onIrisDetected(irisColor)
                    
                    if (isSmartAeEnabled) {
                        val newIso = smartController.calculateNewIso(faceBrightness, iso.toInt())
                        if (newIso != null) {
                            onSmartIsoChange(newIso.toFloat())
                        }
                    }
                })

                // Select Front camera for self-testing (looking at screen)
                val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                    cameraControl = camera.cameraControl
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}
