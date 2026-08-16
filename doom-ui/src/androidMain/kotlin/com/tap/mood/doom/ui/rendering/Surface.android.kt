@file:Suppress("FunctionName")

package com.tap.mood.doom.ui.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.doom.runtime.input.InputMode
import com.tap.mood.doom.runtime.input.Key
import com.tap.mood.doom.runtime.instance.InstanceController
import com.tap.mood.doom.runtime.instance.InstanceState
import com.tap.mood.doom.ui.controls.backKeys
import com.tap.mood.doom.ui.controls.primaryKeys
import com.tap.mood.doom.ui.settings.AspectRatio
import com.tap.mood.doom.ui.settings.DisplaySettings
import com.tap.mood.doom.ui.settings.Scaling
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

@Composable
internal actual fun Surface(
    controller: InstanceController,
    settings: DisplaySettings,
    modifier: Modifier,
) {
    val frameTarget = remember { AtomicReference<AndroidDoomSurface?>() }

    DisposableEffect(controller, frameTarget) {
        val sink: (Frame) -> Unit = { frame -> frameTarget.get()?.submitFrame(frame) }
        controller.setFrameSink(sink)
        onDispose {
            controller.clearFrameSink(sink)
            frameTarget.set(null)
        }
    }

    AndroidView(
        factory = { context ->
            AndroidDoomSurface(context).apply {
                onKeyChanged = controller::setKeyPressed
                onAllKeysReleased = controller::releaseAllKeys
                frameTarget.set(this)
            }
        },
        update = { view ->
            view.inputMode = controller.state.value.inputMode
            view.smoothScaling = settings.scaling == Scaling.Smooth
            view.aspectRatio = settings.aspectRatio
            if (controller.state.value.status == InstanceState.Status.Running) {
                view.requestFocus()
            }
        },
        onRelease = { view ->
            controller.releaseAllKeys()
            frameTarget.compareAndSet(view, null)
            view.onKeyChanged = { _, _ -> }
            view.onAllKeysReleased = {}
        },
        modifier = modifier,
    )
}

private class AndroidDoomSurface(
    context: Context,
) : View(context) {
    private val frameLock = Any()
    private val paint = Paint().apply { isAntiAlias = false }
    private val destination = RectF()
    private var pendingPixels = ByteArray(0)
    private var pendingPixelBuffer = ByteBuffer.wrap(pendingPixels)
    private var frameWidth = 0
    private var frameHeight = 0
    private var bitmap: Bitmap? = null
    private var hasFrame = false
    private val activeKeyMappings = mutableMapOf<Int, List<Key>>()

    var onKeyChanged: (Key, Boolean) -> Unit = { _, _ -> }
    var onAllKeysReleased: () -> Unit = {}
    var smoothScaling: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            paint.isFilterBitmap = value
            invalidate()
        }
    var aspectRatio: AspectRatio = AspectRatio.Corrected
        set(value) {
            if (field == value) return
            field = value
            invalidate()
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
        setBackgroundColor(Color.BLACK)
    }

    fun submitFrame(frame: Frame) {
        synchronized(frameLock) {
            if (pendingPixels.size != frame.pixels.size) {
                pendingPixels = ByteArray(frame.pixels.size)
                pendingPixelBuffer = ByteBuffer.wrap(pendingPixels)
            }
            frame.pixels.copyInto(pendingPixels)
            frameWidth = frame.width
            frameHeight = frame.height
            if (bitmap?.width != frame.width || bitmap?.height != frame.height) {
                bitmap = createBitmap(frame.width, frame.height)
            }
            hasFrame = true
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val currentBitmap =
            synchronized(frameLock) {
                if (!hasFrame || frameWidth <= 0 || frameHeight <= 0) return
                pendingPixelBuffer.rewind()
                requireNotNull(bitmap).also { target -> target.copyPixelsFromBuffer(pendingPixelBuffer) }
            }
        val viewport =
            doomViewport(
                containerWidth = width.toFloat(),
                containerHeight = height.toFloat(),
                frameWidth = currentBitmap.width,
                frameHeight = currentBitmap.height,
                aspectRatio = aspectRatio,
            )
        destination.set(viewport.left, viewport.top, viewport.left + viewport.width, viewport.top + viewport.height)
        canvas.drawBitmap(currentBitmap, null, destination, paint)
    }

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
                unicodeChar
                    .takeIf { character -> character in PRINTABLE_ASCII }
                    ?.toChar()
                    ?.let(Key::character)
                    ?.let(::listOf)
            }
        }

    private fun releaseHardwareKeys() {
        activeKeyMappings.values
            .flatten()
            .asReversed()
            .forEach { key -> onKeyChanged(key, false) }
        activeKeyMappings.clear()
    }

    private companion object {
        val PRINTABLE_ASCII = 32..126
    }
}
