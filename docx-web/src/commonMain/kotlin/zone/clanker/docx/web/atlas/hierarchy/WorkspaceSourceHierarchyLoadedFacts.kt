package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceSummaryShard

internal class WorkspaceSourceHierarchyLoadedFacts(
    summary: WorkspaceSummaryShard,
    loadedProjects: List<ProjectGraphShard>,
) {
    val projects: List<ProjectGraphShard> = loadedProjects.sortedBy(ProjectGraphShard::projectId)
    val sourceSetsById: Map<String, SourceSetSnapshot> =
        uniqueWorkspaceFacts(projects.flatMap(ProjectGraphShard::sourceSets), SourceSetSnapshot::id)
    val filesById: Map<String, SourceFileSnapshot> =
        uniqueWorkspaceFacts(projects.flatMap(ProjectGraphShard::files), SourceFileSnapshot::id)
    val symbolsById: Map<String, SymbolSnapshot> =
        uniqueWorkspaceFacts(projects.flatMap(ProjectGraphShard::symbols), SymbolSnapshot::id)
    val referencesById: Map<String, ReferenceSnapshot> =
        uniqueWorkspaceFacts(projects.flatMap(ProjectGraphShard::references), ReferenceSnapshot::id)
    val relationshipsById: Map<String, RelationshipSnapshot> =
        uniqueWorkspaceFacts(projects.flatMap(ProjectGraphShard::relationships), RelationshipSnapshot::id)
    val findingsById: Map<String, FindingSnapshot> =
        uniqueWorkspaceFacts(projects.flatMap(ProjectGraphShard::findings), FindingSnapshot::id)
    val cyclesById: Map<String, CycleSnapshot> =
        uniqueWorkspaceFacts(projects.flatMap(ProjectGraphShard::cycles), CycleSnapshot::id)
    val findingProjectIdsById: Map<String, String> = findingProjectIds()

    init {
        require(projects.map(ProjectGraphShard::projectId).distinct().size == projects.size) {
            "Loaded workspace source-hierarchy shards must have unique project IDs"
        }
        val knownProjectIds = summary.projects.mapTo(mutableSetOf()) { project -> project.id }
        require(projects.all { project -> project.projectId in knownProjectIds }) {
            "Loaded workspace source-hierarchy shards must belong to summary projects"
        }
    }

    private fun findingProjectIds(): Map<String, String> {
        val owners = mutableMapOf<String, String>()
        projects.forEach { project ->
            project.findings.forEach { finding ->
                val previous = owners.put(finding.id, project.projectId)
                require(previous == null || previous == project.projectId) {
                    "Workspace source-hierarchy finding belongs to multiple projects: ${finding.id}"
                }
            }
        }
        return owners.keys.sorted().associateWith(owners::getValue)
    }
}

private fun <T : Any> uniqueWorkspaceFacts(
    values: List<T>,
    id: (T) -> String,
): Map<String, T> {
    val unique = mutableMapOf<String, T>()
    values.sortedBy(id).forEach { value ->
        val valueId = id(value)
        val previous = unique.put(valueId, value)
        require(previous == null || previous == value) {
            "Conflicting workspace source-hierarchy evidence for ID: $valueId"
        }
    }
    return unique
}
