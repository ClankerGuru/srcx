package zone.clanker.gradle.docx.site

import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceSearchEntry
import zone.clanker.report.model.WorkspaceSearchKind
import zone.clanker.report.model.WorkspaceSearchLocation
import zone.clanker.report.model.WorkspaceSearchTarget
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.workspaceSearchEntryComparator
import zone.clanker.report.model.workspaceSearchTerms

internal data class DocxStaticSearchProjection(
    val entries: List<WorkspaceSearchEntry>,
) {
    init {
        require(entries.map(WorkspaceSearchEntry::key).distinct().size == entries.size) {
            "Static search entries must be unique"
        }
        require(entries == entries.sortedWith(workspaceSearchEntryComparator())) {
            "Static search entries must be deterministic"
        }
    }

    companion object {
        fun apply(site: ProjectedDocxSite): DocxStaticSearchProjection {
            val context = StaticSearchProjectionContext(site)
            val entries =
                context.buildEntries() +
                    context.projectEntries() +
                    context.sourceSetEntries() +
                    context.sourceEntries() +
                    context.analysisEntries()
            return DocxStaticSearchProjection(entries.sortedWith(workspaceSearchEntryComparator()))
        }
    }
}

internal class StaticSearchProjectionContext(
    site: ProjectedDocxSite,
) {
    val buildsById: Map<String, BuildSnapshot> = site.builds.associateBy(BuildSnapshot::id)
    val projectsById: Map<String, ProjectSnapshot> = site.projects.associateBy(ProjectSnapshot::id)
    val sourceSetsById: Map<String, WorkspaceSourceSetSummary> =
        site.sourceSets.associateBy(WorkspaceSourceSetSummary::id)
    val graphs: List<ProjectGraphShard> = site.projectGraphs
    private val badgeIndexes: Map<String, WorkspaceSearchBadgeIndex> =
        graphs.associate { graph -> graph.projectId to WorkspaceSearchBadgeIndex(graph) }

    fun buildEntries(): List<WorkspaceSearchEntry> =
        buildsById.values.map { build ->
            WorkspaceSearchEntry(
                id = build.id,
                kind = WorkspaceSearchKind.BUILD,
                label = build.name,
                detail = "${build.kind.label} / ${build.relativePath}",
                terms = workspaceSearchTerms(build.name, build.relativePath, build.kind.label),
                target = WorkspaceSearchTarget(WorkspaceSearchLocation(buildId = build.id)),
            )
        }

    fun projectEntries(): List<WorkspaceSearchEntry> =
        projectsById.values.map { project ->
            val build = buildsById.getValue(project.buildId)
            WorkspaceSearchEntry(
                id = project.id,
                kind = WorkspaceSearchKind.PROJECT,
                label = project.path,
                detail = "${build.name} / ${project.buildFile}",
                terms = workspaceSearchTerms(project.path, project.buildFile, build.name),
                target =
                    WorkspaceSearchTarget(
                        WorkspaceSearchLocation(buildId = build.id, projectId = project.id),
                    ),
            )
        }

    fun sourceSetEntries(): List<WorkspaceSearchEntry> =
        sourceSetsById.values.map { sourceSet ->
            val project = projectsById.getValue(sourceSet.projectId)
            val build = buildsById.getValue(project.buildId)
            WorkspaceSearchEntry(
                id = sourceSet.id,
                kind = WorkspaceSearchKind.SOURCE_SET,
                label = sourceSet.name,
                detail = "${build.name} / ${project.path} / ${sourceSet.fileCount} files",
                terms = workspaceSearchTerms(sourceSet.name, project.path, build.name),
                target =
                    WorkspaceSearchTarget(
                        WorkspaceSearchLocation(
                            buildId = build.id,
                            projectId = project.id,
                            sourceSetId = sourceSet.id,
                        ),
                    ),
            )
        }

    fun ownedFiles(graph: ProjectGraphShard): List<SourceFileSnapshot> =
        graph.files.filter { file -> file.id in graph.ownedFileIds }

    fun ownedSymbols(graph: ProjectGraphShard): List<SymbolSnapshot> =
        graph.symbols.filter { symbol -> symbol.id in graph.ownedSymbolIds }

    fun scopeDetail(
        projectId: String,
        sourceSetId: String? = null,
    ): String {
        val project = projectsById.getValue(projectId)
        val build = buildsById.getValue(project.buildId)
        val sourceSet = sourceSetId?.let(sourceSetsById::get)?.name
        return listOfNotNull(build.name, project.path, sourceSet).joinToString(" / ")
    }

    fun sourceSet(file: SourceFileSnapshot): WorkspaceSourceSetSummary = sourceSetsById.getValue(file.sourceSetId)

    fun badges(
        projectId: String,
        entityId: String,
    ) = badgeIndexes.getValue(projectId).badges(entityId)

    fun sourceEntries(): List<WorkspaceSearchEntry> =
        packageEntries() + fileEntries() + symbolEntries()

    fun analysisEntries(): List<WorkspaceSearchEntry> = findingEntries() + cycleEntries()
}
