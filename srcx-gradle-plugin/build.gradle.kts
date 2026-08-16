plugins {
    id("clkx-conventions")
}

dependencies {
    implementation(project(":workspace-report-model"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable")
    implementation("org.webjars.npm:d3:7.9.0") {
        isTransitive = false
    }
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

base {
    archivesName.set("plugin-srcx")
}

gradlePlugin {
    plugins {
        register("srcx") {
            id = "zone.clanker.gradle.srcx"
            implementationClass = "zone.clanker.gradle.srcx.Srcx\$SettingsPlugin"
            displayName = "Source Symbol Plugin (srcx)"
            description = "Source symbol extraction for LLM-ready context generation."
        }
    }
}
