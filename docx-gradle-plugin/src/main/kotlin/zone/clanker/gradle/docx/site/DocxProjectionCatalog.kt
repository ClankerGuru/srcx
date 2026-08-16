package zone.clanker.gradle.docx.site

import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.ProjectAnalysisSnapshot
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceSnapshot

/** Read-only ownership and evidence indexes shared by every project-shard projection. */
internal class DocxProjectionCatalog(
    snapshot: WorkspaceSnapshot,
) {
    val builds: List<BuildSnapshot> = snapshot.builds
    val projects: List<ProjectSnapshot> = snapshot.projects
    val sourceSets: List<SourceSetSnapshot> = snapshot.sourceSets

    val buildById: Map<String, BuildSnapshot> = builds.associateBy(BuildSnapshot::id)
    val buildByName: Map<String, BuildSnapshot> = builds.associateBy(BuildSnapshot::name)
    val projectById: Map<String, ProjectSnapshot> = projects.associateBy(ProjectSnapshot::id)
    val projectsByBuildId: Map<String, List<ProjectSnapshot>> = projects.groupBy(ProjectSnapshot::buildId)
    val sourceSetById: Map<String, SourceSetSnapshot> = sourceSets.associateBy(SourceSetSnapshot::id)
    val sourceSetsByProjectId: Map<String, List<SourceSetSnapshot>> =
        sourceSets.groupBy(SourceSetSnapshot::projectId)
    val fileById: Map<String, SourceFileSnapshot> = snapshot.files.associateBy(SourceFileSnapshot::id)
    val filesBySourceSetId: Map<String, List<SourceFileSnapshot>> =
        snapshot.files.groupBy(SourceFileSnapshot::sourceSetId)
    val symbolById: Map<String, SymbolSnapshot> = snapshot.symbols.associateBy(SymbolSnapshot::id)
    val symbolsByFileId: Map<String, List<SymbolSnapshot>> = snapshot.symbols.groupBy(SymbolSnapshot::fileId)
    val referenceById: Map<String, ReferenceSnapshot> = snapshot.references.associateBy(ReferenceSnapshot::id)
    val referencesBySourceFileId: Map<String, List<ReferenceSnapshot>> =
        snapshot.references.groupBy(ReferenceSnapshot::sourceFileId)
    val relationshipById: Map<String, RelationshipSnapshot> =
        snapshot.relationships.associateBy(RelationshipSnapshot::id)
    val relationshipsBySourceFileId: Map<String, List<RelationshipSnapshot>> =
        snapshot.relationships.groupBy { relationship ->
            referenceById.getValue(relationship.referenceId).sourceFileId
        }
    val cyclesByMemberSymbolId: Map<String, List<CycleSnapshot>> =
        snapshot.relationshipCycles.indexByDistinctKeys { cycle -> cycle.symbolIds.dropLast(1) }
    val projectAnalysisByProjectId: Map<String, ProjectAnalysisSnapshot> =
        snapshot.projectAnalyses.associateBy(ProjectAnalysisSnapshot::projectId)

    val buildNames: Set<String> = buildByName.keys
    val projectPaths: Set<String> = projectById.values.mapTo(mutableSetOf(), ProjectSnapshot::path)
    val sourceSetNames: Set<String> = sourceSetById.values.mapTo(mutableSetOf(), SourceSetSnapshot::name)
}

private fun <Key, Value> Iterable<Value>.indexByDistinctKeys(
    keys: (Value) -> Iterable<Key>,
): Map<Key, List<Value>> {
    val index = linkedMapOf<Key, MutableList<Value>>()
    forEach { value ->
        keys(value).toSet().forEach { key -> index.getOrPut(key, ::mutableListOf).add(value) }
    }
    return index.mapValues { (_, values) -> values.toList() }
}
