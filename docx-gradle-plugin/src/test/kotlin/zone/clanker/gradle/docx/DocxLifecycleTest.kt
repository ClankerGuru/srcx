package zone.clanker.gradle.docx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import org.gradle.api.plugins.BasePlugin
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.docx.task.DocxPlanTask

class DocxLifecycleTest :
    BehaviorSpec({
        given("a DOCX extension configured on a root project") {
            val project = ProjectBuilder.builder().build()
            val extension = project.objects.newInstance(DocxSettingsExtension::class.java)
            extension.output.directory.set("build/docx-lifecycle")
            extension.preset.set(DocxPreset.NONE)
            extension.features.workspace
                .enabled
                .set(true)

            `when`("the settings lifecycle registers its plan task") {
                Docx.SettingsPlugin().registerTasks(project, extension)
                val task = project.tasks.getByName(Docx.TASK_PLAN) as DocxPlanTask

                then("the task carries the deterministic plan and writes it") {
                    val plan = DocxAnalysisPlanJson.decode(task.planJson.get())
                    plan.features.single { it.feature == DocxFeature.WORKSPACE }.enabled shouldBe true
                    task.writePlan(task.outputFile.get().asFile, task.planJson.get())
                    task.outputFile
                        .get()
                        .asFile
                        .shouldExist()
                    project.projectDir.deleteRecursively()
                }
            }
        }

        given("the complete first-slice lifecycle") {
            val project = ProjectBuilder.builder().build()
            val extension = project.objects.newInstance(DocxSettingsExtension::class.java)
            val snapshot = project.tasks.register("srcx-context")

            `when`("the settings lifecycle registers its tasks") {
                Docx.SettingsPlugin().registerTasks(project, extension)

                then("site, open, and status remain one-command entry points") {
                    project.tasks.names.containsAll(
                        listOf(
                            Docx.TASK_PLAN,
                            Docx.TASK_SITE,
                            Docx.TASK_OPEN,
                            Docx.TASK_PUBLISH,
                            Docx.TASK_STATUS,
                        ),
                    ) shouldBe true
                    val open = project.tasks.getByName(Docx.TASK_OPEN)
                    open.taskDependencies.getDependencies(open).map { it.name } shouldBe listOf(Docx.TASK_SITE)
                    val site = project.tasks.getByName(Docx.TASK_SITE)
                    site.taskDependencies.getDependencies(site) shouldBe setOf(snapshot.get())
                    val publish = project.tasks.getByName(Docx.TASK_PUBLISH)
                    publish.taskDependencies.getDependencies(publish).map { it.name } shouldBe listOf(Docx.TASK_SITE)
                    project.projectDir.deleteRecursively()
                }
            }
        }

        given("automatic plan generation") {
            val project = ProjectBuilder.builder().build()
            project.pluginManager.apply(BasePlugin::class.java)
            val extension = project.objects.newInstance(DocxSettingsExtension::class.java)
            extension.autoGenerate.set(true)

            `when`("the settings lifecycle is registered") {
                Docx.SettingsPlugin().registerTasks(project, extension)

                then("assemble depends on the DOCX plan") {
                    val assemble = project.tasks.getByName("assemble")
                    assemble.taskDependencies.getDependencies(assemble).map { it.name } shouldBe
                        listOf(Docx.TASK_PLAN)
                    project.projectDir.deleteRecursively()
                }
            }
        }
    })
