package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.ArchitectureComponentSnapshot
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceSummaryShard

internal class AtlasProjectionContext(
    val summary: WorkspaceSummaryShard,
    val project: ProjectGraphShard,
) {
    val buildsById = summary.builds.associateBy(BuildSnapshot::id)
    val projectsById = summary.projects.associateBy(ProjectSnapshot::id)
    val sourceSetsById = project.sourceSets.associateBy(SourceSetSnapshot::id)
    val filesById = project.files.associateBy(SourceFileSnapshot::id)
    val filesByPath = project.files.associateBy(SourceFileSnapshot::projectRelativePath)
    val symbolsById = project.symbols.associateBy(SymbolSnapshot::id)
    val symbolsByFileId = project.symbols.groupBy(SymbolSnapshot::fileId)
    val referencesById = project.references.associateBy(ReferenceSnapshot::id)
    val relationshipsById = project.relationships.associateBy(RelationshipSnapshot::id)
    val analysisComponentsById =
        project.analysis
            ?.components
            .orEmpty()
            .associateBy(ArchitectureComponentSnapshot::id)
    val findings =
        (project.findings + project.analysis?.findings.orEmpty())
            .distinctBy(FindingSnapshot::id)
            .sortedBy(FindingSnapshot::id)
    val cycles = AtlasCycleIndex(project)

    init {
        require(project.projectId in projectsById) { "Atlas project must exist in the workspace summary" }
    }

    fun eligibleRelationships(request: AtlasProjectFrameRequest): List<RelationshipSnapshot> =
        project.relationships.filter { relationship ->
            relationship.kind != RelationshipKind.IMPORT && relationship.kind in request.allowedKinds
        }

    fun problemNodeIds(): List<String> =
        buildSet {
            findings.forEach { finding ->
                finding.fileId
                    ?.takeIf(filesById::containsKey)
                    ?.let(::add)
                finding.filePath
                    ?.let(filesByPath::get)
                    ?.id
                    ?.let(::add)
                finding.symbolIds.filter(symbolsById::containsKey).forEach(::add)
                finding.resolvedComponentIds
                    .mapNotNull(analysisComponentsById::get)
                    .mapNotNull(::componentNodeId)
                    .forEach(::add)
            }
        }.sorted()

    fun cycleNodeIds(): List<String> =
        (cycles.observedSymbolIds + cycles.analysisNodeIds)
            .filter { nodeId -> nodeId in filesById || nodeId in symbolsById }
            .sorted()

    fun fileEndpoints(relationship: RelationshipSnapshot): Pair<String, String>? {
        val sourceId = referencesById[relationship.referenceId]?.sourceFileId
        val targetId = symbolsById[relationship.targetSymbolId]?.fileId
        return if (sourceId == null || targetId == null) null else sourceId to targetId
    }

    fun symbolEndpoints(relationship: RelationshipSnapshot): Pair<String, String>? =
        relationship.sourceSymbolId?.let { sourceId -> sourceId to relationship.targetSymbolId }

    fun ownership(file: SourceFileSnapshot): AtlasOwnership {
        val sourceSet = sourceSetsById.getValue(file.sourceSetId)
        val ownerProject = projectsById.getValue(sourceSet.projectId)
        return AtlasOwnership(sourceSet, ownerProject, buildsById.getValue(ownerProject.buildId))
    }

    fun buildIdForNode(id: String): String {
        val file = filesById[id] ?: symbolsById[id]?.let { filesById.getValue(it.fileId) }
        return ownership(requireNotNull(file)).build.id
    }

    fun findingIdsForFile(fileId: String): List<String> =
        findings
            .filter { finding ->
                finding.fileId == fileId ||
                    finding.filePath == filesById[fileId]?.projectRelativePath ||
                    finding.symbolIds.any { symbolId -> symbolsById[symbolId]?.fileId == fileId } ||
                    finding.resolvedComponentIds
                        .mapNotNull(analysisComponentsById::get)
                        .any { component ->
                            component.sourceFileId == fileId ||
                                component.symbolId?.let { symbolId -> symbolsById[symbolId]?.fileId } == fileId
                        }
            }.map(FindingSnapshot::id)

    fun findingIdsForSymbol(symbolId: String): List<String> =
        findings
            .filter { finding ->
                symbolId in finding.symbolIds ||
                    finding.resolvedComponentIds
                        .mapNotNull(analysisComponentsById::get)
                        .any { component -> component.symbolId == symbolId }
            }.map(FindingSnapshot::id)

    private fun componentNodeId(component: ArchitectureComponentSnapshot): String? =
        component.symbolId?.takeIf(symbolsById::containsKey)
            ?: component.sourceFileId?.takeIf(filesById::containsKey)
}

internal data class AtlasOwnership(
    val sourceSet: SourceSetSnapshot,
    val project: ProjectSnapshot,
    val build: BuildSnapshot,
)
