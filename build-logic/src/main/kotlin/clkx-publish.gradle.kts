import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar

plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(GradlePlugin(javadocJar = JavadocJar.Empty()))
}
