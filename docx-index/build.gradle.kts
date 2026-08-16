import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaToolchainService
import zone.clanker.gradle.conventions.WorkspaceAtlasPerformanceProfileTask

plugins {
    id("clkx-library")
}

val docxWeb = project(":docx-web")
val repositoryScaleFixture = docxWeb.layout.buildDirectory.dir("docxRepositoryScaleSite")
val performanceReport = layout.buildDirectory.dir("reports/workspaceAtlasPerformance/repository")
val javaToolchain = extensions.getByType(JavaPluginExtension::class.java).toolchain
val javaToolchains = extensions.getByType(JavaToolchainService::class.java)
val testSourceSet = extensions.getByType(SourceSetContainer::class.java).named("test")

tasks.register<WorkspaceAtlasPerformanceProfileTask>("workspaceAtlasPerformanceProfile") {
    dependsOn(tasks.named("testClasses"), ":docx-web:docxRepositoryScaleSite")
    runtimeClasspath.from(testSourceSet.map { sourceSet -> sourceSet.runtimeClasspath })
    siteDirectory.set(repositoryScaleFixture)
    reportDirectory.set(performanceReport)
    javaLauncher.set(javaToolchains.launcherFor(javaToolchain))
    sampleCount.set(providers.gradleProperty("docx.performance.samples").map(String::toInt).orElse(30))
}

dependencies {
    implementation(project(":workspace-report-model"))
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.2.0")
    testImplementation(project(":docx-web"))
}

base {
    archivesName.set("docx-index")
}

mavenPublishing {
    pom {
        name.set("DOCX Workspace Index")
        description.set("Generation-versioned SQLite search index for generated DOCX workspace sites.")
    }
}
