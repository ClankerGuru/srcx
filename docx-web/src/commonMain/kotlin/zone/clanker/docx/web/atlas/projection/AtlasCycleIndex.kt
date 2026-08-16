package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.ArchitectureComponentSnapshot
import zone.clanker.report.model.ProjectGraphShard

internal class AtlasCycleIndex(
    project: ProjectGraphShard,
) {
    private val analysis = project.analysis
    private val analysisComponents = analysis?.components.orEmpty()
    private val analysisComponentsById = analysisComponents.associateBy(ArchitectureComponentSnapshot::id)
    private val analysisCycleComponentIds =
        analysis?.cycles.orEmpty().flatMapTo(mutableSetOf()) { cycle -> cycle.componentIds }

    val observedSymbolIds = project.cycles.flatMapTo(mutableSetOf()) { cycle -> cycle.symbolIds }
    val observedRelationshipIds = project.cycles.flatMapTo(mutableSetOf()) { cycle -> cycle.relationshipIds }
    val analysisSymbolIds =
        analysisComponents
            .filter { component -> component.id in analysisCycleComponentIds }
            .mapNotNullTo(mutableSetOf(), ArchitectureComponentSnapshot::symbolId)
    val analysisFileIds =
        analysisComponents
            .filter { component -> component.id in analysisCycleComponentIds }
            .mapNotNullTo(mutableSetOf(), ArchitectureComponentSnapshot::sourceFileId)
    val analysisNodeIds =
        analysisComponents
            .filter { component -> component.id in analysisCycleComponentIds }
            .mapNotNullTo(mutableSetOf()) { component -> component.symbolId ?: component.sourceFileId }
    val analysisSymbolSteps = analysisSteps(ArchitectureComponentSnapshot::symbolId)
    val analysisFileSteps = analysisSteps(ArchitectureComponentSnapshot::sourceFileId)

    private fun analysisSteps(selector: (ArchitectureComponentSnapshot) -> String?): Set<Pair<String, String>> =
        analysis?.cycles.orEmpty().flatMapTo(mutableSetOf()) { cycle ->
            cycle.componentIds.zipWithNext().mapNotNull { (sourceComponentId, targetComponentId) ->
                val sourceId = analysisComponentsById[sourceComponentId]?.let(selector)
                val targetId = analysisComponentsById[targetComponentId]?.let(selector)
                if (sourceId == null || targetId == null) null else sourceId to targetId
            }
        }
}
