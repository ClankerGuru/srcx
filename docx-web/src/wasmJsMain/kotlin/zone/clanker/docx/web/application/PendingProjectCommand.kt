package zone.clanker.docx.web.application

internal data class PendingProjectCommand(
    val revision: Int,
    val projectId: String,
)
