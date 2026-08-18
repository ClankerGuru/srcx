import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("clkx-toolchain")
    id("clkx-detekt")
    id("clkx-ktlint")
    id("com.vanniktech.maven.publish")
}

group = "zone.clanker"
version = providers.gradleProperty("VERSION_NAME").get()
extra["POM_ARTIFACT_ID"] = "atlas-store"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("io.kotest:kotest-runner-junit5:5.9.1")
            implementation("io.kotest:kotest-assertions-core:5.9.1")
            implementation("org.xerial:sqlite-jdbc:3.53.2.0")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
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

val slopTest =
    tasks.register<Test>("slopTest") {
        description = "Run slop taste tests — architecture, naming, boundaries, style"
        group = "verification"
        useJUnitPlatform()
        testClassesDirs = slopTestCompilation.output.classesDirs
        classpath = slopTestCompilation.output.allOutputs + slopTestCompilation.runtimeDependencyFiles
    }

tasks.named("check") {
    dependsOn(slopTest)
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

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
}
