plugins {
    id("clkx-kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
    }
}

base {
    archivesName.set("workspace-report-model")
}

mavenPublishing {
    pom {
        name.set("Workspace Report Model")
        description.set("Versioned renderer-neutral workspace analysis contract shared by SRCX and DOCX.")
    }
}
