plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.chasm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.metro) apply false

    alias(libs.plugins.conventions.kotlin) apply false
    alias(libs.plugins.conventions.linting) apply false
}

tasks.register("fmt") {
    group = "formatting"
    description = "Format Kotlin sources in every module."
    dependsOn(
        ":android:fmt",
        ":doom-runtime:fmt",
        ":doom-ui:fmt",
        ":game-controls:fmt",
        ":renderer:fmt",
        ":renderer:classic:fmt",
        ":renderer:webgpu:fmt",
        ":web:fmt",
    )
}

tasks.register("test") {
    group = "verification"
    description = "Run tests in every module that contains them."
    dependsOn(
        ":doom-runtime:jvmTest",
        ":doom-ui:testAndroidHostTest",
        ":game-controls:testAndroidHostTest",
        ":renderer:testAndroidHostTest",
        ":renderer:webgpu:testAndroidHostTest",
    )
}
