@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("FunctionName")

package com.tap.mood.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.doom.runtime.input.InputMode
import com.tap.mood.doom.runtime.input.Key
import com.tap.mood.doom.runtime.instance.InstanceController
import com.tap.mood.doom.runtime.instance.InstanceState
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

interface BrowserFramePresenter : AutoCloseable {
    val element: HTMLElement
    var settings: DisplaySettings

    fun submit(frame: Frame)
}

fun interface BrowserFramePresenterFactory {
    fun create(onUnavailable: (Throwable?) -> Unit): BrowserFramePresenter
}

@Composable
fun BrowserRendererContent(
    controller: InstanceController,
    settings: DisplaySettings,
    onUnavailable: (Throwable?) -> Unit,
    presenterFactory: BrowserFramePresenterFactory,
    modifier: Modifier = Modifier,
) {
    val presenter = remember(presenterFactory) { presenterFactory.create(onUnavailable) }
    val activeKeyMappings = remember { mutableMapOf<String, List<Key>>() }

    DisposableEffect(controller, presenter) {
        val sink: (Frame) -> Unit = presenter::submit
        val keyDownListener: (Event) -> Unit = { event ->
            val keyboardEvent = event as KeyboardEvent
            val state = controller.state.value
            val mapped =
                if (
                    state.status == InstanceState.Status.Running &&
                    (state.inputMode != InputMode.TextEntry || keyboardEvent.key == "Escape")
                ) {
                    keyboardEvent.toDoomKeys()
                } else {
                    null
                }
            if (mapped != null) {
                keyboardEvent.preventDefault()
                if (activeKeyMappings.put(keyboardEvent.code, mapped) == null) {
                    mapped.forEach { key -> controller.setKeyPressed(key, true) }
                }
            }
        }
        val keyUpListener: (Event) -> Unit = { event ->
            val keyboardEvent = event as KeyboardEvent
            activeKeyMappings.remove(keyboardEvent.code)?.let { mapped ->
                keyboardEvent.preventDefault()
                mapped.asReversed().forEach { key -> controller.setKeyPressed(key, false) }
            }
        }
        val blurListener: (Event) -> Unit = {
            activeKeyMappings.clear()
            controller.releaseAllKeys()
        }

        controller.setFrameSink(sink)
        window.addEventListener("keydown", keyDownListener)
        window.addEventListener("keyup", keyUpListener)
        window.addEventListener("blur", blurListener)
        onDispose {
            controller.clearFrameSink(sink)
            window.removeEventListener("keydown", keyDownListener)
            window.removeEventListener("keyup", keyUpListener)
            window.removeEventListener("blur", blurListener)
            activeKeyMappings.clear()
            controller.releaseAllKeys()
        }
    }

    HtmlElementView(
        factory = { presenter.element },
        update = { presenter.settings = settings },
        onRelease = { presenter.close() },
        modifier = modifier,
    )
}

private fun KeyboardEvent.toDoomKeys(): List<Key>? =
    when (key) {
        "ArrowLeft" -> {
            listOf(Key.LEFT)
        }

        "ArrowRight" -> {
            listOf(Key.RIGHT)
        }

        "ArrowUp" -> {
            listOf(Key.UP)
        }

        "ArrowDown" -> {
            listOf(Key.DOWN)
        }

        "," -> {
            listOf(Key.STRAFE_LEFT)
        }

        "." -> {
            listOf(Key.STRAFE_RIGHT)
        }

        "Control" -> {
            listOf(Key.FIRE)
        }

        " " -> {
            listOf(Key.USE)
        }

        "Shift" -> {
            listOf(Key.SHIFT)
        }

        "Tab" -> {
            listOf(Key.TAB)
        }

        "Escape" -> {
            listOf(Key.ESCAPE)
        }

        "Enter" -> {
            listOf(Key.ENTER)
        }

        "Backspace" -> {
            listOf(Key.BACKSPACE)
        }

        "Alt" -> {
            listOf(Key.ALT)
        }

        else -> {
            key.toPrintableDoomKey()
        }
    }

private fun String.toPrintableDoomKey(): List<Key>? =
    singleOrNull()
        ?.takeIf { character -> character.code in PRINTABLE_ASCII }
        ?.lowercaseChar()
        ?.let(Key::character)
        ?.let(::listOf)

private val PRINTABLE_ASCII = 32..126
