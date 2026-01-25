package com.example.photonid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun LiveChart(
    sentSignal: List<Color>,
    receivedSignal: List<Color>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.background(Color.Black)) {
        val width = size.width
        val height = size.height
        val maxPoints = 300 // 5 seconds @ 60fps

        // Draw Sent Signal (Top half of graph?) or overlaid?
        // Let's just draw Brightness (Luminance) for simplicity to visualize latency,
        // or Red channel if we want specific color latency.
        // User asked for "Sent Signal" vs "Received Signal" RGB values.
        // Graphing 3 lines (R, G, B) x 2 signals is messy.
        // Let's graph "Intensity" (Average RGB) for both.
        
        if (sentSignal.isEmpty() || receivedSignal.isEmpty()) return@Canvas

        val stepX = width / maxPoints.toFloat()

        // Helper to get intensity 0..1
        fun Color.intensity(): Float = (red + green + blue) / 3f

        // Draw SENT
        val sentPath = Path()
        sentSignal.takeLast(maxPoints).forEachIndexed { index, color ->
            val x = index * stepX
            val y = height - (color.intensity() * height) 
            if (index == 0) sentPath.moveTo(x, y) else sentPath.lineTo(x, y)
        }
        drawPath(sentPath, Color.Red.copy(alpha=0.8f), style = Stroke(width = 3f))

        // Draw RECEIVED
        val receivedPath = Path()
        receivedSignal.takeLast(maxPoints).forEachIndexed { index, color ->
            val x = index * stepX
            val y = height - (color.intensity() * height)
            if (index == 0) receivedPath.moveTo(x, y) else receivedPath.lineTo(x, y)
        }
        drawPath(receivedPath, Color.Cyan, style = Stroke(width = 3f))
    }
}
