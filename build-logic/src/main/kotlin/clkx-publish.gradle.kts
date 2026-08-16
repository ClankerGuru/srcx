import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar

plugins {
    id("com.vanniktech.maven.publish")
}

val publicationArtifactId =
    if (project == rootProject) {
        providers.gradleProperty("POM_ARTIFACT_ID").get()
    } else {
        project.name
            .removeSuffix("-gradle-plugin")
            .let { name -> if (name == project.name) name else "plugin-$name" }
    }
val publicationVersion = providers.gradleProperty("VERSION_NAME").get()

version = publicationVersion

mavenPublishing {
    coordinates(project.group.toString(), publicationArtifactId, publicationVersion)
    configure(GradlePlugin(javadocJar = JavadocJar.Empty()))
}
