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
include(":renderer")
include(":renderer:classic")
include(":renderer:webgpu")
include(":web")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
enableFeaturePreview("ENHANCED_GRAPH_ORDERING")
