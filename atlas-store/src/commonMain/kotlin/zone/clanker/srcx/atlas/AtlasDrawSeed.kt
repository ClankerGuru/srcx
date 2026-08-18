package zone.clanker.srcx.atlas

/** Typed 42-seed handed to the D3 adapter. Built from sqlite, never from an HTML island. */
data class AtlasDrawSeed(
    val workspace: String,
    val nodeLimit: Int,
    val fileNodes: List<AtlasDrawFileNode>,
    val fileEdges: List<AtlasDrawEdge>,
    val nodes: List<AtlasDrawSymbolNode>,
    val builds: List<String>,
)

data class AtlasDrawFileNode(
    val id: String,
    val name: String,
    val path: String,
    val build: String,
    val project: String,
    val sourceSet: String,
    val symbols: List<AtlasDrawFileSymbol>,
    val important: Boolean,
    val relationshipRecordCount: Int,
    val content: String,
)

data class AtlasDrawFileSymbol(
    val id: String,
    val name: String,
    val kind: String,
    val declarationSemantic: String,
    val line: Int,
)

data class AtlasDrawSymbolNode(
    val id: String,
    val name: String,
    val build: String,
    val project: String,
    val sourceSet: String,
    val file: String,
    val line: Int,
    val kind: String,
)

data class AtlasDrawEdge(
    val id: String,
    val source: String,
    val target: String,
    val kind: String,
    val recordCount: Int,
)

object AtlasDrawSeedRenderer {
    fun from(seed: AtlasStoreContents): AtlasDrawSeed {
        val files = seed.nodes.filter { node -> node.entity == AtlasStoreSchema.ENTITY_FILE }
        val symbols = seed.nodes.filter { node -> node.entity == AtlasStoreSchema.ENTITY_SYMBOL }
        val fileIds = files.map { node -> node.id }.toSet()
        return AtlasDrawSeed(
            workspace = seed.meta.workspace,
            nodeLimit = seed.meta.seedLimit,
            fileNodes = files.map { node -> fileNode(node) },
            fileEdges =
                seed.relationships
                    .filter { relationship ->
                        relationship.sourceId in fileIds && relationship.targetId in fileIds
                    }.map { edge ->
                        AtlasDrawEdge(
                            id = edge.id,
                            source = edge.sourceId,
                            target = edge.targetId,
                            kind = edge.kind,
                            recordCount = edge.recordCount,
                        )
                    },
            nodes =
                symbols.map { node ->
                    AtlasDrawSymbolNode(
                        id = node.id,
                        name = node.name,
                        build = node.build,
                        project = node.project,
                        sourceSet = node.sourceSet,
                        file = node.path,
                        line = node.line,
                        kind = node.kind,
                    )
                },
            builds = files.map { node -> node.build }.distinct(),
        )
    }

    private fun fileNode(node: AtlasNodeRecord): AtlasDrawFileNode {
        val payload = runCatching { AtlasCborRenderer.decodeNode(node.payload) }.getOrNull()
        val records =
            (payload?.incomingRecordCount ?: 0) +
                (payload?.outgoingRecordCount ?: 0) +
                (payload?.internalRecordCount ?: 0)
        return AtlasDrawFileNode(
            id = node.id,
            name = node.name,
            path = node.path,
            build = node.build,
            project = node.project,
            sourceSet = node.sourceSet,
            symbols =
                (payload?.symbols ?: emptyList()).map { symbol ->
                    AtlasDrawFileSymbol(
                        id = symbol.id,
                        name = symbol.name,
                        kind = symbol.kind,
                        declarationSemantic = symbol.semantic,
                        line = symbol.line,
                    )
                },
            important = payload?.important == true,
            relationshipRecordCount = records,
            content = payload?.content.orEmpty(),
        )
    }
}
