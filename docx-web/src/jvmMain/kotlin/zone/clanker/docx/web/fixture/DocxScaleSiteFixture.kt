package zone.clanker.docx.web.fixture

import zone.clanker.report.model.WorkspaceSiteJson
import java.nio.file.Files
import java.nio.file.Path

/** Deterministic sharded static sites used to exercise the production viewer at meaningful scales. */
internal object DocxScaleSiteFixture {
    val tenThousandSymbols =
        DocxScaleDimensions(
            profileName = "10k",
            workspaceName = "DOCX 10K Scale Workspace",
            buildCount = 2,
            projectsPerBuild = 5,
            filesPerProject = 100,
            symbolsPerFile = 10,
            linesPerFile = 200,
        )

    val twentyFiveThousandSymbols =
        DocxScaleDimensions(
            profileName = "25k",
            workspaceName = "DOCX 25K Composite Workspace",
            buildCount = 5,
            projectsPerBuild = 5,
            filesPerProject = 45,
            symbolsPerFile = 10,
            linesPerFile = 200,
            projectFileOverrides =
                mapOf(
                    4 to 200,
                    5 to 500,
                    11 to 500,
                    17 to 200,
                    23 to 200,
                ),
            buildNames = listOf("scale-app", "scale-platform", "scale-services", "scale-data", "scale-tooling"),
            includeProjectDependencies = true,
        )

    /**
     * Repository-topology profile for opt-in performance proof. It materializes only the source evidence needed
     * by bounded queries while retaining the declared logical line volume of the represented workspace.
     */
    val repositoryTopology =
        DocxScaleDimensions(
            profileName = "repository",
            workspaceName = "DOCX Repository Scale Workspace",
            buildCount = 80,
            projectsPerBuild = 25,
            filesPerProject = 2,
            symbolsPerFile = 2,
            linesPerFile = 8,
            logicalLinesPerFile = 2_500,
            includeProjectDependencies = true,
        )

    fun profile(name: String): DocxScaleDimensions =
        when (name) {
            tenThousandSymbols.profileName -> tenThousandSymbols
            twentyFiveThousandSymbols.profileName -> twentyFiveThousandSymbols
            repositoryTopology.profileName -> repositoryTopology
            else -> error("Unknown DOCX scale profile '$name'; expected 10k, 25k, or repository")
        }

    fun generate(
        destination: Path,
        dimensions: DocxScaleDimensions = tenThousandSymbols,
    ) {
        requireEmptyDestination(destination)
        val catalog = DocxScaleCatalog(dimensions)
        write(destination.resolve(MANIFEST_PATH), WorkspaceSiteJson.encodeManifest(catalog.manifest()))
        write(destination.resolve(WORKSPACE_PATH), WorkspaceSiteJson.encodeWorkspace(catalog.summary()))
        write(destination.resolve(DASHBOARD_PATH), WorkspaceSiteJson.encodeDashboard(catalog.dashboard()))
        write(destination.resolve(ATLAS_PATH), WorkspaceSiteJson.encodeAtlasFrame(catalog.atlasOverview()))
        write(destination.resolve(PROFILE_PATH), encodeProfile(dimensions))
        catalog.projects.forEach { project ->
            write(
                destination.resolve(project.shardFile),
                WorkspaceSiteJson.encodeProject(catalog.projectGraph(project)),
            )
        }
    }

    private fun requireEmptyDestination(destination: Path) {
        Files.createDirectories(destination)
        Files.list(destination).use { entries ->
            require(entries.findAny().isEmpty) { "Scale-site destination must be empty: $destination" }
        }
    }

    private fun write(
        target: Path,
        content: String,
    ) {
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    private fun encodeProfile(dimensions: DocxScaleDimensions): String =
        buildString {
            appendLine("profile=${dimensions.profileName}")
            appendLine("builds=${dimensions.buildCount}")
            appendLine("projects=${dimensions.projectCount}")
            appendLine("files=${dimensions.fileCount}")
            appendLine("symbols=${dimensions.symbolCount}")
            appendLine("relationships=${dimensions.relationshipCount}")
            appendLine("logicalLines=${dimensions.logicalLineCount}")
            appendLine("materializedLines=${dimensions.materializedLineCount}")
        }

    internal const val MANIFEST_PATH = "data/manifest.json"
    internal const val WORKSPACE_PATH = "data/workspace.json"
    internal const val DASHBOARD_PATH = "data/dashboard.json"
    internal const val ATLAS_PATH = "data/atlas-overview.json"
    internal const val PROFILE_PATH = "data/scale-profile.properties"
}

fun main(arguments: Array<String>) {
    require(arguments.size in 1..2) { "DOCX scale-site generation requires an output directory and optional profile" }
    val dimensions = DocxScaleSiteFixture.profile(arguments.getOrElse(1) { "10k" })
    DocxScaleSiteFixture.generate(Path.of(arguments.first()), dimensions)
}
