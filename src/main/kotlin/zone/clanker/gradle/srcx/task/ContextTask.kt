package zone.clanker.gradle.srcx.task

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.srcx.Srcx
import zone.clanker.gradle.srcx.report.DashboardRenderer
import zone.clanker.gradle.srcx.report.ReportWriter
import zone.clanker.gradle.srcx.scan.ProjectScanner
import zone.clanker.gradle.srcx.scan.SymbolExtractor
import java.io.File

/**
 * Generates the comprehensive context report with symbols, analysis, and diagrams.
 *
 * Collects all projects from the root, runs parallel symbol extraction,
 * generates included build reports, builds the dashboard, and writes `context.md`.
 *
 * ```bash
 * ./gradlew srcx-context
 * cat .srcx/context.md
 * ```
 *
 * @see CleanTask
 * @see Srcx
 */
@org.gradle.work.DisableCachingByDefault(because = "Reads source files from disk each run")
abstract class ContextTask : DefaultTask() {
    /** Output directory relative to the root project (e.g. `.srcx`). */
    @get:Input
    abstract val outputDir: Property<String>

    init {
        group = Srcx.GROUP
        description = "Generate comprehensive context report"
    }

    /**
     * Generate per-project symbol reports and the dashboard context file.
     *
     * 1. Collect all projects (root + subprojects)
     * 2. Run parallel symbol extraction, writing per-project reports
     * 3. Generate included build reports
     * 4. Build the dashboard with summaries, build edges, and class diagram
     * 5. Write `context.md` and `.gitignore`
     */
    @TaskAction
    fun generate() {
        val rootProject = project.rootProject
        val projects = ProjectScanner.collectProjects(rootProject)

        // Per-project symbol reports
        ReportWriter.runParallel(projects) { proj ->
            val summary = SymbolExtractor.extractProjectSummary(proj, rootProject)
            ReportWriter.writeProjectReport(rootProject, summary, outputDir.get())
            "OK ${proj.path}"
        }
        val includedBuilds = rootProject.gradle.includedBuilds
        ReportWriter.generateIncludedBuildReports(includedBuilds, outputDir.get())

        // Context report
        val summaries = projects.map { SymbolExtractor.extractProjectSummary(it, rootProject) }
        val includedBuildRefs =
            includedBuilds.map { build ->
                val relPath = build.projectDir.relativeTo(rootProject.projectDir).path
                DashboardRenderer.IncludedBuildRef(build.name, relPath)
            }
        val includedBuildSummaries = ReportWriter.collectIncludedBuildSummaries(includedBuilds)
        val buildPairs = includedBuilds.map { it.name to it.projectDir }
        val buildEdges = ReportWriter.computeBuildEdges(buildPairs, includedBuildSummaries)
        val classDiagram = ReportWriter.generateClassDiagram(rootProject)
        val renderer =
            DashboardRenderer(
                rootName = rootProject.name,
                summaries = summaries,
                includedBuilds = includedBuildRefs,
                includedBuildSummaries = includedBuildSummaries,
                buildEdges = buildEdges,
                classDiagram = classDiagram,
            )
        val dir = File(rootProject.projectDir, outputDir.get())
        dir.mkdirs()
        File(dir, "context.md").writeText(renderer.render())
        ReportWriter.writeGitignore(rootProject.projectDir, outputDir.get())
        println("srcx: context written to ${outputDir.get()}/context.md")
    }
}
