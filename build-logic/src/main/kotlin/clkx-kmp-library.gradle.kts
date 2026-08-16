import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("clkx-toolchain")
    id("clkx-detekt")
    id("clkx-ktlint")
    id("org.jetbrains.kotlinx.kover")
    id("com.vanniktech.maven.publish")
}

group = "zone.clanker"
val publicationVersion = providers.gradleProperty("VERSION_NAME").get()

version = publicationVersion

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("io.kotest:kotest-runner-junit5:5.9.1")
            implementation("io.kotest:kotest-assertions-core:5.9.1")
            implementation("io.kotest:kotest-framework-datatest:5.9.1")
        }
    }
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

tasks.named("check") {
    dependsOn("detekt")
}

kover {
    reports {
        filters {
            excludes {
                // It is prohibited to add exclusions.
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
}
