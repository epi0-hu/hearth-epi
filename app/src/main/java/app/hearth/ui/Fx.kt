package app.hearth.ui

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** True when the user switched animations off system-wide, or turned off ambient effects. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberSystemReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

private class Ember(val x: Float, val speed: Float, val phase: Float, val size: Float, val sway: Float, val swayFreq: Float)

/**
 * The living hearth: a warm glow rising from the bottom of the screen that flickers and
 * flares while a character is writing, with embers drifting up through it.
 * Everything is drawn in the draw phase from one clock, so it never recomposes.
 */
@Composable
fun HearthBackground(intensity: Float, animate: Boolean, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val bg = MaterialTheme.colorScheme.background
    val ember = LocalChatPalette.current.ember
    val glowTarget by animateFloatAsState(intensity, tween(900, easing = FastOutSlowInEasing), label = "glow")
    val embers = remember {
        val r = Random(7)
        List(26) { Ember(r.nextFloat(), 0.35f + r.nextFloat() * 0.9f, r.nextFloat(), 0.8f + r.nextFloat() * 1.8f, 6f + r.nextFloat() * 22f, 0.5f + r.nextFloat() * 1.5f) }
    }
    val clock = if (animate) {
        val inf = rememberInfiniteTransition(label = "hearth")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    } else null

    Canvas(modifier.fillMaxSize()) {
        val t = clock?.value ?: 0.3f
        drawRect(bg)
        val time = t * 60f // seconds
        val flicker = if (clock != null) 0.86f + 0.08f * sin(time * 7.3f) + 0.06f * sin(time * 13.1f + 1.3f) else 1f
        val strength = (if (dark) 0.20f else 0.10f) * (0.75f + glowTarget * 0.9f) * flicker
        // Firelight pooling at the bottom
        drawRect(
            Brush.radialGradient(
                listOf(ember.copy(alpha = strength), ember.copy(alpha = strength * 0.35f), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 1.08f),
                radius = size.height * (0.75f + glowTarget * 0.1f),
            ),
        )
        // A cooler counter-glow at the top keeps it from feeling flat
        drawRect(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = if (dark) 0.25f else 0f), Color.Transparent), endY = size.height * 0.35f),
        )
        if (clock == null) return@Canvas
        val d = density
        for (e in embers) {
            val p = ((time / 60f) * e.speed * 9f + e.phase) % 1f // 0 = bottom, 1 = top
            val y = size.height * (1.02f - p * 0.9f)
            val x = size.width * e.x + sin((time * e.swayFreq + e.phase * 6f).toDouble() * PI / 2).toFloat() * e.sway * d
            val fade = (if (p < 0.12f) p / 0.12f else 1f) * (1f - p).coerceIn(0f, 1f)
            val a = fade * (0.35f + 0.5f * glowTarget) * (if (dark) 1f else 0.6f)
            val r = e.size * d
            drawCircle(ember.copy(alpha = a * 0.25f), radius = r * 3.2f, center = Offset(x, y))
            drawCircle(ember.copy(alpha = a), radius = r, center = Offset(x, y))
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Three ember dots bouncing in turn — the "writing" indicator. */
@Composable
fun EmberDots(color: Color = LocalChatPalette.current.ember, dot: Dp = 6.dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "dots")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "dt")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(dot * 0.7f)) {
        repeat(3) { i ->
            val local = ((t - i * 0.18f) % 1f + 1f) % 1f
            val lift = if (local < 0.5f) sin(local * 2 * PI).toFloat() else 0f
            Box(
                Modifier.size(dot).graphicsLayer {
                    translationY = -lift * dot.toPx() * 0.9f
                    alpha = 0.45f + 0.55f * lift
                }.clip(CircleShape).drawBehind { drawCircle(color) },
            )
        }
    }
}

/** An animated glowing ring around an avatar (pulses while active). */
fun Modifier.glowRing(active: Boolean, color: Color, width: Dp = 2.dp): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val shown by animateFloatAsState(if (active) 1f else 0f, tween(400), label = "ring")
    val pulse = if (active && !reduced) {
        val inf = rememberInfiniteTransition(label = "pulse")
        inf.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p").value
    } else 1f
    drawBehind {
        if (shown <= 0.01f) return@drawBehind
        val w = width.toPx()
        drawCircle(color.copy(alpha = 0.25f * shown * pulse), radius = size.minDimension / 2 + w * 3f)
        drawCircle(color.copy(alpha = shown * pulse), radius = size.minDimension / 2 + w, style = Stroke(w))
    }
}

/** Gentle press feedback: scales the element down slightly while pressed. */
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (isPressed) pressed else 1f, tween(140), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Transparent app bars so the hearth shows through. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun hearthBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
)

/** A soft rotating ring used on the stop button while generating. */
@Composable
fun SpinningEmberRing(size: Dp, color: Color = LocalChatPalette.current.ember) {
    val inf = rememberInfiniteTransition(label = "spin")
    val angle by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = angle }) {
        drawArc(
            Brush.sweepGradient(listOf(Color.Transparent, color.copy(alpha = 0.3f), color)),
            startAngle = 0f, sweepAngle = 300f, useCenter = false, style = Stroke(2.5.dp.toPx()),
        )
    }
}

@Composable
fun FullSize(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
