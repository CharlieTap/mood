import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

val libs = the<LibrariesForLibs>()
val conventions = extensions.create<KotlinConventionsExtension>("kotlinConventions")

conventions.jvmBytecodeVersion.convention(
    libs.versions.java.library.bytecode.version.map(String::toInt),
)

plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinBaseExtension>("kotlin") {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.java.compiler.version.get().toInt()))
        }
    }
}

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinBaseExtension>("kotlin") {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.java.compiler.version.get().toInt()))
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
        extraWarnings.set(true)
        freeCompilerArgs.add("-Xwarning-level=REDUNDANT_VISIBILITY_MODIFIER:disabled")
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add(conventions.jvmBytecodeVersion.map { version -> "-Xjdk-release=$version" })
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(conventions.jvmBytecodeVersion.map { version -> JvmTarget.fromTarget(version.toString()) })
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(conventions.jvmBytecodeVersion)
}
