@file:Suppress("FunctionName")

package com.tap.mood.doom.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tap.mood.doom.runtime.input.InputMode
import com.tap.mood.doom.runtime.input.Key
import com.tap.mood.doom.runtime.instance.InstanceController
import com.tap.mood.doom.ui.resources.Res
import com.tap.mood.doom.ui.resources.control_back
import com.tap.mood.doom.ui.resources.control_cancel
import com.tap.mood.doom.ui.resources.control_close
import com.tap.mood.doom.ui.resources.control_confirm
import com.tap.mood.doom.ui.resources.control_map
import com.tap.mood.doom.ui.resources.control_menu
import com.tap.mood.doom.ui.resources.control_move
import com.tap.mood.doom.ui.resources.control_navigate
import com.tap.mood.doom.ui.resources.control_ok
import com.tap.mood.doom.ui.resources.control_save
import com.tap.mood.doom.ui.resources.control_turn
import com.tap.mood.doom.ui.resources.control_use
import com.tap.mood.game.controls.ActionBinding
import com.tap.mood.game.controls.Button
import com.tap.mood.game.controls.HeldActions
import com.tap.mood.game.controls.Joystick
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

@Composable
internal fun DoomControls(
    inputMode: InputMode,
    controller: InstanceController,
) {
    val heldActions = remember(controller) { HeldActions(controller::setKeyPressed) }
    val movement = remember(heldActions) { ActionBinding(heldActions) }
    val turning = remember(heldActions) { ActionBinding(heldActions) }
    val menu = remember(heldActions) { ActionBinding(heldActions) }

    DisposableEffect(inputMode, controller) {
        onDispose {
            movement.release()
            turning.release()
            menu.release()
            heldActions.releaseAll()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (inputMode) {
            InputMode.Gameplay -> {
                DoomButton(
                    label = stringResource(Res.string.control_menu),
                    key = Key.ESCAPE,
                    heldActions = heldActions,
                    controller = controller,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    diameter = 62.dp,
                )
                DoomButton(
                    label = stringResource(Res.string.control_map),
                    key = Key.TAB,
                    heldActions = heldActions,
                    controller = controller,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    diameter = 62.dp,
                )
                Joystick(
                    label = stringResource(Res.string.control_move),
                    onDisplacementChanged = { offset -> movement.update(offset.movementKeys()) },
                    modifier = Modifier.align(Alignment.BottomStart).padding(42.dp),
                )
                Joystick(
                    label = stringResource(Res.string.control_turn),
                    onDisplacementChanged = { offset -> turning.update(offset.turningKeys()) },
                    onSecondaryPressedChanged = { pressed ->
                        if (pressed) heldActions.acquire(Key.FIRE) else heldActions.release(Key.FIRE)
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(42.dp),
                )
                DoomButton(
                    label = stringResource(Res.string.control_use),
                    key = Key.USE,
                    heldActions = heldActions,
                    controller = controller,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 78.dp, bottom = 174.dp),
                    diameter = 70.dp,
                )
            }

            InputMode.Menu -> {
                DoomButton(
                    label = stringResource(Res.string.control_close),
                    key = Key.ESCAPE,
                    heldActions = heldActions,
                    controller = controller,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    diameter = 62.dp,
                )
                Joystick(
                    label = stringResource(Res.string.control_navigate),
                    onDisplacementChanged = { offset -> menu.update(offset.menuKeys()) },
                    modifier = Modifier.align(Alignment.BottomStart).padding(42.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp),
                ) {
                    DoomButton(stringResource(Res.string.control_back), Key.BACKSPACE, heldActions, controller)
                    DoomButton(
                        stringResource(Res.string.control_ok),
                        Key.ENTER,
                        heldActions,
                        controller,
                        diameter = 82.dp,
                    )
                }
            }

            InputMode.Confirmation -> {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(28.dp),
                ) {
                    DoomButton(
                        stringResource(Res.string.control_cancel),
                        Key.character('n'),
                        heldActions,
                        controller,
                        diameter = 86.dp,
                    )
                    DoomButton(
                        stringResource(Res.string.control_confirm),
                        Key.character('y'),
                        heldActions,
                        controller,
                        diameter = 96.dp,
                    )
                }
            }

            InputMode.TextEntry -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                ) {
                    DoomButton(stringResource(Res.string.control_cancel), Key.ESCAPE, heldActions, controller)
                    DoomButton(
                        stringResource(Res.string.control_save),
                        Key.ENTER,
                        heldActions,
                        controller,
                        diameter = 88.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DoomButton(
    label: String,
    key: Key,
    heldActions: HeldActions<Key>,
    controller: InstanceController,
    modifier: Modifier = Modifier,
    diameter: Dp = 76.dp,
) {
    Button(
        label = label,
        onPressedChanged = { pressed ->
            if (pressed) heldActions.acquire(key) else heldActions.release(key)
        },
        onClick = { controller.tapKey(key) },
        modifier = modifier,
        diameter = diameter,
    )
}

private fun Offset.movementKeys(): Set<Key> =
    buildSet {
        if (y < 0f) add(Key.UP)
        if (y > 0f) add(Key.DOWN)
        if (x < 0f) add(Key.STRAFE_LEFT)
        if (x > 0f) add(Key.STRAFE_RIGHT)
    }

private fun Offset.turningKeys(): Set<Key> =
    when {
        x < 0f -> setOf(Key.LEFT)
        x > 0f -> setOf(Key.RIGHT)
        else -> emptySet()
    }

private fun Offset.menuKeys(): Set<Key> =
    when {
        x == 0f && y == 0f -> emptySet()
        abs(x) > abs(y) && x < 0f -> setOf(Key.LEFT)
        abs(x) > abs(y) -> setOf(Key.RIGHT)
        y < 0f -> setOf(Key.UP)
        else -> setOf(Key.DOWN)
    }
