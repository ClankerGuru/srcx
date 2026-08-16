package zone.clanker.gradle.conventions

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.initialization.resolve.RepositoriesMode

class ClkxSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        requireJetBrainsJdk17()
        settings.pluginManager.apply("org.gradle.toolchains.foojay-resolver-convention")

        @Suppress("UnstableApiUsage")
        settings.dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
            repositories {
                mavenCentral()
                google {
                    content {
                        includeGroupByRegex("androidx\\..*")
                    }
                }
                gradlePluginPortal()
            }
        }
    }
}

private fun requireJetBrainsJdk17() {
    val vendor = System.getProperty("java.vendor")
    val version = System.getProperty("java.version")
    require(Runtime.version().feature() == REQUIRED_JAVA_FEATURE && vendor.contains("JetBrains")) {
        "Clanker builds require JetBrains JDK 17; set JAVA_HOME to a JetBrains JDK 17 installation before " +
            "running Gradle. Current runtime: $vendor $version."
    }
}

private const val REQUIRED_JAVA_FEATURE: Int = 17
