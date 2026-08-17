import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)

    alias(libs.plugins.conventions.linting)
}

android {
    namespace = libs.versions.application.namespace.get()
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = libs.versions.application.id.get()
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 1
        versionName = libs.versions.version.name.get()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.library.bytecode.version.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.library.bytecode.version.get())
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.library.bytecode.version.get()))
    }
}

dependencies {
    implementation(projects.doomRuntime)
    implementation(projects.doomUi)
    implementation(projects.renderer.classic)
    implementation(projects.renderer.webgpu)

    implementation(libs.bundles.androidx)
    implementation(libs.bundles.compose.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.metrox.android)
    implementation(libs.metrox.viewmodel)

    debugImplementation(libs.androidx.compose.tooling)
}
