plugins {
    id("clkx-wasm-compose-application")
    id("clkx-docx-web-distribution")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":workspace-report-model"))
        }
    }
}
