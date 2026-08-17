import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.metro)

    alias(libs.plugins.conventions.kotlin)
    alias(libs.plugins.conventions.linting)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.doomRuntime)
                implementation(projects.doomUi)
                implementation(projects.graphics.backend.classic)
                implementation(projects.graphics.backend.webgpu)
                implementation(projects.graphics.upscaler.fsr1)
                implementation(projects.graphics.upscaler.neural)
                implementation(projects.graphics.effect.crt)
                implementation(projects.graphics.effect.enhanced)
                implementation(compose.runtime)
                implementation(compose.material)
                implementation(compose.ui)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
