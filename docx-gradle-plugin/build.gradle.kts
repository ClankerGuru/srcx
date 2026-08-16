import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("clkx-conventions")
    id("clkx-serialization")
}

val docxWebDistribution =
    configurations.create("docxWebDistribution") {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, "distribution"))
            attribute(Usage.USAGE_ATTRIBUTE, objects.named("docx-web-distribution"))
        }
    }

dependencies {
    implementation(project(":workspace-report-model"))
    add(docxWebDistribution.name, project(path = ":docx-web", configuration = "docxWebDistributionElements"))
}

tasks.named<ProcessResources>("processResources") {
    from(docxWebDistribution) {
        into("zone/clanker/gradle/docx/web")
        rename { "docx-web-distribution.zip" }
    }
}

base {
    archivesName.set("plugin-docx")
}

mavenPublishing {
    pom {
        name.set("Source Documentation Plugin (docx)")
        description.set("Scoped documentation and architecture reports for Gradle workspaces.")
    }
}

gradlePlugin {
    plugins {
        register("docx") {
            id = "zone.clanker.gradle.docx"
            implementationClass = "zone.clanker.gradle.docx.Docx\$SettingsPlugin"
            displayName = "Source Documentation Plugin (docx)"
            description = "Scoped source documentation report generation for Gradle workspaces."
        }
    }
}
