package zone.clanker.gradle.srcx.scan

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly

class IncludedBuildProjectDiscoveryTest :
    BehaviorSpec({
        given("an included build whose project models have not been created") {
            `when`("the build is prepared for project discovery") {
                val target = IncludedBuildLifecycleFixture()

                IncludedBuildProjectDiscovery.ensureProjectsConfigured(target)

                then("settings are loaded before the build lifecycle creates and configures its projects") {
                    target.transitions shouldContainExactly listOf("loaded", "configured")
                }
            }
        }
    })

private class IncludedBuildLifecycleFixture {
    val transitions = mutableListOf<String>()
    private var projectsLoaded = false

    fun ensureProjectsLoaded() {
        projectsLoaded = true
        transitions += "loaded"
    }

    fun ensureProjectsConfigured() {
        check(projectsLoaded) { "projects must be loaded before configuration" }
        transitions += "configured"
    }
}
