@file:Suppress("FunctionName")

package com.tap.mood.renderer.classic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.graphics.createBitmap
import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.doom.runtime.instance.InstanceController
import com.tap.mood.renderer.AndroidFramePresenter
import com.tap.mood.renderer.AndroidFramePresenterFactory
import com.tap.mood.renderer.AndroidRendererContent
import com.tap.mood.renderer.DisplaySettings
import com.tap.mood.renderer.RendererFrontend
import com.tap.mood.renderer.RendererIds
import com.tap.mood.renderer.Scaling
import com.tap.mood.renderer.doomViewport
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import java.nio.ByteBuffer

@StringKey(RendererIds.CLASSIC)
@ContributesIntoMap(AppScope::class, binding<RendererFrontend>())
@Inject
class AndroidClassicFrontend : RendererFrontend {
    override val displayName: String = "Classic (Canvas)"
    override val autoPriority: Int = 0

    @Composable
    override fun Content(
        controller: InstanceController,
        settings: DisplaySettings,
        onUnavailable: (Throwable?) -> Unit,
        modifier: Modifier,
    ) = AndroidRendererContent(
        controller = controller,
        settings = settings,
        onUnavailable = onUnavailable,
        presenterFactory = presenterFactory,
        modifier = modifier,
    )
}

private val presenterFactory = AndroidFramePresenterFactory { context, _ -> AndroidClassicPresenter(context) }

private class AndroidClassicPresenter(
    context: Context,
) : AndroidFramePresenter {
    private val frameLock = Any()
    private val paint = Paint().apply { isAntiAlias = false }
    private val destination = RectF()
    private var pendingPixels = ByteArray(0)
    private var pendingPixelBuffer = ByteBuffer.wrap(pendingPixels)
    private var frameWidth = 0
    private var frameHeight = 0
    private var bitmap: Bitmap? = null
    private var closed = false

    override val view: View =
        object : View(context) {
            override fun onDraw(canvas: Canvas) = drawFrame(canvas)
        }.apply { setBackgroundColor(Color.BLACK) }

    override var settings: DisplaySettings = DisplaySettings()
        set(value) {
            field = value
            paint.isFilterBitmap = value.scaling == Scaling.Smooth
        }

    override fun submit(frame: Frame) {
        val pixels = frame.pixels
        synchronized(frameLock) {
            if (closed) return
            if (pendingPixels.size != pixels.size) {
                pendingPixels = ByteArray(pixels.size)
                pendingPixelBuffer = ByteBuffer.wrap(pendingPixels)
            }
            pixels.copyInto(pendingPixels)
            frameWidth = frame.width
            frameHeight = frame.height
            if (bitmap?.width != frame.width || bitmap?.height != frame.height) {
                bitmap?.recycle()
                bitmap = createBitmap(frame.width, frame.height)
            }
        }
        view.postInvalidateOnAnimation()
    }

    override fun close() {
        synchronized(frameLock) {
            if (closed) return
            closed = true
            bitmap?.recycle()
            bitmap = null
        }
    }

    private fun drawFrame(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val currentBitmap =
            synchronized(frameLock) {
                if (closed || frameWidth <= 0 || frameHeight <= 0) return
                pendingPixelBuffer.rewind()
                requireNotNull(bitmap).also { target -> target.copyPixelsFromBuffer(pendingPixelBuffer) }
            }
        val viewport =
            doomViewport(
                containerWidth = view.width.toFloat(),
                containerHeight = view.height.toFloat(),
                frameWidth = currentBitmap.width,
                frameHeight = currentBitmap.height,
                aspectRatio = settings.aspectRatio,
            )
        destination.set(viewport.left, viewport.top, viewport.left + viewport.width, viewport.top + viewport.height)
        canvas.drawBitmap(currentBitmap, null, destination, paint)
    }
}
