pluginManagement {
    val isJetBrainsJdk17 =
        JavaVersion.current() == JavaVersion.VERSION_17 &&
            System.getProperty("java.vendor").contains("JetBrains")
    require(isJetBrainsJdk17) {
        "SRCX builds require JetBrains JDK 17; set JAVA_HOME to a JetBrains JDK 17 installation before " +
            "running Gradle. " +
            "Current runtime: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}."
    }

    includeBuild("build-logic") { name = "srcx-build-logic" }

    plugins {
        kotlin("multiplatform") version "2.3.0"
        kotlin("plugin.compose") version "2.3.0"
        kotlin("plugin.serialization") version "2.3.0"
        id("org.jetbrains.compose") version "1.10.3"
        id("org.jetbrains.kotlinx.rpc.plugin") version "0.10.3"
    }
}

plugins {
    id("clkx-settings")
}

rootProject.name = "srcx"

include(":srcx-gradle-plugin")
include(":atlas-store")
include(":atlas-site")
include(":workspace-report-model")
include(":docx-gradle-plugin")
include(":docx-index")
include(":docx-live-contract")
include(":docx-service")
include(":docx-web")
