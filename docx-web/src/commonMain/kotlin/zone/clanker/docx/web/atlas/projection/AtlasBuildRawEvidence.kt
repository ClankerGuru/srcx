package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.ArchitectureCycleSnapshot
import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot

internal data class AtlasBuildRawEvidence(
    val sourceFilesById: Map<String, SourceFileSnapshot>,
    val relationshipsById: Map<String, RelationshipSnapshot>,
    val observedRelationshipIds: Set<String>,
)

internal fun atlasBuildRawEvidence(projects: List<ProjectGraphShard>): AtlasBuildRawEvidence {
    distinctRecords(projects.flatMap(ProjectGraphShard::sourceSets), SourceSetSnapshot::id, "source set")
    val sourceFiles = mergeSourceFileRecords(projects)
    distinctRecords(projects.flatMap(ProjectGraphShard::symbols), SymbolSnapshot::id, "symbol")
    distinctRecords(projects.flatMap(ProjectGraphShard::references), ReferenceSnapshot::id, "reference")
    val relationships =
        distinctRecords(
            projects.flatMap(ProjectGraphShard::relationships),
            RelationshipSnapshot::id,
            "relationship",
        )
    validateEvidenceRecords(projects)
    return AtlasBuildRawEvidence(
        sourceFilesById = sourceFiles,
        relationshipsById = relationships,
        observedRelationshipIds =
            projects
                .flatMap(ProjectGraphShard::cycles)
                .flatMapTo(mutableSetOf(), CycleSnapshot::relationshipIds),
    )
}

private data class AtlasBuildSourceFileRecord(
    val owned: Boolean,
    val file: SourceFileSnapshot,
)

private fun mergeSourceFileRecords(projects: List<ProjectGraphShard>): Map<String, SourceFileSnapshot> =
    projects
        .flatMap { project ->
            project.files.map { file ->
                AtlasBuildSourceFileRecord(file.id in project.ownedFileIds, file)
            }
        }.groupBy { record -> record.file.id }
        .entries
        .sortedBy(Map.Entry<String, List<AtlasBuildSourceFileRecord>>::key)
        .associate { (fileId, records) -> fileId to mergeSourceFileRecord(fileId, records) }

private fun mergeSourceFileRecord(
    fileId: String,
    records: List<AtlasBuildSourceFileRecord>,
): SourceFileSnapshot {
    require(records.map { record -> record.file.copy(content = null) }.distinct().size == 1) {
        "Duplicate Atlas build source file evidence must agree except for projected content for ID $fileId"
    }
    val owners = records.filter(AtlasBuildSourceFileRecord::owned)
    require(owners.size <= 1) { "Atlas build source file $fileId must have at most one owning project shard" }
    owners.singleOrNull()?.let { owner -> return owner.file }
    val closureFiles = records.map(AtlasBuildSourceFileRecord::file).distinct()
    require(closureFiles.size == 1) {
        "Unowned Atlas build source file evidence must agree for ID $fileId"
    }
    return closureFiles.single()
}

private fun validateEvidenceRecords(projects: List<ProjectGraphShard>) {
    distinctRecords(
        projects.flatMap { project -> project.findings + project.analysis?.findings.orEmpty() },
        FindingSnapshot::id,
        "finding",
    )
    distinctRecords(projects.flatMap(ProjectGraphShard::cycles), CycleSnapshot::id, "observed cycle")
    distinctRecords(
        projects.flatMap { project -> project.analysis?.cycles.orEmpty() },
        ArchitectureCycleSnapshot::id,
        "analysis cycle",
    )
}

private fun <T> distinctRecords(
    records: List<T>,
    id: (T) -> String,
    label: String,
): Map<String, T> {
    records.groupBy(id).forEach { (recordId, duplicates) ->
        require(duplicates.distinct().size == 1) {
            "Duplicate Atlas build $label evidence must agree for ID $recordId"
        }
    }
    return records.sortedBy(id).distinctBy(id).associateBy(id)
}
