@file:Suppress("FunctionName")

package com.tap.mood.game.controls

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Button(
    label: String,
    onPressedChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 72.dp,
    content: @Composable () -> Unit = {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    },
) {
    var pressed by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    DisposableEffect(Unit) {
        onDispose {
            if (pressed) onPressedChanged(false)
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(diameter)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                }.pointerInput(onPressedChanged) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        pressed = true
                        onPressedChanged(true)
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        var change: PointerInputChange
                        do {
                            change = awaitPointerEvent().changes.first { it.id == down.id }
                            change.consume()
                        } while (change.pressed)
                        pressed = false
                        onPressedChanged(false)
                    }
                },
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(if (pressed) PRESSED_COLOR else FILL_COLOR)
            drawCircle(OUTLINE_COLOR, style = Stroke(width = 2.dp.toPx()))
        }
        content()
    }
}

private val FILL_COLOR = Color(30, 30, 30, 150)
private val PRESSED_COLOR = Color(180, 45, 35, 205)
private val OUTLINE_COLOR = Color.White.copy(alpha = 0.75f)
