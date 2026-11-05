package io.github.nikkittap.timelineexporter.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.nikkittap.timelineexporter.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

internal class TapSequence(private val count: Int, private val windowMs: Long) {
    private var first = 0L
    private var taps = 0
    fun tap(now: Long = SystemClock.uptimeMillis()): Boolean {
        if (taps == 0 || now < first || now - first > windowMs) { first = now; taps = 0 }
        taps++
        if (taps < count) return false
        taps = 0
        return true
    }
}

internal enum class JourneyScene { DISTANCE, TRAVELER, POSTCARD }

@Composable
internal fun JourneyDialog(scene: JourneyScene, meters: Double = 0.0, onDismiss: () -> Unit) {
    val progress = remember(scene) { Animatable(0f) }
    LaunchedEffect(scene) { progress.animateTo(1f, tween(if (scene == JourneyScene.TRAVELER) 4000 else 5000, easing = LinearEasing)) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val blue = MaterialTheme.colorScheme.primary
                val background = MaterialTheme.colorScheme.primaryContainer
                Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                    val sx = size.width / 300f
                    val sy = size.height / 150f
                    // The globe and its orbit use one scale on both axes. The
                    // other scenes retain their existing layout and proportions.
                    val globeScale = minOf(sx, sy)
                    withTransform({
                        if (scene == JourneyScene.DISTANCE) {
                            translate((size.width - 300f * globeScale) / 2f, (size.height - 150f * globeScale) / 2f)
                            scale(globeScale, globeScale, pivot = Offset.Zero)
                        } else {
                            scale(sx, sy, pivot = Offset.Zero)
                        }
                    }) {
                        when (scene) {
                            JourneyScene.DISTANCE -> {
                                drawCircle(background, 52f, Offset(150f, 73f))
                                drawCircle(blue, 52f, Offset(150f, 73f), style = Stroke(2f))
                                val globeClip = Path().apply {
                                    addOval(androidx.compose.ui.geometry.Rect(98f, 21f, 202f, 125f))
                                }
                                clipPath(globeClip) {
                                    rotate(progress.value * 14f, Offset(150f, 73f)) {
                                        val americas = Path().apply {
                                            moveTo(104f, 48f); lineTo(113f, 37f); lineTo(130f, 33f)
                                            lineTo(140f, 40f); lineTo(136f, 46f); lineTo(128f, 47f)
                                            lineTo(130f, 53f); lineTo(124f, 59f); lineTo(127f, 65f)
                                            lineTo(134f, 67f); lineTo(140f, 74f); lineTo(138f, 82f)
                                            lineTo(132f, 87f); lineTo(130f, 99f); lineTo(125f, 108f)
                                            lineTo(120f, 97f); lineTo(117f, 87f); lineTo(118f, 77f)
                                            lineTo(111f, 68f); lineTo(103f, 61f); close()
                                        }
                                        val eurasia = Path().apply {
                                            moveTo(151f, 38f); lineTo(161f, 30f); lineTo(180f, 33f)
                                            lineTo(196f, 43f); lineTo(201f, 55f); lineTo(193f, 60f)
                                            lineTo(184f, 56f); lineTo(177f, 61f); lineTo(171f, 57f)
                                            lineTo(166f, 62f); lineTo(159f, 57f); lineTo(151f, 59f)
                                            lineTo(146f, 52f); lineTo(151f, 47f); close()
                                        }
                                        val africa = Path().apply {
                                            moveTo(149f, 63f); lineTo(159f, 61f); lineTo(170f, 67f)
                                            lineTo(174f, 75f); lineTo(168f, 84f); lineTo(164f, 96f)
                                            lineTo(158f, 104f); lineTo(151f, 94f); lineTo(146f, 82f)
                                            lineTo(143f, 72f); close()
                                        }
                                        drawPath(americas, blue.copy(alpha = .42f))
                                        drawPath(eurasia, blue.copy(alpha = .42f))
                                        drawPath(africa, blue.copy(alpha = .42f))
                                        drawOval(blue.copy(alpha = .42f), Offset(181f, 89f), Size(13f, 8f))
                                    }
                                }
                                drawCircle(blue.copy(alpha = .5f), 67f, Offset(150f, 73f), style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
                                val a = progress.value * 6.283185f - 1.57f
                                drawPlane(Offset(150f + 67f * cos(a), 73f + 67f * sin(a)), 17f, blue, progress.value * 360f + 90f)
                            }
                            JourneyScene.TRAVELER -> {
                                drawLine(blue.copy(alpha = .45f), Offset(25f, 123f), Offset(275f, 123f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 7f)))
                                drawSnail(Offset(75f + 100f * progress.value, 105f), blue, background)
                            }
                            JourneyScene.POSTCARD -> {
                                drawSnail(Offset(68f, 111f), blue, background)
                                // Shift the plane and its entire path together;
                                // the 300-unit viewport leaves room at both edges.
                                translate(left = 40f) {
                                    val heart = Path().apply { moveTo(150f, 55f); cubicTo(122f, 24f, 85f, 55f, 120f, 93f); lineTo(150f, 118f); lineTo(180f, 93f); cubicTo(215f, 55f, 178f, 24f, 150f, 55f) }
                                    val measure = PathMeasure().apply { setPath(heart, false) }
                                    val heartProgress = ((progress.value - .2f) / .8f).coerceIn(0f, 1f)
                                    val visible = Path()
                                    measure.getSegment(0f, measure.length * heartProgress, visible, true)
                                    drawPath(visible, blue.copy(alpha = .55f), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
                                    val entry = Offset(30f + 120f * (progress.value / .2f).coerceIn(0f, 1f), 42f + 13f * (progress.value / .2f).coerceIn(0f, 1f))
                                    val at = if (progress.value < .2f) entry else measure.getPosition(measure.length * heartProgress)
                                    val tangent = if (progress.value < .2f) Offset(1f, 0f) else measure.getTangent(measure.length * heartProgress)
                                    val angle = Math.toDegrees(kotlin.math.atan2(tangent.y.toDouble(), tangent.x.toDouble())).toFloat() + 90f
                                    drawPlane(at, 24f, blue, angle, expressive = true)
                                }
                            }
                        }
                    }
                }
                val message = when (scene) {
                    JourneyScene.DISTANCE -> {
                        val n = formatJourneyRatio(meters, LocalConfiguration.current.locales[0])
                        stringResource(R.string.journey_distance, n)
                    }
                    JourneyScene.TRAVELER -> stringResource(R.string.journey_traveler)
                    JourneyScene.POSTCARD -> stringResource(R.string.journey_postcard)
                }
                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        alpha = if (scene == JourneyScene.POSTCARD) ((progress.value - .8f) / .2f).coerceIn(0f, 1f) else 1f
                    },
                )
            }
        }
    }
}

