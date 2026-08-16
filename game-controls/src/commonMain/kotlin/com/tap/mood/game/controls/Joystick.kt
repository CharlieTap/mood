@file:Suppress("FunctionName")

package com.tap.mood.game.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

@Composable
fun Joystick(
    label: String,
    onDisplacementChanged: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 112.dp,
    deadZone: Float = 0.32f,
    onSecondaryPressedChanged: ((Boolean) -> Unit)? = null,
) {
    var displacement by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    var previousTapTimeMillis by remember { mutableStateOf<Long?>(null) }
    var secondaryPressed by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    fun updateDisplacement(value: Offset) {
        displacement = value
        onDisplacementChanged(if (value.getDistance() >= deadZone) value else Offset.Zero)
    }

    DisposableEffect(Unit) {
        onDispose {
            onDisplacementChanged(Offset.Zero)
            if (secondaryPressed) onSecondaryPressedChanged?.invoke(false)
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(diameter)
                .semantics { contentDescription = label }
                .pointerInput(onDisplacementChanged, onSecondaryPressedChanged, deadZone) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        active = true
                        val startedAt = down.uptimeMillis
                        val lastTap = previousTapTimeMillis
                        if (
                            onSecondaryPressedChanged != null &&
                            lastTap != null &&
                            startedAt - lastTap <= DOUBLE_TAP_MILLIS
                        ) {
                            previousTapTimeMillis = null
                            secondaryPressed = true
                            onSecondaryPressedChanged(true)
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        }

                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = minOf(size.width, size.height) / 2f

                        fun normalized(position: Offset): Offset {
                            val delta = position - center
                            val length = hypot(delta.x, delta.y).coerceAtLeast(1f)
                            val scale = (radius / length).coerceAtMost(1f)
                            return Offset(delta.x * scale / radius, delta.y * scale / radius)
                        }

                        updateDisplacement(normalized(down.position))
                        var change: PointerInputChange
                        do {
                            change = awaitPointerEvent().changes.first { it.id == down.id }
                            if (change.pressed) updateDisplacement(normalized(change.position))
                            change.consume()
                        } while (change.pressed)

                        val duration = change.uptimeMillis - startedAt
                        val travel = (change.position - down.position).getDistance()
                        if (!secondaryPressed && duration <= TAP_MILLIS && travel <= TAP_SLOP_PX) {
                            previousTapTimeMillis = change.uptimeMillis
                        }
                        if (secondaryPressed) {
                            secondaryPressed = false
                            onSecondaryPressedChanged?.invoke(false)
                        }
                        active = false
                        updateDisplacement(Offset.Zero)
                    }
                },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.minDimension / 2f
            drawCircle(FILL_COLOR)
            drawCircle(OUTLINE_COLOR, style = Stroke(width = 2.dp.toPx()))
            val knobCenter = center + Offset(displacement.x * radius, displacement.y * radius)
            drawCircle(
                color = if (active) ACTIVE_COLOR else FILL_COLOR,
                radius = KNOB_RADIUS.toPx(),
                center = knobCenter,
            )
            drawCircle(
                color = OUTLINE_COLOR,
                radius = KNOB_RADIUS.toPx(),
                center = knobCenter,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.35f)),
        )
    }
}

private val FILL_COLOR = Color(30, 30, 30, 150)
private val ACTIVE_COLOR = Color(180, 45, 35, 205)
private val OUTLINE_COLOR = Color.White.copy(alpha = 0.75f)
private val KNOB_RADIUS = 25.dp
private const val TAP_MILLIS = 250L
private const val DOUBLE_TAP_MILLIS = 300L
private const val TAP_SLOP_PX = 48f
