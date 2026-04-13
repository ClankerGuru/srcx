plugins {
    id("clkx-conventions")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable")
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
