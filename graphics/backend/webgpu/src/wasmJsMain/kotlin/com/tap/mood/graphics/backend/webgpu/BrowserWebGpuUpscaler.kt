@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.tap.mood.graphics.backend.webgpu

import kotlin.js.JsAny

interface BrowserWebGpuUpscaler : WebGpuUpscaler {
    fun install(
        renderer: JsAny,
        shaderMode: Int,
    )
}
