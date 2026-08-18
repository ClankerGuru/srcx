package zone.clanker.srcx.atlas

/** Encodes the sqlite seed as the object handed to the D3 adapter. Not an HTML island. */
object AtlasSeedJsonRenderer {
    fun encode(seed: AtlasStoreContents): String {
        val files = seed.nodes.filter { node -> node.entity == AtlasStoreSchema.ENTITY_FILE }
        val symbols = seed.nodes.filter { node -> node.entity == AtlasStoreSchema.ENTITY_SYMBOL }
        val fileIds = files.map { node -> node.id }.toSet()
        val fileEdges =
            seed.relationships.filter { relationship ->
                relationship.sourceId in fileIds && relationship.targetId in fileIds
            }
        val fileNodes = files.joinToString(prefix = "[", postfix = "]") { node -> encodeFileNode(node) }
        val edges = fileEdges.joinToString(prefix = "[", postfix = "]") { edge -> encodeEdge(edge) }
        val symbolNodes = symbols.joinToString(prefix = "[", postfix = "]") { node -> encodeSymbol(node) }
        val builds =
            files.map { node -> node.build }.distinct().joinToString(prefix = "[", postfix = "]") { name ->
                """{"name":${jsonString(name)}}"""
            }
        return """{"workspace":${jsonString(seed.meta.workspace)},"defaultView":"files",""" +
            """"nodeLimit":${seed.meta.seedLimit},"fileNodeLimit":${seed.meta.seedLimit},""" +
            """"fileNodes":$fileNodes,"fileEdges":$edges,"nodes":$symbolNodes,"edges":[],""" +
            """"builds":$builds,"sourceFiles":$fileNodes,""" +
            """"availableNodes":[],"availableEdges":[],"availableFileNodes":[],"availableFileEdges":[]}"""
    }

    private fun encodeFileNode(node: AtlasNodeRecord): String {
        val payload = runCatching { AtlasCborRenderer.decodeNode(node.payload) }.getOrNull()
        val symbolsJson =
            (payload?.symbols ?: emptyList()).joinToString(prefix = "[", postfix = "]") { symbol ->
                """{"id":${jsonString(symbol.id)},"name":${jsonString(symbol.name)},"kind":${jsonString(symbol.kind)},""" +
                    """"declarationSemantic":${jsonString(symbol.semantic)},"line":${symbol.line}}"""
            }
        val records =
            (payload?.incomingRecordCount ?: 0) +
                (payload?.outgoingRecordCount ?: 0) +
                (payload?.internalRecordCount ?: 0)
        return """{"id":${jsonString(node.id)},"name":${jsonString(node.name)},"path":${jsonString(node.path)},""" +
            """"build":${jsonString(node.build)},"project":${jsonString(node.project)},""" +
            """"sourceSet":${jsonString(node.sourceSet)},"symbols":$symbolsJson,""" +
            """"important":${payload?.important == true},""" +
            """"relationshipRecordCount":$records,""" +
            """"content":${jsonString(payload?.content ?: "")}}"""
    }

    private fun encodeSymbol(node: AtlasNodeRecord): String =
        """{"id":${jsonString(node.id)},"name":${jsonString(node.name)},"build":${jsonString(node.build)},""" +
            """"project":${jsonString(node.project)},"sourceSet":${jsonString(node.sourceSet)},""" +
            """"file":${jsonString(node.path)},"line":${node.line},"kind":${jsonString(node.kind)}}"""

    private fun encodeEdge(edge: AtlasRelationshipRecord): String =
        """{"id":${jsonString(edge.id)},"source":${jsonString(edge.sourceId)},"target":${jsonString(edge.targetId)},""" +
            """"kind":${jsonString(edge.kind)},"recordCount":${edge.recordCount}}"""

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (char.code < 0x20) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                }
            }
            append('"')
        }
}
