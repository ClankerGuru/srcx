package zone.clanker.docx.web.application

internal data class PendingBuildCommand(
    val revision: Int,
    val buildId: String?,
)
