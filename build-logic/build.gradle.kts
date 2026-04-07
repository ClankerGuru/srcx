plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${embeddedKotlinVersion}")
    implementation("org.jetbrains.kotlin:kotlin-serialization:${embeddedKotlinVersion}")
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.36.0")
    implementation("org.gradle.toolchains:foojay-resolver:1.0.0")
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.1")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.7")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:12.1.2")
}

gradlePlugin {
    plugins {
        register("clkx-settings") {
            id = "clkx-settings"
            implementationClass = "zone.clanker.gradle.conventions.ClkxSettingsPlugin"
        }
    }
}
