@file:Suppress("FunctionName")

package com.tap.mood

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeViewport
import com.tap.mood.doom.ui.DoomScreen
import dev.zacsweers.metro.createGraph
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val application = createGraph<WebAppGraph>().application
    ComposeViewport("webApp") {
        DisposableEffect(application) {
            onDispose(application::close)
        }
        MoodTheme {
            DoomScreen(
                controller = application.controller,
                settingsStore = application.settingsStore,
                active = rememberPageActive(),
                configuration = application.uiConfiguration,
            )
        }
    }
}

@Composable
private fun rememberPageActive(): Boolean {
    var focused by remember { mutableStateOf(document.hasFocus()) }

    DisposableEffect(Unit) {
        val visibilityListener: (Event) -> Unit = { focused = document.hasFocus() }
        val focusListener: (Event) -> Unit = { focused = true }
        val blurListener: (Event) -> Unit = { focused = false }
        document.addEventListener("visibilitychange", visibilityListener)
        window.addEventListener("focus", focusListener)
        window.addEventListener("blur", blurListener)
        onDispose {
            document.removeEventListener("visibilitychange", visibilityListener)
            window.removeEventListener("focus", focusListener)
            window.removeEventListener("blur", blurListener)
        }
    }

    return focused
}

@Composable
private fun MoodTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors =
            darkColors(
                primary = Color(0xffb42d23),
                secondary = Color(0xffd17b43),
            ),
        content = content,
    )
}
