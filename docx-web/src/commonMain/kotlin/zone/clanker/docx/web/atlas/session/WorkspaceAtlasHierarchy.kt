package zone.clanker.docx.web.atlas.session

import zone.clanker.report.model.WorkspaceSummaryShard

internal fun workspaceAtlasHierarchy(summary: WorkspaceSummaryShard): AtlasHierarchyIndex {
    val builds =
        summary.builds.map { build ->
            AtlasHierarchyNode(
                id = AtlasSemanticId(build.id),
                level = AtlasHierarchyLevel.BUILD,
            )
        }
    val projects =
        summary.projects.map { project ->
            AtlasHierarchyNode(
                id = AtlasSemanticId(project.id),
                level = AtlasHierarchyLevel.PROJECT,
                parentId = AtlasSemanticId(project.buildId),
            )
        }
    val sourceSets =
        summary.sourceSets.map { sourceSet ->
            AtlasHierarchyNode(
                id = AtlasSemanticId(sourceSet.id),
                level = AtlasHierarchyLevel.SOURCE_SET,
                parentId = AtlasSemanticId(sourceSet.projectId),
            )
        }
    return AtlasHierarchyIndex.of(builds + projects + sourceSets)
}
