package zone.clanker.docx.web.state

import zone.clanker.report.model.ProjectGraphShard

/** Independent detail-evidence transaction; it never represents the selected Atlas scope. */
internal data class EvidenceProjectState(
    val requestedProjectId: String? = null,
    val project: ProjectGraphShard? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val requestRevision: Int = 0,
    val source: EvidenceSourceContentState = EvidenceSourceContentState(),
) {
    init {
        require(requestRevision >= 0) { "Evidence request revision must not be negative" }
        require(requestedProjectId == null || requestedProjectId.isNotBlank()) {
            "Evidence project identity must not be blank"
        }
        require(project == null || project.projectId == requestedProjectId) {
            "Loaded evidence must belong to the requested project"
        }
        require(error == null || error.isNotBlank()) { "Evidence load error must not be blank" }
        require(!loading || requestedProjectId != null) { "Evidence loading requires a requested project" }
        require(!loading || project == null) { "Evidence cannot be loading and loaded at the same time" }
        require(!loading || error == null) { "Evidence cannot be loading and failed at the same time" }
        require(error == null || requestedProjectId != null) { "Evidence failure requires a requested project" }
        require(project == null || error == null) { "Loaded evidence cannot also contain a failure" }
    }

    fun request(projectId: String): EvidenceProjectState {
        require(projectId.isNotBlank()) { "Evidence project identity must not be blank" }
        if (requestedProjectId == projectId && (loading || project != null)) return this
        return EvidenceProjectState(
            requestedProjectId = projectId,
            loading = true,
            requestRevision = requestRevision + 1,
        )
    }

    fun complete(
        projectId: String,
        loadedProject: ProjectGraphShard,
    ): EvidenceProjectState {
        require(loadedProject.projectId == projectId) {
            "Loaded evidence shard identity must match its request"
        }
        if (requestedProjectId != projectId) return this
        return copy(project = loadedProject, loading = false, error = null)
    }

    fun fail(
        projectId: String,
        message: String,
    ): EvidenceProjectState {
        require(message.isNotBlank()) { "Evidence load error must not be blank" }
        if (requestedProjectId != projectId) return this
        return copy(project = null, loading = false, error = message)
    }

    fun requestSource(
        projectId: String,
        fileId: String,
    ): EvidenceProjectState = copy(source = source.request(projectId, fileId))

    fun acceptSource(nextSource: EvidenceSourceContentState): EvidenceProjectState =
        if (
            source.requestedProjectId == nextSource.requestedProjectId &&
            source.requestedFileId == nextSource.requestedFileId &&
            source.requestRevision == nextSource.requestRevision
        ) {
            copy(source = nextSource)
        } else {
            this
        }
}
