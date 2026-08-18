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

val atlasSeedWasm =
    tasks.register<Copy>("syncAtlasSeedWasm") {
        dependsOn(":atlas-site:compileProductionExecutableKotlinWasmJsOptimize")
        from(
            rootProject.layout.projectDirectory.dir(
                "atlas-site/build/compileSync/wasmJs/main/productionExecutable/optimized",
            ),
        ) {
            include("atlas-seed.wasm", "atlas-seed.mjs", "atlas-seed.uninstantiated.mjs")
        }
        into(layout.buildDirectory.dir("generated/atlas-seed-wasm"))
    }

tasks.named<Copy>("processResources") {
    dependsOn(atlasSeedWasm)
    from(layout.buildDirectory.dir("generated/atlas-seed-wasm")) {
        into("zone/clanker/gradle/srcx/report/html/wasm")
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