internal fun DrawScope.drawPlane(center: Offset, size: Float, blue: Color, angle: Float, alpha: Float = 1f, expressive: Boolean = false) {
    rotate(angle, center) {
        val p = Path().apply { moveTo(center.x, center.y - size / 2); lineTo(center.x + size * .46f, center.y + size * .42f); lineTo(center.x, center.y + size * .22f); lineTo(center.x - size * .46f, center.y + size * .42f); close() }
        drawPath(p, Color(0xFFFFF9F0).copy(alpha = alpha))
        drawPath(p, blue.copy(alpha = alpha), style = Stroke(size * .06f))
        drawLine(blue.copy(alpha = alpha), Offset(center.x, center.y - size * .38f), Offset(center.x, center.y + size * .22f), size * .04f)
        if (expressive) {
            drawCircle(blue, size * .035f, Offset(center.x - size * .12f, center.y + size * .12f))
            drawCircle(blue, size * .035f, Offset(center.x + size * .12f, center.y + size * .12f))
        }
    }
}

private fun DrawScope.drawSnail(center: Offset, blue: Color, pale: Color) {
    val body = Path().apply { moveTo(center.x - 35, center.y + 16); quadraticTo(center.x + 25, center.y + 25, center.x + 34, center.y + 5); lineTo(center.x + 32, center.y - 25); quadraticTo(center.x + 28, center.y - 38, center.x + 18, center.y - 26); lineTo(center.x + 14, center.y + 3); close() }
    drawPath(body, Color(0xFFFFDEB8)); drawPath(body, blue, style = Stroke(2.5f))
    drawCircle(Color(0xFFE5B67A), 24f, Offset(center.x - 8, center.y - 4)); drawCircle(blue, 24f, Offset(center.x - 8, center.y - 4), style = Stroke(2.5f))
    drawArc(blue, 45f, 280f, false, Offset(center.x - 21, center.y - 17), Size(26f, 26f), style = Stroke(2f))
    drawRoundRect(pale, Offset(center.x - 26, center.y - 38), Size(34f, 20f), androidx.compose.ui.geometry.CornerRadius(6f)); drawRoundRect(blue, Offset(center.x - 26, center.y - 38), Size(34f, 20f), androidx.compose.ui.geometry.CornerRadius(6f), style = Stroke(2f))
    drawLine(blue, Offset(center.x - 23, center.y - 29), Offset(center.x + 5, center.y - 29), 1.5f)
    drawRoundRect(blue, Offset(center.x - 12, center.y - 31), Size(5f, 5f), androidx.compose.ui.geometry.CornerRadius(1f), style = Stroke(1.5f))
    drawLine(blue, Offset(center.x + 21, center.y - 28), Offset(center.x + 17, center.y - 43), 2f); drawLine(blue, Offset(center.x + 29, center.y - 29), Offset(center.x + 34, center.y - 43), 2f)
    drawCircle(blue, 2f, Offset(center.x + 17, center.y - 43)); drawCircle(blue, 2f, Offset(center.x + 34, center.y - 43)); drawCircle(blue, 1.8f, Offset(center.x + 26, center.y - 20))
}

/** Formats the distance comparison in the language selected for the app. */
internal fun formatJourneyRatio(meters: Double, locale: Locale): String {
    val ratio = meters / 40_075_000.0
    val decimals = if (ratio > 0.0 && ratio < .01)
        (-kotlin.math.floor(kotlin.math.log10(ratio)).toInt() + 1).coerceIn(2, 12) else 2
    return NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = decimals
        minimumFractionDigits = 0
    }.format(ratio)
}
