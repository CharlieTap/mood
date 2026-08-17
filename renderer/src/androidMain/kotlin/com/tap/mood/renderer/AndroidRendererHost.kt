@file:Suppress("FunctionName")

package com.tap.mood.renderer

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.doom.runtime.input.InputMode
import com.tap.mood.doom.runtime.input.Key
import com.tap.mood.doom.runtime.instance.InstanceController
import com.tap.mood.doom.runtime.instance.InstanceState
import java.util.concurrent.atomic.AtomicReference

interface AndroidFramePresenter : AutoCloseable {
    val view: View
    var settings: DisplaySettings

    fun submit(frame: Frame)
}

fun interface AndroidFramePresenterFactory {
    fun create(
        context: Context,
        onUnavailable: (Throwable?) -> Unit,
    ): AndroidFramePresenter
}

@Composable
fun AndroidRendererContent(
    controller: InstanceController,
    settings: DisplaySettings,
    onUnavailable: (Throwable?) -> Unit,
    presenterFactory: AndroidFramePresenterFactory,
    modifier: Modifier = Modifier,
) {
    val rendererHost = remember { AtomicReference<AndroidRendererHost?>() }

    DisposableEffect(controller, rendererHost) {
        val frameSink: (Frame) -> Unit = { frame -> rendererHost.get()?.submit(frame) }
        controller.setFrameSink(frameSink)
        onDispose {
            controller.clearFrameSink(frameSink)
            rendererHost.set(null)
        }
    }

    AndroidView(
        factory = { context ->
            AndroidRendererHost(
                context = context,
                presenter = presenterFactory.create(context, onUnavailable),
            ).apply {
                onKeyChanged = controller::setKeyPressed
                onAllKeysReleased = controller::releaseAllKeys
                rendererHost.set(this)
            }
        },
        update = { view ->
            val state = controller.state.value
            view.inputMode = state.inputMode
            view.settings = settings
            if (state.status == InstanceState.Status.Running) view.requestFocus()
        },
        onRelease = { view ->
            controller.releaseAllKeys()
            rendererHost.compareAndSet(view, null)
            view.close()
        },
        modifier = modifier,
    )
}

private class AndroidRendererHost(
    context: Context,
    private val presenter: AndroidFramePresenter,
) : FrameLayout(context),
    AutoCloseable {
    private val activeKeyMappings = mutableMapOf<Int, List<Key>>()
    private var closed = false

    var onKeyChanged: (Key, Boolean) -> Unit = { _, _ -> }
    var onAllKeysReleased: () -> Unit = {}
    var settings: DisplaySettings
        get() = presenter.settings
        set(value) {
            presenter.settings = value
        }
    var inputMode: InputMode = InputMode.Gameplay
        set(value) {
            if (field == value) return
            releaseHardwareKeys()
            field = value
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        addView(
            presenter.view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun submit(frame: Frame) = presenter.submit(frame)

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (activeKeyMappings.containsKey(keyCode)) return true
        val doomKeys = event.toDoomKeys() ?: return super.onKeyDown(keyCode, event)
        activeKeyMappings[keyCode] = doomKeys
        doomKeys.forEach { key -> onKeyChanged(key, true) }
        return true
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val doomKeys = activeKeyMappings.remove(keyCode) ?: return super.onKeyUp(keyCode, event)
        doomKeys.asReversed().forEach { key -> onKeyChanged(key, false) }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestFocus()
            performClick()
        }
        return true
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            releaseHardwareKeys()
            onAllKeysReleased()
        }
    }

    override fun onDetachedFromWindow() {
        releaseHardwareKeys()
        onAllKeysReleased()
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseHardwareKeys()
        presenter.close()
    }

    private fun KeyEvent.toDoomKeys(): List<Key>? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                listOf(Key.LEFT)
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                listOf(Key.RIGHT)
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                listOf(Key.UP)
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                listOf(Key.DOWN)
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                inputMode.primaryKeys()
            }

            KeyEvent.KEYCODE_BUTTON_B -> {
                inputMode.backKeys()
            }

            KeyEvent.KEYCODE_BUTTON_X -> {
                if (inputMode == InputMode.Gameplay) listOf(Key.USE) else inputMode.primaryKeys()
            }

            KeyEvent.KEYCODE_BUTTON_START -> {
                listOf(Key.ESCAPE)
            }

            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> {
                listOf(Key.FIRE)
            }

            KeyEvent.KEYCODE_COMMA -> {
                listOf(Key.STRAFE_LEFT)
            }

            KeyEvent.KEYCODE_PERIOD -> {
                listOf(Key.STRAFE_RIGHT)
            }

            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                listOf(Key.FIRE)
            }

            KeyEvent.KEYCODE_SPACE -> {
                listOf(Key.USE)
            }

            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                listOf(Key.SHIFT)
            }

            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                listOf(Key.TAB)
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                listOf(Key.ESCAPE)
            }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                listOf(Key.ENTER)
            }

            KeyEvent.KEYCODE_DEL -> {
                listOf(Key.BACKSPACE)
            }

            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                listOf(Key.ALT)
            }

            else -> {
                unicodeChar.toPrintableDoomKey()
            }
        }

    private fun releaseHardwareKeys() {
        activeKeyMappings.values
            .flatten()
            .asReversed()
            .forEach { key -> onKeyChanged(key, false) }
        activeKeyMappings.clear()
    }
}

private fun Int.toPrintableDoomKey(): List<Key>? =
    takeIf { character -> character in PRINTABLE_ASCII }
        ?.toChar()
        ?.let(Key::character)
        ?.let(::listOf)

private val PRINTABLE_ASCII = 32..126
