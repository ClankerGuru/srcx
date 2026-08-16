plugins {
    id("clkx-library")
    id("clkx-serialization")
    application
}

dependencies {
    implementation(project(":docx-index"))
    implementation(project(":workspace-report-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

application {
    mainClass.set("zone.clanker.docx.service.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

base {
    archivesName.set("docx-service")
}

mavenPublishing {
    pom {
        name.set("DOCX Workspace Service")
        description.set("Headless multi-workspace host for generated DOCX sites.")
    }
}
