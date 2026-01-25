package com.example.photonid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PhotonIDApp() {
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    
    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (cameraPermissionState.status.isGranted) {
        PhotonIDScreen()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission required")
        }
    }
}

@Composable
fun PhotonIDScreen() {
    // Shared State for Emitter Color
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.White, Color.Black)
    var colorIndex by remember { mutableIntStateOf(0) }
    val currentSentColor = colors[colorIndex]
    
    // Timer Logic
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            colorIndex = (colorIndex + 1) % colors.size
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Half: Emitter
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            EmitterView(color = currentSentColor)
        }

        // Bottom Half: Dashboard
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            DashboardView(currentSentColor = currentSentColor)
        }
    }
}

@Composable
fun EmitterView(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "SIGNAL SENT",
            color = if (color == Color.White || color == Color.Green) Color.Black else Color.White,
            style = MaterialTheme.typography.headlineLarge
        )
    }
}

@Composable
fun DashboardView(
    currentSentColor: Color
) {
    var iso by remember { mutableFloatStateOf(800f) }
    var exposure by remember { mutableFloatStateOf(20f) }
    var isSmartAeEnabled by remember { mutableStateOf(false) }
    
    // State for received signal
    var receivedColor by remember { mutableStateOf(Color.Black) }

    // History for graphing
    val sentHistory = remember { mutableStateListOf<Color>() }
    val receivedHistory = remember { mutableStateListOf<Color>() }

    // Update history periodically (e.g. every frame or fixed interval)
    LaunchedEffect(currentSentColor, receivedColor) {
         // This effect triggers whenever inputs change. 
         // Since receivedColor updates rapidly (~20fps), this is fine.
         // Limit history size
        if (sentHistory.size > 300) sentHistory.removeFirst()
        sentHistory.add(currentSentColor)

        if (receivedHistory.size > 300) receivedHistory.removeFirst()
        receivedHistory.add(receivedColor)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(Color.DarkGray)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("DASHBOARD", color = Color.White, style = MaterialTheme.typography.titleMedium)
            
            // Smart AE Toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Smart AE", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isSmartAeEnabled,
                    onCheckedChange = { isSmartAeEnabled = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Magenta)
                )
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
             Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "SENT: R${(currentSentColor.red*255).toInt()} G${(currentSentColor.green*255).toInt()} B${(currentSentColor.blue*255).toInt()}",
                    color = currentSentColor,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "RECV: R${(receivedColor.red*255).toInt()} G${(receivedColor.green*255).toInt()} B${(receivedColor.blue*255).toInt()}",
                    color = receivedColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Camera Preview Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            CameraPreview(
                iso = iso,
                exposure = exposure,
                isSmartAeEnabled = isSmartAeEnabled,
                onIrisDetected = { (r, g, b) ->
                    // Update UI state
                    receivedColor = Color(r, g, b)
                },
                onSmartIsoChange = { newIso ->
                    iso = newIso
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Controls
        Text("ISO: ${iso.toInt()}", color = Color.White)
        Slider(
            enabled = !isSmartAeEnabled, // Disable manual slider when Smart AE is on
            value = iso,
            onValueChange = { iso = it },
            valueRange = 100f..3200f
        )

        Text("Exposure (ms): ${exposure.toInt()}", color = Color.White)
        Slider(
            value = exposure,
            onValueChange = { exposure = it },
            valueRange = 1f..100f
        )
        
        // Live Chart
        Box(
            modifier = Modifier
                .height(150.dp)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            LiveChart(
                sentSignal = sentHistory,
                receivedSignal = receivedHistory,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
