package zone.clanker.docx.web.atlas.projection

enum class AtlasRelationshipDirection {
    ANY,
    OUTGOING,
    INCOMING,
}

data class AtlasRelationshipCountFilter(
    val moreThan: Int,
    val direction: AtlasRelationshipDirection = AtlasRelationshipDirection.ANY,
) {
    init {
        require(moreThan >= 0) { "Atlas relationship-count threshold must not be negative" }
    }
}
