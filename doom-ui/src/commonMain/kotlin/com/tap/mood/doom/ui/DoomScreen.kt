@file:Suppress("FunctionName")

package com.tap.mood.doom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tap.mood.doom.runtime.input.InputMode
import com.tap.mood.doom.runtime.input.Key
import com.tap.mood.doom.runtime.instance.InstanceController
import com.tap.mood.doom.runtime.instance.InstanceState
import com.tap.mood.doom.ui.controls.DoomControls
import com.tap.mood.doom.ui.performance.PerformanceOverlay
import com.tap.mood.doom.ui.rendering.Surface
import com.tap.mood.doom.ui.resources.Res
import com.tap.mood.doom.ui.resources.doom_error
import com.tap.mood.doom.ui.resources.doom_idle
import com.tap.mood.doom.ui.resources.doom_loading
import com.tap.mood.doom.ui.resources.doom_paused
import com.tap.mood.doom.ui.resources.doom_save_name
import com.tap.mood.doom.ui.resources.keyboard_confirmation_controls
import com.tap.mood.doom.ui.resources.keyboard_gameplay_controls
import com.tap.mood.doom.ui.resources.keyboard_menu_controls
import com.tap.mood.doom.ui.resources.settings
import com.tap.mood.doom.ui.settings.DisplaySettings
import com.tap.mood.doom.ui.settings.SettingsStore
import com.tap.mood.doom.ui.settings.SettingsWindow
import org.jetbrains.compose.resources.stringResource

