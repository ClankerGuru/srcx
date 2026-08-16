package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphSearchTarget
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.WorkspaceSummaryShard

internal fun workspaceSourceHierarchyDescriptors(
    summary: WorkspaceSummaryShard,
    loadedFacts: WorkspaceSourceHierarchyLoadedFacts,
): List<WorkspaceSourceHierarchyDescriptor> {
    val baseDescriptors = WorkspaceSourceHierarchyBaseDescriptors(summary, loadedFacts).descriptors()
    val detailDescriptors = WorkspaceSourceDetailDescriptors(loadedFacts, baseDescriptors).descriptors()
    val hierarchyDescriptors = baseDescriptors + detailDescriptors
    val overlayDescriptors = WorkspaceSourceOverlayDescriptors(loadedFacts, hierarchyDescriptors).descriptors()
    return (hierarchyDescriptors + overlayDescriptors).sortedBy(WorkspaceSourceHierarchyDescriptor::id)
}

private class WorkspaceSourceHierarchyBaseDescriptors(
    private val summary: WorkspaceSummaryShard,
    private val loadedFacts: WorkspaceSourceHierarchyLoadedFacts,
) {
    private val buildsById = summary.builds.associateBy(BuildSnapshot::id)
    private val projectsById = summary.projects.associateBy(ProjectSnapshot::id)
    private val summarySourceSetsById = summary.sourceSets.associateBy(WorkspaceSourceSetSummary::id)

    fun descriptors(): List<WorkspaceSourceHierarchyDescriptor> =
        listOf(workspaceDescriptor()) +
            summary.builds.map(::buildDescriptor) +
            summary.projects.map(::projectDescriptor) +
            sourceSetDescriptors()

    private fun sourceSetDescriptors(): List<WorkspaceSourceHierarchyDescriptor> {
        loadedFacts.sourceSetsById.values.forEach(::validateLoadedSourceSet)
        val summaryDescriptors = summary.sourceSets.map(::sourceSetDescriptor)
        val loadedDescriptors =
            loadedFacts.sourceSetsById.values
                .filter { sourceSet ->
                    sourceSet.id !in summarySourceSetsById && sourceSet.projectId in projectsById
                }.map(::sourceSetDescriptor)
        return (summaryDescriptors + loadedDescriptors).sortedBy(WorkspaceSourceHierarchyDescriptor::id)
    }

    private fun validateLoadedSourceSet(sourceSet: SourceSetSnapshot) {
        val summarySourceSet = summarySourceSetsById[sourceSet.id] ?: return
        require(summarySourceSet.projectId == sourceSet.projectId && summarySourceSet.name == sourceSet.name) {
            "Loaded source set conflicts with workspace summary: ${sourceSet.id}"
        }
    }

    private fun workspaceDescriptor(): WorkspaceSourceHierarchyDescriptor =
        WorkspaceSourceHierarchyDescriptor(
            id = summary.workspace.id,
            kind = WorkspaceGraphNodeKind.WORKSPACE,
            parentId = null,
            depth = 0,
            semanticLevel = WORKSPACE_LEVEL,
            label = summary.workspace.name,
            secondaryLabel = "Workspace",
        )

    private fun buildDescriptor(build: BuildSnapshot): WorkspaceSourceHierarchyDescriptor =
        WorkspaceSourceHierarchyDescriptor(
            id = build.id,
            kind = WorkspaceGraphNodeKind.BUILD,
            parentId = summary.workspace.id,
            depth = BUILD_DEPTH,
            semanticLevel = BUILD_LEVEL,
            label = build.name,
            secondaryLabel = build.kind.label,
            searchFields =
                mapOf(
                    WorkspaceGraphSearchTarget.BUILD to listOf(build.id, build.name, build.relativePath),
                ),
        )

    private fun projectDescriptor(project: ProjectSnapshot): WorkspaceSourceHierarchyDescriptor =
        WorkspaceSourceHierarchyDescriptor(
            id = project.id,
            kind = WorkspaceGraphNodeKind.PROJECT,
            parentId = project.buildId,
            depth = PROJECT_DEPTH,
            semanticLevel = PROJECT_LEVEL,
            label = project.path,
            secondaryLabel = buildsById.getValue(project.buildId).name,
            searchFields =
                mapOf(
                    WorkspaceGraphSearchTarget.PROJECT to listOf(project.id, project.path, project.buildFile),
                ),
        )

    private fun sourceSetDescriptor(sourceSet: WorkspaceSourceSetSummary): WorkspaceSourceHierarchyDescriptor =
        sourceSetDescriptor(sourceSet.id, sourceSet.projectId, sourceSet.name)

    private fun sourceSetDescriptor(sourceSet: SourceSetSnapshot): WorkspaceSourceHierarchyDescriptor =
        sourceSetDescriptor(sourceSet.id, sourceSet.projectId, sourceSet.name)

    private fun sourceSetDescriptor(
        id: String,
        projectId: String,
        name: String,
    ): WorkspaceSourceHierarchyDescriptor =
        WorkspaceSourceHierarchyDescriptor(
            id = id,
            kind = WorkspaceGraphNodeKind.SOURCE_SET,
            parentId = projectId,
            depth = SOURCE_SET_DEPTH,
            semanticLevel = SOURCE_SET_LEVEL,
            label = name,
            secondaryLabel = projectsById.getValue(projectId).path,
        )
}

internal const val WORKSPACE_LEVEL: Int = 0
internal const val BUILD_LEVEL: Int = 1
internal const val PROJECT_LEVEL: Int = 2
internal const val SOURCE_SET_LEVEL: Int = 3
internal const val PACKAGE_LEVEL: Int = 4
internal const val FILE_LEVEL: Int = 5
internal const val SYMBOL_LEVEL: Int = 6
internal const val BUILD_DEPTH: Int = 1
internal const val PROJECT_DEPTH: Int = 2
internal const val SOURCE_SET_DEPTH: Int = 3
