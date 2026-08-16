package zone.clanker.docx.web.application

internal sealed interface AtlasCommandResult {
    data object Completed : AtlasCommandResult

    data class SelectProject(
        val projectId: String,
    ) : AtlasCommandResult

    data class SelectBuild(
        val buildId: String?,
    ) : AtlasCommandResult

    data class Rejected(
        val reason: String,
    ) : AtlasCommandResult
}
