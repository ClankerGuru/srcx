import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-library`
    kotlin("jvm")
    id("clkx-toolchain")
    id("clkx-testing")
    id("clkx-detekt")
    id("clkx-ktlint")
    id("com.vanniktech.maven.publish")
}

group = "zone.clanker"
val publicationVersion = providers.gradleProperty("VERSION_NAME").get()

version = publicationVersion

tasks.withType<Test> {
    useJUnitPlatform()
}

mavenPublishing {
    coordinates(project.group.toString(), project.name, publicationVersion)
    configure(JavaLibrary(javadocJar = JavadocJar.Empty()))
}
