import io.github.charlietap.chasm.gradle.CodegenConfig
import io.github.charlietap.chasm.gradle.CodegenTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jmailen.gradle.kotlinter.tasks.ConfigurableKtLintTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.chasm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.metro)

    alias(libs.plugins.conventions.kotlin)
    alias(libs.plugins.conventions.linting)
}

chasm {
    modules {
        create("DoomWasmModule") {
            binary = layout.projectDirectory.file("src/commonMain/composeResources/files/doom/doom.wasm")
            packageName = "com.tap.mood.doom.runtime.generated"
            codegenConfig =
                CodegenConfig(
                    generateTypesafeMemoryProperties = true,
                    generateSuspendingFactories = true,
                )
        }
    }
}

kotlin {
    jvm()
    js {
        browser()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.androidx.compose.runtime)
                implementation(libs.compose.resources)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "com.tap.mood.doom.runtime.resources"
}

tasks.withType<ConfigurableKtLintTask>().configureEach {
    dependsOn(tasks.withType<CodegenTask>())
}
