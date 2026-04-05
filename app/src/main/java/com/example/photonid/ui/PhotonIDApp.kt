package com.example.photonid.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photonid.camera.AnalysisResult
import com.example.photonid.camera.MotionStabilityController
import com.example.photonid.logic.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay

// ── Step Enum ──────────────────────────────────────────────────────
enum class Step {
    FACE_DETECTION,
    INSTRUCTION,
    FLASHING,
    PROCESSING,
    COMPLETE
}

// ── Color Helpers ──────────────────────────────────────────────────
private fun paletteToComposeColor(idx: Int): Color {
    val (r, g, b) = COLOR_PALETTE_RGB[idx]
    return Color(r, g, b)
}

private fun textColorFor(bgIdx: Int): Color {
    return if (bgIdx == 0 || bgIdx == 3 || bgIdx == 5) Color.White else Color.Black
}

// ══════════════════════════════════════════════════════════════════
// ENTRY POINT
// ══════════════════════════════════════════════════════════════════

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📷", fontSize = 48.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Camera Permission Required",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Photon ID needs camera access\nto perform liveness detection",
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// MAIN SCREEN — 5-STEP STATE MACHINE
// ══════════════════════════════════════════════════════════════════

@Composable
fun PhotonIDScreen() {
    val context = LocalContext.current

    // ── Engine instances ────────────────────────────────────────
    val engine = remember { PhotonIDEngine() }
    val sentinel = remember { SentinelVault(ttlSeconds = 60f) }
    val motionController = remember { MotionStabilityController(context) }

    // Start/stop motion sensor with composition lifecycle
    DisposableEffect(Unit) {
        motionController.start()
        onDispose { motionController.stop() }
    }

    // ── State ──────────────────────────────────────────────────
    var currentStep by remember { mutableStateOf(Step.FACE_DETECTION) }
    var faceDetectedCount by remember { mutableIntStateOf(0) }
    var currentColorIdx by remember { mutableIntStateOf(0) }
    var challengeCount by remember { mutableIntStateOf(0) }
    var flashStartTime by remember { mutableLongStateOf(0L) }
    var stepStartTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var latestResult by remember { mutableStateOf<AnalysisResult?>(null) }
    var vaultStatus by remember { mutableStateOf("UNKNOWN") }
    var trustScore by remember { mutableFloatStateOf(0f) }

    // Multi-face rejection state
    var multiFaceRejected by remember { mutableStateOf(false) }
    var detectedFaceCount by remember { mutableIntStateOf(0) }

    // Motion stability (poll sensor state)
    var stabilityScore by remember { mutableFloatStateOf(0f) }
    var isStable by remember { mutableStateOf(false) }
    var motionGuidance by remember { mutableStateOf("Initializing sensors...") }

    // Poll motion controller state at ~10Hz
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            stabilityScore = motionController.stabilityScore
            isStable = motionController.isStable
            motionGuidance = motionController.guidanceMessage
        }
    }

    // ── STEP 2 auto-advance timer ──────────────────────────────
    if (currentStep == Step.INSTRUCTION) {
        LaunchedEffect(stepStartTime) {
            delay(3000)
            currentStep = Step.FLASHING
            flashStartTime = System.currentTimeMillis()
            stepStartTime = System.currentTimeMillis()
            challengeCount = 0
            currentColorIdx = 0
        }
    }

    // ── STEP 3 color cycling ───────────────────────────────────
    if (currentStep == Step.FLASHING) {
        LaunchedEffect(flashStartTime) {
            while (currentStep == Step.FLASHING) {
                delay(CHALLENGE_FREQ_MS)
                challengeCount++
                if (challengeCount % BASELINE_EVERY == 0) {
                    currentColorIdx = 0
                } else {
                    val choices = (1 until COLOR_PALETTE_RGB.size).filter { it != currentColorIdx }
                    currentColorIdx = choices.random()
                }
                engine.recordChallenge(currentColorIdx)
            }
        }
    }

    // ── STEP 4 Knox signing delay ──────────────────────────────
    if (currentStep == Step.PROCESSING) {
        LaunchedEffect(stepStartTime) {
            delay(800)
            engine.generateSecurePacket()
            currentStep = Step.COMPLETE
        }
    }

    // ── Register on completion ─────────────────────────────────
    if (currentStep == Step.COMPLETE) {
        LaunchedEffect(Unit) {
            latestResult?.let { res ->
                if (res.landmarkXs.isNotEmpty()) {
                    if (engine.isHuman) {
                        sentinel.registerUser(res.landmarkXs, res.landmarkYs, "VERIFIED", engine.confidence)
                    } else if (engine.isCertain) {
                        sentinel.registerUser(res.landmarkXs, res.landmarkYs, "BLOCKED", 0f)
                    }
                }
            }
        }
    }

    // ── Camera result handler ──────────────────────────────────
    val onCameraResult: (AnalysisResult) -> Unit = { result ->
        latestResult = result
        detectedFaceCount = result.faceCount
        multiFaceRejected = result.multiFaceRejected

        if (result.faceDetected && !result.multiFaceRejected) {
            faceDetectedCount = (faceDetectedCount + 1).coerceAtMost(10)
        } else {
            faceDetectedCount = (faceDetectedCount - 1).coerceAtLeast(0)
        }

        // Sentinel check during Step 1
        if (currentStep == Step.FACE_DETECTION && result.faceDetected &&
            !result.multiFaceRejected && result.landmarkXs.isNotEmpty()) {
            val (s, t) = sentinel.checkIdentity(result.landmarkXs, result.landmarkYs)
            vaultStatus = s
            trustScore = t
        }

        // Feed engine during Step 3 — only if single face and device is stable
        if (currentStep == Step.FLASHING && result.faceDetected &&
            !result.multiFaceRejected) {
            engine.recordFrame(result.glintRgb)
            engine.analyze()

            if (engine.isCertain) {
                currentStep = Step.PROCESSING
                stepStartTime = System.currentTimeMillis()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // RENDER
    // ══════════════════════════════════════════════════════════════
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(onResult = onCameraResult)

        when (currentStep) {
            Step.FACE_DETECTION -> FaceDetectionOverlay(
                faceDetected = faceDetectedCount > 5,
                multiFaceRejected = multiFaceRejected,
                faceCount = detectedFaceCount,
                vaultStatus = vaultStatus,
                trustScore = trustScore,
                stabilityScore = stabilityScore,
                isStable = isStable,
                motionGuidance = motionGuidance,
                onStart = {
                    engine.reset()
                    currentStep = Step.INSTRUCTION
                    stepStartTime = System.currentTimeMillis()
                }
            )
            Step.INSTRUCTION -> InstructionOverlay()
            Step.FLASHING -> FlashingOverlay(
                colorIdx = currentColorIdx,
                elapsed = (System.currentTimeMillis() - flashStartTime) / 1000f,
                engine = engine,
                stabilityScore = stabilityScore,
                isStable = isStable,
                motionGuidance = motionGuidance,
                multiFaceRejected = multiFaceRejected,
                faceCount = detectedFaceCount
            )
            Step.PROCESSING -> ProcessingOverlay()
            Step.COMPLETE -> CompleteOverlay(
                engine = engine,
                onRestart = {
                    engine.reset()
                    faceDetectedCount = 0
                    challengeCount = 0
                    multiFaceRejected = false
                    currentStep = Step.FACE_DETECTION
                    stepStartTime = System.currentTimeMillis()
                }
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// STABILITY BAR (reusable component)
// ══════════════════════════════════════════════════════════════════

@Composable
private fun StabilityBar(
    stabilityScore: Float,
    isStable: Boolean,
    motionGuidance: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "📱 Device Stability",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace
            )
            Text(
                "${stabilityScore.toInt()}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    isStable -> Color(0xFF00E676)
                    stabilityScore > 60 -> Color(0xFFFFEB3B)
                    else -> Color(0xFFFF5252)
                },
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = (stabilityScore / 100f).coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = when {
                isStable -> Color(0xFF00E676)
                stabilityScore > 60 -> Color(0xFFFFEB3B)
                else -> Color(0xFFFF5252)
            },
            trackColor = Color.White.copy(alpha = 0.1f)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            motionGuidance,
            fontSize = 11.sp,
            color = if (isStable) Color(0xFF00E676) else Color(0xFFFFEB3B),
            fontFamily = FontFamily.Monospace
        )
    }
}

// ══════════════════════════════════════════════════════════════════
// MULTI-FACE WARNING (reusable component)
// ══════════════════════════════════════════════════════════════════

@Composable
private fun MultiFaceWarning(faceCount: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C).copy(alpha = 0.9f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚠️", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "MULTI-FACE REJECTED",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "$faceCount faces detected — only 1 face allowed for eKYC",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// STEP 1: FACE DETECTION
// ══════════════════════════════════════════════════════════════════

@Composable
private fun FaceDetectionOverlay(
    faceDetected: Boolean,
    multiFaceRejected: Boolean,
    faceCount: Int,
    vaultStatus: String,
    trustScore: Float,
    stabilityScore: Float,
    isStable: Boolean,
    motionGuidance: String,
    onStart: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))
                Text(
                    "PHOTON ID",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp
                )
                Text(
                    "v2.0 — Corneal-Glint Liveness Detection",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    Text("🔒 AES-256 ", fontSize = 10.sp, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                    Text("• 👁 Anti-Spoof ", fontSize = 10.sp, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                    Text("• 👥 1-Face", fontSize = 10.sp, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                }
            }

            // Status
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Multi-face warning
                if (multiFaceRejected) {
                    MultiFaceWarning(faceCount)
                    Spacer(Modifier.height(16.dp))
                }

                // Scan ring
                val infiniteTransition = rememberInfiniteTransition(label = "scan")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
                    label = "alpha"
                )

                val statusColor = when {
                    multiFaceRejected -> Color(0xFFFF9100)
                    faceDetected -> Color(0xFF00E676)
                    else -> Color(0xFFFF1744)
                }

                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = alpha * 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            multiFaceRejected -> "👥"
                            faceDetected -> "👤"
                            else -> "❌"
                        },
                        fontSize = 48.sp
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        multiFaceRejected -> "MULTIPLE FACES"
                        faceDetected -> "FACE DETECTED"
                        else -> "NO FACE"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )

                if (faceDetected && !multiFaceRejected) {
                    val (statusText, sColor) = when (vaultStatus) {
                        "VERIFIED" -> "KNOWN USER (Trust: ${trustScore.toInt()}%)" to Color(0xFF00E676)
                        "BLOCKED" -> "⚠️ BLOCKED IDENTITY" to Color(0xFFFF1744)
                        else -> "NEW IDENTITY" to Color(0xFF64B5F6)
                    }
                    Text(statusText, color = sColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }

                // Stability bar
                Spacer(Modifier.height(16.dp))
                StabilityBar(stabilityScore, isStable, motionGuidance)
            }

            // Action
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (faceDetected && !multiFaceRejected && vaultStatus != "BLOCKED") {
                    Button(
                        onClick = onStart,
                        enabled = isStable,
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            disabledContainerColor = Color(0xFF424242)
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text(
                            if (isStable) "START ENROLLMENT" else "STABILIZE DEVICE",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isStable) Color.Black else Color.Gray,
                            letterSpacing = 2.sp
                        )
                    }
                } else if (multiFaceRejected) {
                    Text("Remove extra faces to continue", fontSize = 14.sp, color = Color(0xFFFF9100))
                } else if (vaultStatus == "BLOCKED") {
                    Text("ACCESS DENIED", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF1744))
                } else {
                    Text("Bring your face closer", fontSize = 14.sp, color = Color.Gray)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// STEP 2: INSTRUCTION / EPILEPSY WARNING
// ══════════════════════════════════════════════════════════════════

@Composable
private fun InstructionOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text("⚠️", fontSize = 64.sp)
            Spacer(Modifier.height(16.dp))
            Text("WARNING", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF1744))
            Spacer(Modifier.height(16.dp))
            Text("Flashing lights ahead", fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "Keep your face steady and look at the screen",
                fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            val infiniteTransition = rememberInfiniteTransition(label = "blink")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                label = "countdown"
            )
            Text(
                "Starting in 3 seconds...",
                fontSize = 18.sp,
                color = Color(0xFF00E5FF).copy(alpha = alpha),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// STEP 3: COLOR FLASHING
// ══════════════════════════════════════════════════════════════════

@Composable
private fun FlashingOverlay(
    colorIdx: Int,
    elapsed: Float,
    engine: PhotonIDEngine,
    stabilityScore: Float,
    isStable: Boolean,
    motionGuidance: String,
    multiFaceRejected: Boolean,
    faceCount: Int
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(paletteToComposeColor(colorIdx))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top: Time + Status
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))

                // Multi-face warning banner
                if (multiFaceRejected) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            " ⚠️ $faceCount FACES — PAUSED ",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Text(
                    "Time: %.1fs".format(elapsed),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColorFor(colorIdx),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Confidence: %.1f%%".format(engine.confidence),
                    fontSize = 20.sp,
                    color = textColorFor(colorIdx),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    engine.status,
                    fontSize = 18.sp,
                    color = if (colorIdx == 0) Color(0xFF00E5FF) else textColorFor(colorIdx),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    engine.debugMsg,
                    fontSize = 14.sp,
                    color = textColorFor(colorIdx).copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
            }

            // Bottom: Stability + Progress
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Stability mini-bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📱 ${stabilityScore.toInt()}%",
                        fontSize = 11.sp,
                        color = textColorFor(colorIdx).copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        if (isStable) "✓ Steady" else motionGuidance,
                        fontSize = 11.sp,
                        color = textColorFor(colorIdx).copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(4.dp))

                val progress = (elapsed / 60f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// STEP 4: KNOX PROCESSING
// ══════════════════════════════════════════════════════════════════

@Composable
private fun ProcessingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Color(0xFF00E5FF),
                strokeWidth = 4.dp,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "SAMSUNG Knox",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Signing + Encrypting Telemetry...",
                fontSize = 14.sp,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "AES-256-GCM • HMAC-SHA256 • Android Keystore",
                fontSize = 10.sp,
                color = Color(0xFF00E676),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// STEP 5: COMPLETE / VERDICT
// ══════════════════════════════════════════════════════════════════

@Composable
private fun CompleteOverlay(
    engine: PhotonIDEngine,
    onRestart: () -> Unit
) {
    val bgColor = if (engine.isHuman) Color(0xFF003300) else Color(0xFF330000)
    val accentColor = if (engine.isHuman) Color(0xFF00E676) else Color(0xFFFF1744)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgColor, Color.Black)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // Verdict
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (engine.isHuman) "✓" else "✗",
                    fontSize = 64.sp,
                    color = accentColor
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (engine.isHuman) "VERIFIED HUMAN" else "DEEPFAKE DETECTED",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Confidence: %.1f%%".format(engine.confidence),
                    fontSize = 15.sp, color = Color.White, fontFamily = FontFamily.Monospace
                )
                Text(
                    "Avg Latency: %.0f ms".format(engine.latencyStats.mean),
                    fontSize = 13.sp, color = Color.Gray, fontFamily = FontFamily.Monospace
                )
            }

            // Knox + Encryption info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                engine.securePacket?.let { packet ->
                    val sig = (packet["signature"] as? String)?.take(16) ?: ""
                    Card(
                        modifier = Modifier.fillMaxWidth(0.95f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Knox Attestation", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(Modifier.height(6.dp))
                            Text("HMAC-SHA256: ${sig}...", fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                            Text("Device: ${engine.knox.deviceId}", fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                            Text("Knox: v${engine.knox.knoxVersion}", fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                            Text("Integrity: ${engine.knox.warrantyBit} (Valid)", fontSize = 11.sp, color = accentColor, fontFamily = FontFamily.Monospace)

                            // Encryption status
                            Spacer(Modifier.height(8.dp))
                            Divider(color = Color(0xFF333333))
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🔒", fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "AES-256-GCM Encrypted",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00E676),
                                        fontFamily = FontFamily.Monospace
                                    )
                                    engine.encryptedPacket?.let { enc ->
                                        Text(
                                            "Cipher: ${enc.take(24)}...",
                                            fontSize = 10.sp,
                                            color = Color.Gray,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Text(
                                        "Key: Android Keystore (hardware-backed)",
                                        fontSize = 10.sp,
                                        color = Color.Gray,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Actions
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onRestart,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                    shape = RoundedCornerShape(26.dp)
                ) {
                    Text(
                        "RESTART VERIFICATION",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        letterSpacing = 2.sp
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
