package zone.clanker.srcx.atlas.site

import zone.clanker.srcx.atlas.AtlasDrawFileNode

/** FIND / kind / used-at-least filter for the 42 seed. */
object AtlasVisibleNodes {
    fun matches(
        node: AtlasDrawFileNode,
        query: String,
        kinds: Set<String>,
        usedAtLeast: Int,
    ): Boolean {
        if (usedAtLeast > 0 && node.relationshipRecordCount < usedAtLeast) return false
        val needle = query.trim().lowercase()
        if (needle.isNotEmpty()) {
            val hay = (node.name + " " + node.path).lowercase()
            if (!hay.contains(needle)) return false
        }
        if (kinds.isEmpty()) return true
        return nodeMatchesKinds(node, kinds)
    }

    private fun nodeMatchesKinds(
        node: AtlasDrawFileNode,
        kinds: Set<String>,
    ): Boolean {
        val name = node.name.lowercase()
        if ("file" in kinds) return true
        if ("kotlin" in kinds && name.endsWith(".kt")) return true
        if ("java" in kinds && name.endsWith(".java")) return true
        if ("gradle-kts" in kinds && name.endsWith(".kts")) return true
        return node.symbols.any { symbol ->
            symbol.kind.lowercase() in kinds || symbol.declarationSemantic.lowercase() in kinds
        }
    }
}
