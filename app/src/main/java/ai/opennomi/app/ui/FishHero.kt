package ai.opennomi.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Compose Animation is AndroidX's Apache-2.0 open-source animation runtime.
 * Wave rendering is a vendored adaptation of audio-visualizer-android.
 */
@Composable
internal fun FishHero(busy: Boolean, audioSession: Int, reduced: Boolean, voice: String) {
    var phase by remember { mutableFloatStateOf(0f) }
    val owner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(reduced, foreground) {
        if (reduced || !foreground) { phase = 0f; return@LaunchedEffect }
        val animation = Animatable(0f)
        animation.animateTo(1f, infiniteRepeatable(tween(7000, easing = LinearEasing))) { phase = value }
    }
    Column(Modifier.fillMaxWidth().background(
        Brush.linearGradient(listOf(Color(0xFF23233B), Color(0xFF152733), Color(0xFF181D2C))), RoundedCornerShape(28.dp)
    ).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("专属声音", fontSize = 12.sp, color = Color(0xFFB7B5D5))
            Text(if(audioSession > 0) "播放中" else if(busy) "合成中" else "准备就绪", fontSize = 12.sp, color = Color(0xFF9EDDEC))
        }
        Canvas(Modifier.size(142.dp).padding(8.dp)) {
            val radius = size.minDimension * .34f
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFB9A8FF).copy(alpha = .32f), Color.Transparent)), radius * 1.7f, center)
            drawCircle(Brush.linearGradient(listOf(Color(0xFFE0D8FF), Color(0xFFBCA6FD), Color(0xFF7DD3FC))), radius, center)
            drawCircle(Color.White.copy(alpha = .42f), radius * .77f, center, style = Stroke(1.dp.toPx()))
            for (i in 0..2) {
                val angle = (phase * 2 * PI + i * 2 * PI / 3)
                val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * radius * 1.28f
                drawCircle(if(i == 0) Color(0xFF7DD3FC) else Color(0xFFD1C3FF), (if(i == 0) 3 else 2).dp.toPx(), point)
            }
            for (i in 0..4) {
                val h = (7 + (2 - kotlin.math.abs(i - 2)) * 5).dp.toPx()
                val x = center.x + (i - 2) * 8.dp.toPx()
                drawLine(Color(0xFF36395B), Offset(x, center.y - h), Offset(x, center.y + h), 3.dp.toPx())
            }
        }
        Text(voice.take(24), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color(0xFFF1EDFF))
        AndroidView(factory = { FishWaveView(it) }, update = { it.configure(if(foreground)audioSession else 0, reduced) },
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(top = 8.dp))
    }
}
