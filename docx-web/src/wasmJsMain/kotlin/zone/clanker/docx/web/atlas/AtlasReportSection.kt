package zone.clanker.docx.web.atlas

internal enum class AtlasReportSection(
    val elementId: String,
) {
    ARCHITECTURE("architecture"),
    BUILDS("builds"),
    HEALTH("health"),
    FINDINGS("findings"),
}
