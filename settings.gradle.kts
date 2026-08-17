pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    includeBuild("gradle/plugins/kotlin-conventions")
    includeBuild("gradle/plugins/linting-conventions")
}

dependencyResolutionManagement {
    // Kotlin/Wasm registers Ivy repositories for its Node/Yarn toolchains.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mood"

include(":android")
include(":doom-runtime")
include(":doom-ui")
include(":game-controls")
include(":graphics:core")
include(":graphics:backend:classic")
include(":graphics:backend:webgpu")
include(":graphics:upscaler:fsr1")
include(":graphics:upscaler:neural")
include(":graphics:effect:crt")
include(":graphics:effect:enhanced")
include(":web")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
enableFeaturePreview("ENHANCED_GRAPH_ORDERING")
