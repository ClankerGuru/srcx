plugins {
    id("clkx-kmp-library")
    id("org.jetbrains.kotlinx.rpc.plugin")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":workspace-report-model"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            api("org.jetbrains.kotlinx:kotlinx-rpc-core:0.10.3")
        }
    }
}

base {
    archivesName.set("docx-live-contract")
}

mavenPublishing {
    pom {
        name.set("DOCX Live Contract")
        description.set("Transport-neutral kRPC observation contract shared by DOCX JVM and Wasm clients.")
    }
}