@Composable
fun DoomScreen(
    controller: InstanceController,
    settingsStore: SettingsStore,
    active: Boolean,
    configuration: Configuration,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val settings by settingsStore.settings.collectAsState()
    var settingsVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(active, settingsVisible) {
        controller.setActive(active && !settingsVisible)
    }
    LaunchedEffect(settings.engine) {
        controller.configure(settings.engine)
    }
    DisposableEffect(controller) {
        onDispose {
            controller.releaseAllKeys()
            controller.setActive(false)
        }
    }

    Box(modifier = modifier.background(Color.Black).fillMaxSize()) {
        if (configuration.showKeyboardControls) {
            Column(modifier = Modifier.fillMaxSize()) {
                DoomGameContent(
                    state = state,
                    displaySettings = settings.display,
                    settingsVisible = settingsVisible,
                    configuration = configuration,
                    controller = controller,
                    onShowSettings = { settingsVisible = true },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                if (state.showsActiveContent(settingsVisible)) {
                    KeyboardControlsBar(
                        state = state,
                        showPerformanceStatus = configuration.showPerformanceOverlay,
                        onShowSettings = {
                            controller.releaseAllKeys()
                            settingsVisible = true
                        },
                    )
                }
            }
        } else {
            DoomGameContent(
                state = state,
                displaySettings = settings.display,
                settingsVisible = settingsVisible,
                configuration = configuration,
                controller = controller,
                onShowSettings = { settingsVisible = true },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (settingsVisible) {
            SettingsWindow(
                settings = settings,
                onSettingsChanged = settingsStore::update,
                onDismiss = { settingsVisible = false },
            )
        }
    }
}

@Composable
private fun DoomGameContent(
    state: InstanceState,
    displaySettings: DisplaySettings,
    settingsVisible: Boolean,
    configuration: Configuration,
    controller: InstanceController,
    onShowSettings: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier) {
        if (state.showsActiveContent(settingsVisible)) {
            Surface(controller, displaySettings, Modifier.fillMaxSize())
        } else if (state.status != InstanceState.Status.Running) {
            DoomStatus(state)
        }

        if (
            configuration.showVirtualControls &&
            state.status == InstanceState.Status.Running &&
            !settingsVisible
        ) {
            DoomControls(state.inputMode, controller)
        }

        if (state.inputMode == InputMode.TextEntry && !settingsVisible) {
            SaveNameInput(controller)
        }

        if (
            configuration.showPerformanceOverlay &&
            !configuration.showKeyboardControls &&
            state.inputMode == InputMode.Gameplay
        ) {
            state.framesPerSecond?.let { framesPerSecond ->
                PerformanceOverlay(
                    framesPerSecond = framesPerSecond,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(
                                top = 8.dp,
                                end = if (configuration.showVirtualControls) 94.dp else 8.dp,
                            ),
                )
            }
        }

        if (!configuration.showKeyboardControls && state.showsActiveContent(settingsVisible)) {
            SettingsButton(
                onClick = {
                    controller.releaseAllKeys()
                    onShowSettings()
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun KeyboardControlsBar(
    state: InstanceState,
    showPerformanceStatus: Boolean,
    onShowSettings: () -> Unit,
) {
    val text =
        when (state.inputMode) {
            InputMode.Gameplay -> stringResource(Res.string.keyboard_gameplay_controls)
            InputMode.Menu -> stringResource(Res.string.keyboard_menu_controls)
            InputMode.Confirmation -> stringResource(Res.string.keyboard_confirmation_controls)
            InputMode.TextEntry -> return
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color(0xff151515))
                .padding(start = 12.dp, end = 4.dp),
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        if (showPerformanceStatus && state.inputMode == InputMode.Gameplay) {
            state.framesPerSecond?.let { framesPerSecond -> PerformanceOverlay(framesPerSecond) }
        }
        SettingsButton(onShowSettings)
    }
}

@Composable
private fun SettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(
            text = stringResource(Res.string.settings),
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun BoxScope.DoomStatus(state: InstanceState) {
    Text(
        text = state.statusText(),
        color = if (state.status == InstanceState.Status.Error) Color.Red else Color.White,
        modifier =
            Modifier
                .align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(16.dp),
    )
}

@Composable
private fun InstanceState.statusText(): String =
    when (status) {
        InstanceState.Status.Idle -> stringResource(Res.string.doom_idle)
        InstanceState.Status.Loading -> stringResource(Res.string.doom_loading)
        InstanceState.Status.Paused -> stringResource(Res.string.doom_paused)
        InstanceState.Status.Error -> errorMessage ?: stringResource(Res.string.doom_error)
        InstanceState.Status.Running -> ""
    }

private fun InstanceState.showsActiveContent(settingsVisible: Boolean): Boolean =
    status == InstanceState.Status.Running && inputMode != InputMode.TextEntry && !settingsVisible

@Composable
private fun BoxScope.SaveNameInput(controller: InstanceController) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var value by rememberSaveable { mutableStateOf("") }
    var clearedExistingName by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    OutlinedTextField(
        value = value,
        onValueChange = { proposedValue ->
            val nextValue =
                proposedValue
                    .filter { character -> character.code in PRINTABLE_ASCII }
                    .take(MAX_SAVE_NAME_LENGTH)
            if (nextValue == value) return@OutlinedTextField

            if (!clearedExistingName && nextValue.isNotEmpty()) {
                repeat(MAX_SAVE_NAME_LENGTH) { controller.tapKey(Key.BACKSPACE) }
                clearedExistingName = true
            }

            val sharedPrefixLength = value.commonPrefixWith(nextValue).length
            repeat(value.length - sharedPrefixLength) {
                controller.tapKey(Key.BACKSPACE)
            }
            nextValue.drop(sharedPrefixLength).forEach { character ->
                controller.tapKey(Key.character(character))
            }
            value = nextValue
        },
        label = { Text(stringResource(Res.string.doom_save_name)) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
        keyboardActions =
            KeyboardActions(
                onDone = {
                    keyboardController?.hide()
                    controller.tapKey(Key.ENTER)
                },
            ),
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 116.dp)
                .focusRequester(focusRequester),
    )
}

private const val MAX_SAVE_NAME_LENGTH = 23
private val PRINTABLE_ASCII = 32..126
