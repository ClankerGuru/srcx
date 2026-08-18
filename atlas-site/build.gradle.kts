plugins {
    kotlin("multiplatform")
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
        outputModuleName.set("atlas-seed")
        browser {
            binaries.executable()
        }
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":atlas-store"))
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

detekt {
    source.setFrom(files("src/wasmJsMain/kotlin"))
}
