@file:Suppress("FunctionName")

package com.tap.mood.renderer.webgpu

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tap.mood.doom.runtime.engine.Frame
import com.tap.mood.doom.runtime.instance.InstanceController
import com.tap.mood.renderer.AndroidFramePresenter
import com.tap.mood.renderer.AndroidFramePresenterFactory
import com.tap.mood.renderer.AndroidRendererContent
import com.tap.mood.renderer.DisplaySettings
import com.tap.mood.renderer.RendererFrontend
import com.tap.mood.renderer.RendererIds
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@StringKey(RendererIds.WEB_GPU)
@ContributesIntoMap(AppScope::class, binding<RendererFrontend>())
@Inject
class AndroidWebGpuFrontend : RendererFrontend {
    override val displayName: String = "WebGPU (Vulkan)"
    override val autoPriority: Int = 100

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

private val presenterFactory = AndroidFramePresenterFactory(::AndroidWebGpuPresenter)

private class AndroidWebGpuPresenter(
    context: Context,
    private val onUnavailable: (Throwable?) -> Unit,
) : AndroidFramePresenter,
    SurfaceHolder.Callback {
    private val sessionLock = Any()
    private val surfaceView = SurfaceView(context)
    private val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: AndroidWebGpuSession? = null

    @Volatile private var surfaceWidth = 0

    @Volatile private var surfaceHeight = 0

    @Volatile private var surfaceGeneration = 0

    @Volatile private var closed = false

    @Volatile private var initializing = false
    private var unavailableReported = false

    override val view: View = surfaceView
    override var settings: DisplaySettings = DisplaySettings()

    init {
        if (Build.VERSION.SDK_INT >= 34) {
            surfaceView.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
        }
        surfaceView.holder.addCallback(this)
    }

    override fun submit(frame: Frame) {
        synchronized(sessionLock) {
            val activeSession = session ?: return
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            if (
                activeSession.renderer.render(
                    frame = frame,
                    targetWidth = surfaceWidth,
                    targetHeight = surfaceHeight,
                    settings = settings,
                )
            ) {
                return
            }
            activeSession.close()
            session = null
        }
        reportUnavailable(IllegalStateException("Android WebGPU stopped accepting frames"))
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width
        surfaceHeight = height
        if (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
            reportUnavailable(UnsupportedOperationException("Dawn is unsafe on Android emulator Vulkan"))
            return
        }
        val expectedSurfaceGeneration = surfaceGeneration
        synchronized(sessionLock) {
            session?.let { activeSession ->
                if (activeSession.driver.attachSurface(holder.surface, width, height)) return
                activeSession.close()
                session = null
            }
        }
        if (initializing || closed) return
        initializing = true
        initializationScope.launch {
            val result =
                runCatching {
                    val driver = AndroidWebGpuDriver.create(holder.surface, width, height)
                    AndroidWebGpuSession(driver, WebGpuRenderer(driver))
                }
            surfaceView.post {
                result
                    .onSuccess { newSession ->
                        if (
                            closed ||
                            expectedSurfaceGeneration != surfaceGeneration ||
                            !holder.surface.isValid
                        ) {
                            newSession.close()
                        } else {
                            synchronized(sessionLock) {
                                newSession.driver.configureTarget(surfaceWidth, surfaceHeight)
                                session = newSession
                            }
                            Log.i(
                                TAG,
                                "Renderer: Android WebGPU via ${newSession.renderer.backendLabel} " +
                                    "(R8Uint indexed framebuffer)",
                            )
                        }
                    }.onFailure(::reportUnavailable)
                if (expectedSurfaceGeneration == surfaceGeneration) initializing = false
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceGeneration++
        surfaceWidth = 0
        surfaceHeight = 0
        synchronized(sessionLock) { session?.driver?.detachSurface() }
        initializing = false
    }

    override fun close() {
        if (closed) return
        closed = true
        surfaceView.holder.removeCallback(this)
        initializationScope.cancel()
        synchronized(sessionLock) {
            session?.close()
            session = null
        }
    }

    private fun reportUnavailable(error: Throwable?) {
        if (unavailableReported || closed) return
        unavailableReported = true
        Log.w(TAG, "Android WebGPU frontend unavailable", error)
        surfaceView.post { if (!closed) onUnavailable(error) }
    }

    private companion object {
        const val TAG = "MoodWebGPU"
    }
}

private class AndroidWebGpuSession(
    val driver: AndroidWebGpuDriver,
    val renderer: WebGpuRenderer,
) : AutoCloseable {
    override fun close() = renderer.close()
}
