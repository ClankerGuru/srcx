pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("clkx-settings")
    id("zone.clanker.gradle.srcx") version "0.0.0-dev"
}

rootProject.name = "srcx"
