plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("clkx-toolchain")
    id("clkx-detekt")
    id("clkx-ktlint")
}

group = "zone.clanker"
version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("atlas-host")
        browser {
            commonWebpackConfig {
                outputFileName = "atlas-host.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":atlas-store"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation("io.kotest:kotest-runner-junit5:5.9.1")
            implementation("io.kotest:kotest-assertions-core:5.9.1")
        }
        wasmJsMain.dependencies {
            implementation(compose.ui)
            implementation(compose.foundation)
        }
    }
}

val slopTestCompilation =
    kotlin.jvm().compilations.create("slopTest") {
        associateWith(kotlin.jvm().compilations.getByName("main"))
        defaultSourceSet.kotlin.srcDir("src/slopTest/kotlin")
        defaultSourceSet.dependencies {
            implementation("com.lemonappdev:konsist:0.17.3")
            implementation("io.kotest:kotest-runner-junit5:5.9.1")
            implementation("io.kotest:kotest-assertions-core:5.9.1")
        }
    }

tasks.register<Test>("slopTest") {
    description = "Run slop taste tests — architecture, naming, boundaries, style"
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = slopTestCompilation.output.classesDirs
    classpath = slopTestCompilation.output.allOutputs + slopTestCompilation.runtimeDependencyFiles
}

tasks.named("check") {
    dependsOn("slopTest")
}

tasks.withType<Test>().configureEach {
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
