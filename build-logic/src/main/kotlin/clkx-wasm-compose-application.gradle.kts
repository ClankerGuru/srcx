import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("clkx-toolchain")
    id("clkx-detekt")
    id("clkx-ktlint")
}

group = "zone.clanker"
version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("docx-viewer")
        compilerOptions {
            freeCompilerArgs.add(
                providers.provider {
                    "-Xklib-relative-path-base=${layout.projectDirectory.asFile.absolutePath}"
                },
            )
        }
        browser {
            commonWebpackConfig {
                outputFileName = "docx-viewer.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

val jvmTarget = kotlin.targets.getByName("jvm") as KotlinJvmTarget
val slopTestCompilation =
    jvmTarget.compilations.create("slopTest") {
        associateWith(jvmTarget.compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME))
        defaultSourceSet {
            kotlin.srcDir("src/slopTest/kotlin")
            dependencies {
                implementation("com.lemonappdev:konsist:0.17.3")
                implementation("io.kotest:kotest-runner-junit5:5.9.1")
                implementation("io.kotest:kotest-assertions-core:5.9.1")
            }
        }
    }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val slopTest =
    tasks.register<Test>("slopTest") {
        description = "Run slop taste tests — architecture, naming, boundaries, style"
        group = "verification"
        testClassesDirs = slopTestCompilation.output.classesDirs
        classpath =
            files(
                slopTestCompilation.output.allOutputs,
                slopTestCompilation.runtimeDependencyFiles,
            )
        useJUnitPlatform()
    }

detekt {
    source.setFrom(
        files(
            "src/commonMain/kotlin",
            "src/jvmMain/kotlin",
            "src/jvmTest/kotlin",
            "src/wasmJsMain/kotlin",
        ),
    )
}

tasks.named("check") {
    dependsOn("detekt", slopTest)
}
