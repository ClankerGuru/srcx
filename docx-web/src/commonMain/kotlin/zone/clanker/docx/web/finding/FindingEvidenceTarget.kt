package zone.clanker.docx.web.finding

import zone.clanker.report.model.ArchitectureComponentSnapshot
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceDashboardFinding

internal data class FindingEvidenceTarget(
    val findingId: String,
    val lens: AtlasLens,
    val nodeId: String,
)

internal fun resolveFindingEvidenceTarget(
    item: WorkspaceDashboardFinding,
    project: ProjectGraphShard,
): FindingEvidenceTarget? {
    require(item.projectId == project.projectId) { "Finding evidence must use its owning project shard" }
    val filesById = project.files.associateBy(SourceFileSnapshot::id)
    val filesByPath = project.files.associateBy(SourceFileSnapshot::projectRelativePath)
    val symbolsById = project.symbols.associateBy(SymbolSnapshot::id)
    val componentsById =
        project.analysis
            ?.components
            .orEmpty()
            .associateBy(ArchitectureComponentSnapshot::id)
    val candidates =
        buildList {
            addAll(item.finding.symbolIds.filter(symbolsById::containsKey))
            item.finding.fileId
                ?.takeIf(filesById::containsKey)
                ?.let(::add)
            item.finding.filePath
                ?.let(filesByPath::get)
                ?.id
                ?.let(::add)
            item.finding.resolvedComponentIds
                .mapNotNull(componentsById::get)
                .mapNotNull { component ->
                    component.symbolId?.takeIf(symbolsById::containsKey)
                        ?: component.sourceFileId?.takeIf(filesById::containsKey)
                }.forEach(::add)
        }.distinct()
    if (candidates.isEmpty()) return null

    val cycleNodeIds = project.cycleNodeIds(componentsById)
    val cycleNode = candidates.firstOrNull(cycleNodeIds::contains)
    val lens = if (item.finding.componentCycle != null && cycleNode != null) AtlasLens.CYCLES else AtlasLens.PROBLEMS
    return FindingEvidenceTarget(
        findingId = item.finding.id,
        lens = lens,
        nodeId = cycleNode.takeIf { lens == AtlasLens.CYCLES } ?: candidates.first(),
    )
}

private fun ProjectGraphShard.cycleNodeIds(
    componentsById: Map<String, ArchitectureComponentSnapshot>,
): Set<String> =
    buildSet {
        cycles.flatMapTo(this) { cycle -> cycle.symbolIds }
        analysis
            ?.cycles
            .orEmpty()
            .flatMap { cycle -> cycle.componentIds }
            .mapNotNull(componentsById::get)
            .mapNotNull { component -> component.symbolId ?: component.sourceFileId }
            .forEach(::add)
    }
