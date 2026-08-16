import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.toolchain.JavaToolchainService
import zone.clanker.gradle.conventions.DocxWebBrowserSmokeTask
import zone.clanker.gradle.conventions.GenerateDocxWebFixtureTask
import zone.clanker.gradle.conventions.GenerateDocxScaleFixtureTask
import zone.clanker.gradle.conventions.NormalizeDocxWebDistributionTask
import zone.clanker.gradle.conventions.PrepareDocxRealCompositeDemoTask
import zone.clanker.gradle.conventions.StageDocxAtlasAssetsTask
import zone.clanker.gradle.conventions.ValidateDocxWebDistributionTask

val browserDistribution = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
val generatedFixture = layout.buildDirectory.dir("generated/docxWebFixture")
val scaleProfile = providers.gradleProperty("docx.scale.profile").orElse("10k")
val generatedScaleFixture = layout.buildDirectory.dir(scaleProfile.map { "generated/docxScaleFixture/$it" })
val generatedRepositoryScaleFixture = layout.buildDirectory.dir("generated/docxRepositoryScaleFixture")
val generatedAtlasAssets = layout.buildDirectory.dir("generated/docxAtlasAssets")
val rawDistribution = layout.buildDirectory.dir("docxWebDistributionRaw")
val stagedDistribution = layout.buildDirectory.dir("docxWebDistribution")
val stagedScaleSite = layout.buildDirectory.dir(scaleProfile.map { "docxScaleSite/$it" })
val stagedRepositoryScaleSite = layout.buildDirectory.dir("docxRepositoryScaleSite")
val realCompositeProfile = providers.gradleProperty("docx.realComposite.profile").orElse("large")
val realCompositeRoot = providers.gradleProperty("docx.realComposite.root")
val realCompositeDemo = rootProject.layout.buildDirectory.dir("realCompositeDemo")
val atlasResourceDirectory =
    rootProject.layout.projectDirectory.dir(
        "srcx-gradle-plugin/src/main/resources/zone/clanker/gradle/srcx/report/html",
    )
val javaToolchain = extensions.getByType(JavaPluginExtension::class.java).toolchain
val javaToolchains = extensions.getByType(JavaToolchainService::class.java)
val d3WebJar =
    configurations.create("docxWebD3") {
        isCanBeConsumed = false
        isCanBeResolved = true
        description = "Approved D3 7.9.0 WebJar used by the DOCX Atlas graph"
    }

dependencies {
    add(d3WebJar.name, "org.webjars.npm:d3:7.9.0") {
        isTransitive = false
    }
}

val generateDocxWebFixture =
    tasks.register<GenerateDocxWebFixtureTask>("generateDocxWebFixture") {
        dependsOn(tasks.named("jvmMainClasses"))
        runtimeClasspath.from(
            layout.buildDirectory.dir("classes/kotlin/jvm/main"),
            layout.buildDirectory.dir("processedResources/jvm/main"),
            configurations.named("jvmRuntimeClasspath"),
        )
        javaLauncher.set(javaToolchains.launcherFor(javaToolchain))
        outputDirectory.set(generatedFixture)
    }

val generateDocxScaleFixture =
    tasks.register<GenerateDocxScaleFixtureTask>("generateDocxScaleFixture") {
        dependsOn(tasks.named("jvmMainClasses"))
        runtimeClasspath.from(
            layout.buildDirectory.dir("classes/kotlin/jvm/main"),
            layout.buildDirectory.dir("processedResources/jvm/main"),
            configurations.named("jvmRuntimeClasspath"),
        )
        javaLauncher.set(javaToolchains.launcherFor(javaToolchain))
        profileName.set(scaleProfile)
        outputDirectory.set(generatedScaleFixture)
    }

tasks.register<GenerateDocxScaleFixtureTask>("generateDocxRepositoryScaleFixture") {
    dependsOn(tasks.named("jvmMainClasses"))
    runtimeClasspath.from(
        layout.buildDirectory.dir("classes/kotlin/jvm/main"),
        layout.buildDirectory.dir("processedResources/jvm/main"),
        configurations.named("jvmRuntimeClasspath"),
    )
    javaLauncher.set(javaToolchains.launcherFor(javaToolchain))
    profileName.set("repository")
    outputDirectory.set(generatedRepositoryScaleFixture)
}

val stageDocxAtlasAssets =
    tasks.register<StageDocxAtlasAssetsTask>("stageDocxAtlasAssets") {
        themeStylesheet.set(atlasResourceDirectory.file("theme.css"))
        dashboardStylesheet.set(atlasResourceDirectory.file("dashboard.css"))
        d3WebJarFiles.from(d3WebJar)
        outputDirectory.set(generatedAtlasAssets)
    }

val stageDocxWebDistribution =
    tasks.register<Sync>("stageDocxWebDistribution") {
        group = "distribution"
        description = "Arrange the DOCX viewer in its published static-site layout"
        dependsOn(tasks.named("wasmJsBrowserDistribution"), generateDocxWebFixture, stageDocxAtlasAssets)
        includeEmptyDirs = false

        from(browserDistribution) {
            include("index.html")
        }
        from(browserDistribution) {
            exclude("index.html")
            into("assets")
        }
        from(stageDocxAtlasAssets) {
            into("assets")
        }
        from(generatedFixture)
        into(rawDistribution)
    }

