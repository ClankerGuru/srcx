plugins {
    id("clkx-conventions")
}

dependencies {
    implementation(project(":workspace-report-model"))
    implementation(project(":atlas-store"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable")
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    implementation("org.webjars.npm:d3:7.9.0") {
        isTransitive = false
    }
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

base {
    archivesName.set("plugin-srcx")
}

val atlasComposeHost =
    tasks.register("syncAtlasComposeHost") {
        dependsOn(":atlas-site:wasmJsBrowserDistribution")
        val fromDir =
            rootProject.layout.projectDirectory.dir("atlas-site/build/dist/wasmJs/productionExecutable")
        val intoDir = layout.buildDirectory.dir("generated/atlas-compose-host")
        inputs.dir(fromDir)
        outputs.dir(intoDir)
        doLast {
            val source = fromDir.asFile
            val target = intoDir.get().asFile
            target.deleteRecursively()
            target.mkdirs()
            source.copyRecursively(target, overwrite = true)
            val names =
                source
                    .walkTopDown()
                    .filter { file -> file.isFile }
                    .map { file -> file.relativeTo(source).invariantSeparatorsPath }
                    .sorted()
                    .joinToString("\n")
            target.resolve("listing.txt").writeText(names + "\n")
        }
    }

tasks.named<Copy>("processResources") {
    dependsOn(atlasComposeHost)
    from(layout.buildDirectory.dir("generated/atlas-compose-host")) {
        into("zone/clanker/gradle/srcx/report/html/compose")
    }
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
