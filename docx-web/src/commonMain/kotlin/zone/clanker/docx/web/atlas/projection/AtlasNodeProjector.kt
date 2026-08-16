package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.AtlasNodeType
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolSnapshot

internal class AtlasNodeProjector(
    private val context: AtlasProjectionContext,
) {
    fun fileNode(
        file: SourceFileSnapshot,
        primary: Boolean,
        relationships: List<RelationshipSnapshot>,
    ): AtlasNode {
        val ownership = context.ownership(file)
        val findingIds = context.findingIdsForFile(file.id)
        val fileSymbolIds =
            context.project.symbols
                .filter { it.fileId == file.id }
                .mapTo(mutableSetOf(), SymbolSnapshot::id)
        val incident =
            relationships.filter { relationship ->
                context.fileEndpoints(relationship)?.toList()?.any(file.id::equals) == true
            }
        val internal =
            incident.count { relationship ->
                context.fileEndpoints(relationship)?.let { it.first == it.second } == true
            }
        return AtlasNode(
            id = file.id,
            type = AtlasNodeType.FILE,
            name = file.projectRelativePath.substringAfterLast('/'),
            path = file.projectRelativePath,
            buildId = ownership.build.id,
            buildName = ownership.build.name,
            projectId = ownership.project.id,
            projectPath = ownership.project.path,
            sourceSet = ownership.sourceSet.name,
            sourceFileId = file.id,
            language = file.language.label,
            kind = file.language.name,
            primary = primary,
            findingIds = findingIds,
            sourceFileFindingCount = findingIds.size,
            hasObservedCycle = fileSymbolIds.any(context.cycles.observedSymbolIds::contains),
            hasAnalysisCycle = file.id in context.cycles.analysisFileIds,
            relationshipRecordCount = incident.size,
            internalRecordCount = internal,
        )
    }

    fun symbolNode(
        symbol: SymbolSnapshot,
        primary: Boolean,
        relationships: List<RelationshipSnapshot>,
    ): AtlasNode {
        val file = context.filesById.getValue(symbol.fileId)
        val ownership = context.ownership(file)
        val findingIds = context.findingIdsForSymbol(symbol.id)
        val incident =
            relationships.filter { relationship ->
                relationship.sourceSymbolId == symbol.id || relationship.targetSymbolId == symbol.id
            }
        return AtlasNode(
            id = symbol.id,
            type = AtlasNodeType.SYMBOL,
            name = symbol.name,
            qualifiedName = symbol.qualifiedName,
            path = file.projectRelativePath,
            buildId = ownership.build.id,
            buildName = ownership.build.name,
            projectId = ownership.project.id,
            projectPath = ownership.project.path,
            sourceSet = ownership.sourceSet.name,
            sourceFileId = file.id,
            language = file.language.label,
            kind = symbol.kind.name,
            semantic = symbol.declarationSemantic,
            primary = primary,
            findingIds = findingIds,
            sourceFileFindingCount = context.findingIdsForFile(file.id).size,
            hasObservedCycle = symbol.id in context.cycles.observedSymbolIds,
            hasAnalysisCycle = symbol.id in context.cycles.analysisSymbolIds,
            relationshipRecordCount = incident.size,
            internalRecordCount = incident.count { it.sourceSymbolId == it.targetSymbolId },
        )
    }

    fun mixedNode(
        nodeId: String,
        primary: Boolean,
        relationships: List<RelationshipSnapshot>,
        endpoints: (RelationshipSnapshot) -> Pair<String, String>,
    ): AtlasNode {
        val incident = relationships.filter { relationship -> nodeId in endpoints(relationship).toList() }
        val internal = incident.count { relationship -> endpoints(relationship).let { it.first == it.second } }
        val node =
            context.filesById[nodeId]?.let { file -> fileNode(file, primary, relationships = emptyList()) }
                ?: context.symbolsById.getValue(nodeId).let { symbol ->
                    symbolNode(symbol, primary, relationships = emptyList())
                }
        return node.copy(
            relationshipRecordCount = incident.size,
            internalRecordCount = internal,
        )
    }
}