val normalizeDocxWebDistribution =
    tasks.register<NormalizeDocxWebDistributionTask>("normalizeDocxWebDistribution") {
        dependsOn(stageDocxWebDistribution)
        sourceDirectory.set(rawDistribution)
        outputDirectory.set(stagedDistribution)
    }

val validateDocxWebDistribution =
    tasks.register<ValidateDocxWebDistributionTask>("validateDocxWebDistribution") {
        distributionDirectory.set(stagedDistribution)
        forbiddenWorkspacePath.set(rootProject.layout.projectDirectory.asFile.invariantSeparatorsPath)
        canonicalThemeStylesheet.set(atlasResourceDirectory.file("theme.css"))
        canonicalDashboardStylesheet.set(atlasResourceDirectory.file("dashboard.css"))
        dependsOn(normalizeDocxWebDistribution)
    }

val docxScaleSite =
    tasks.register<Sync>("docxScaleSite") {
        group = "verification"
        description = "Stage the production DOCX viewer with the selected deterministic sharded scale fixture"
        dependsOn(validateDocxWebDistribution, generateDocxScaleFixture)
        includeEmptyDirs = false

        from(stagedDistribution) {
            exclude("data/**")
        }
        from(generatedScaleFixture)
        into(stagedScaleSite)
    }

tasks.register<Sync>("docxRepositoryScaleSite") {
    group = "verification"
    description = "Stage the production DOCX viewer with the deterministic repository-topology fixture"
    dependsOn(validateDocxWebDistribution, tasks.named("generateDocxRepositoryScaleFixture"))
    includeEmptyDirs = false

    from(stagedDistribution) {
        exclude("data/**")
    }
    from(generatedRepositoryScaleFixture)
    into(stagedRepositoryScaleSite)
}

val docxWebBrowserSmoke =
    tasks.register<DocxWebBrowserSmokeTask>("docxWebBrowserSmoke") {
        dependsOn(validateDocxWebDistribution)
        distributionDirectory.set(stagedDistribution)
        canonicalThemeStylesheet.set(atlasResourceDirectory.file("theme.css"))
        canonicalDashboardStylesheet.set(atlasResourceDirectory.file("dashboard.css"))
        screenshotDirectory.set(layout.buildDirectory.dir("reports/docxWebBrowserSmoke"))
        chromeExecutable.set(
            providers
                .environmentVariable("CHROME_BIN")
                .orElse(providers.environmentVariable("CHROMIUM_BIN")),
        )
        externalReportUrl.set(providers.gradleProperty("docxWebBrowserExternalUrl"))
        referenceReportUrl.set(providers.gradleProperty("docxWebBrowserReferenceUrl"))
        readyCaptureOnly.set(
            providers
                .gradleProperty("docxWebBrowserReadyCaptureOnly")
                .map(String::toBoolean)
                .orElse(false),
        )
        browserProofEnabled.set(false)
        externalNodeSelectionOnly.set(false)
    }

val docxWebBrowserProof =
    tasks.register<DocxWebBrowserSmokeTask>("docxWebBrowserProof") {
        dependsOn(validateDocxWebDistribution)
        distributionDirectory.set(stagedDistribution)
        canonicalThemeStylesheet.set(atlasResourceDirectory.file("theme.css"))
        canonicalDashboardStylesheet.set(atlasResourceDirectory.file("dashboard.css"))
        screenshotDirectory.set(layout.buildDirectory.dir("reports/docxWebBrowserProof"))
        chromeExecutable.set(
            providers
                .environmentVariable("CHROME_BIN")
                .orElse(providers.environmentVariable("CHROMIUM_BIN")),
        )
        externalReportUrl.set(providers.gradleProperty("docxWebBrowserExternalUrl"))
        referenceReportUrl.set(providers.gradleProperty("docxWebBrowserReferenceUrl"))
        readyCaptureOnly.set(false)
        browserProofEnabled.set(true)
        externalNodeSelectionOnly.set(
            providers
                .gradleProperty("docxWebBrowserNodeSelectionOnly")
                .map(String::toBoolean)
                .orElse(false),
        )
    }

tasks.register<PrepareDocxRealCompositeDemoTask>("prepareDocxRealCompositeDemo") {
    repositoryRootPath.set(realCompositeRoot)
    profileName.set(realCompositeProfile)
    outputDirectory.set(realCompositeDemo)
}

val docxWebDistributionZip =
    tasks.register<Zip>("docxWebDistributionZip") {
        group = "distribution"
        description = "Package the precompiled DOCX Wasm viewer for the DOCX Gradle plugin"
        dependsOn(validateDocxWebDistribution)
        from(stagedDistribution)
        archiveFileName.set("docx-web-distribution.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

val docxWebDistributionElements =
    configurations.create("docxWebDistributionElements") {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, "distribution"))
            attribute(Usage.USAGE_ATTRIBUTE, objects.named("docx-web-distribution"))
        }
    }

artifacts {
    add(docxWebDistributionElements.name, docxWebDistributionZip)
}

tasks.named("assemble") {
    dependsOn(docxWebDistributionZip)
}

tasks.named("check") {
    dependsOn(docxWebBrowserSmoke)
}

tasks.withType<Test>().configureEach {
    dependsOn(generateDocxWebFixture)
    inputs.dir(generatedFixture)
    systemProperty("docx.fixture.directory", generatedFixture.get().asFile.absolutePath)
}
