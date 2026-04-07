plugins {
    `java-library`
    kotlin("jvm")
}

group = "zone.clanker"

dependencies {
    "implementation"(gradleApi())
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(tasks.withType<Sign>())
}
