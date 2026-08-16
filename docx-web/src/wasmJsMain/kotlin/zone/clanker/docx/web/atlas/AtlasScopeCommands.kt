package zone.clanker.docx.web.atlas

import zone.clanker.docx.web.atlas.session.AtlasFlyToRequested
import zone.clanker.docx.web.atlas.session.AtlasHierarchyLevel
import zone.clanker.docx.web.atlas.session.AtlasInspectionKind
import zone.clanker.docx.web.atlas.session.AtlasInspectionTarget
import zone.clanker.docx.web.atlas.session.AtlasSemanticId
import zone.clanker.report.model.WorkspaceSearchEntry

internal fun AtlasController.selectBuild(buildId: String?) {
    replaceScope(AtlasHierarchyLevel.BUILD, setOfNotNull(buildId))
}

internal fun AtlasController.toggleBuild(buildId: String) {
    replaceScope(AtlasHierarchyLevel.BUILD, selectedBuildIds.toggling(buildId))
}

internal fun AtlasController.clearBuilds() {
    replaceScope(AtlasHierarchyLevel.BUILD, emptySet())
}

internal fun AtlasController.selectProject(projectId: String) {
    replaceScope(AtlasHierarchyLevel.PROJECT, setOf(projectId))
}

internal fun AtlasController.toggleProject(projectId: String) {
    replaceScope(AtlasHierarchyLevel.PROJECT, selectedProjectIds.toggling(projectId))
}

internal fun AtlasController.replaceSourceSets(sourceSetIds: Set<String>) {
    replaceScope(AtlasHierarchyLevel.SOURCE_SET, sourceSetIds)
}

internal fun AtlasController.clearProjects() {
    replaceScope(AtlasHierarchyLevel.PROJECT, emptySet())
}

internal fun AtlasController.flyToSearchEntry(entry: WorkspaceSearchEntry) {
    val location = entry.target.location
    val revealAncestorId = location.sourceSetId ?: location.projectId ?: location.buildId
    dispatch(
        AtlasFlyToRequested(
            target = AtlasInspectionTarget(AtlasInspectionKind.NODE, AtlasSemanticId(entry.id)),
            revealAncestorId = revealAncestorId?.let(::AtlasSemanticId),
        ),
    )
}

private fun Set<String>.toggling(id: String): Set<String> = if (id in this) this - id else this + id
