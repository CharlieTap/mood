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
                implementation(projects.renderer.classic)
                implementation(projects.renderer.webgpu)
                implementation(compose.runtime)
                implementation(compose.material)
                implementation(compose.ui)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
