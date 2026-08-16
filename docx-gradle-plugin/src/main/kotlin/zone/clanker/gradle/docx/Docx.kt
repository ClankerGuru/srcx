package zone.clanker.gradle.docx

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.language.base.plugins.LifecycleBasePlugin
import zone.clanker.gradle.docx.task.DocxOpenTask
import zone.clanker.gradle.docx.task.DocxPlanTask
import zone.clanker.gradle.docx.task.DocxPublishTask
import zone.clanker.gradle.docx.task.DocxSiteTask
import zone.clanker.gradle.docx.task.DocxStatusTask

/** Root identity and settings-plugin lifecycle for DOCX. */
data object Docx {
    const val GROUP: String = "docx"
    const val EXTENSION_NAME: String = "docx"
    const val DEFAULT_OUTPUT_DIRECTORY: String = ".docx"
    const val DEFAULT_SNAPSHOT_FILE: String = ".srcx/workspace-report.json"
    const val ANALYSIS_PLAN_FILE: String = "analysis-plan.json"
    const val STATUS_FILE: String = "status.json"
    const val WEB_DISTRIBUTION_RESOURCE: String = "zone/clanker/gradle/docx/web/docx-web-distribution.zip"
    const val TASK_PLAN: String = "docx-plan"
    const val TASK_SITE: String = "docx-site"
    const val TASK_OPEN: String = "docx-open"
    const val TASK_PUBLISH: String = "docx-publish"
    const val TASK_STATUS: String = "docx-status"

    /** Settings plugin entry point: `id("zone.clanker.gradle.docx")`. */
    class SettingsPlugin : Plugin<Settings> {
        override fun apply(settings: Settings) {
            val extension =
                settings.extensions.create(
                    EXTENSION_NAME,
                    DocxSettingsExtension::class.java,
                )

            settings.gradle.rootProject(
                Action { rootProject -> registerTasks(rootProject, extension) },
            )
        }

        internal fun registerTasks(
            rootProject: Project,
            extension: DocxSettingsExtension,
        ) {
            val plan = extension.analysisPlan()
            val planJson = DocxAnalysisPlanJson.encode(plan)
            val planTask =
                rootProject.tasks.register(TASK_PLAN, DocxPlanTask::class.java) { task ->
                    task.planJson.set(planJson)
                    task.outputFile.set(
                        rootProject.layout.buildDirectory.file("docx/$ANALYSIS_PLAN_FILE"),
                    )
                }
            val siteTask =
                rootProject.tasks.register(TASK_SITE, DocxSiteTask::class.java) { task ->
                    task.snapshotFile.set(rootProject.layout.projectDirectory.file(plan.output.snapshotFile))
                    task.planJson.set(planJson)
                    task.distributionResource.set(WEB_DISTRIBUTION_RESOURCE)
                    task.outputDirectory.set(rootProject.layout.projectDirectory.dir(plan.output.directory))
                    task.lockFile.set(rootProject.layout.buildDirectory.file("docx/site.lock"))
                }
            val openTask =
                rootProject.tasks.register(TASK_OPEN, DocxOpenTask::class.java) { task ->
                    task.dependsOn(siteTask)
                    task.siteDirectory.set(rootProject.layout.projectDirectory.dir(plan.output.directory))
                }
            rootProject.tasks.register(TASK_PUBLISH, DocxPublishTask::class.java) { task ->
                task.dependsOn(siteTask)
                task.siteDirectory.set(rootProject.layout.projectDirectory.dir(plan.output.directory))
                task.workspaceId.set(extension.service.workspaceId.orElse(rootProject.name))
                task.serviceUrl.set(extension.service.url)
                task.serviceToken.set(
                    extension.service.token
                        .orElse(rootProject.providers.environmentVariable("DOCX_SERVICE_TOKEN"))
                        .orElse(""),
                )
                task.endpointFile.set(extension.service.endpointFile)
            }
            rootProject.tasks.register(TASK_STATUS, DocxStatusTask::class.java) { task ->
                task.statusFile.set(
                    rootProject.layout.projectDirectory.file("${plan.output.directory}/$STATUS_FILE"),
                )
            }
            siteTask.configure { task ->
                task.dependsOn(rootProject.tasks.matching { it.name == SNAPSHOT_PRODUCER_TASK })
            }
            siteTask.configure { task -> task.mustRunAfter(planTask) }
            openTask.configure { task -> task.mustRunAfter(planTask) }

            if (extension.autoGenerate.get()) {
                rootProject.plugins.withType(LifecycleBasePlugin::class.java) {
                    rootProject.tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure { assemble ->
                        assemble.dependsOn(planTask)
                    }
                }
            }
        }

        private companion object {
            const val SNAPSHOT_PRODUCER_TASK: String = "srcx-context"
        }
    }
}
