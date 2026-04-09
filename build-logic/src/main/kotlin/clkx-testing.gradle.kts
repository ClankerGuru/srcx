plugins {
    `java-base`
    id("org.jetbrains.kotlinx.kover")
}

val sourceSets = the<SourceSetContainer>()

dependencies {
    "testImplementation"("io.kotest:kotest-runner-junit5:5.9.1")
    "testImplementation"("io.kotest:kotest-assertions-core:5.9.1")
    "testImplementation"("io.kotest:kotest-framework-datatest:5.9.1")
    "testImplementation"("org.testcontainers:testcontainers:1.20.4")
    "testImplementation"("org.testcontainers:junit-jupiter:1.20.4")
    "testImplementation"(gradleTestKit())
}

kover {
    reports {
        filters {
            excludes {
                // It is prohibited to add exclusions
            }
        }
        verify {
            rule {
                // Settings.apply(), data object synthetic lines, task @TaskAction
                // methods, and IncludedBuild methods (which use Gradle internal API
                // via reflection and can't be constructed in unit tests) run only in
                // TestKit's forked JVM which kover cannot instrument.
                minBound(90)
            }
        }
    }
}

val slopTest by sourceSets.creating {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

configurations[slopTest.implementationConfigurationName].extendsFrom(configurations["testImplementation"])
configurations[slopTest.runtimeOnlyConfigurationName].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    "slopTestImplementation"("com.lemonappdev:konsist:0.17.3")
    "slopTestImplementation"("io.kotest:kotest-runner-junit5:5.9.1")
    "slopTestImplementation"("io.kotest:kotest-assertions-core:5.9.1")
}

val slopTask = tasks.register<Test>("slopTest") {
    description = "Run slop taste tests — architecture, naming, boundaries, style"
    group = "verification"
    testClassesDirs = slopTest.output.classesDirs
    classpath = slopTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(slopTask)
}
