package zone.clanker.docx.web.state

internal data class EvidenceSourceContentState(
    val requestedProjectId: String? = null,
    val requestedFileId: String? = null,
    val content: String? = null,
    val contentHash: String? = null,
    val encodedByteSize: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val requestRevision: Int = 0,
) {
    init {
        require(requestRevision >= 0) { "Source-evidence request revision must not be negative" }
        require((requestedProjectId == null) == (requestedFileId == null)) {
            "Source-evidence project and file identities must be requested together"
        }
        require(!loading || requestedProjectId != null) { "Source-evidence loading requires a target" }
        require(!loading || content == null) { "Source evidence cannot be loading and loaded together" }
        require(!loading || error == null) { "Source evidence cannot be loading and failed together" }
        require(content == null || encodedByteSize != null) { "Loaded source evidence requires its encoded size" }
        require(content == null || error == null) { "Loaded source evidence cannot also contain a failure" }
        require(encodedByteSize == null || encodedByteSize >= 0) { "Source-evidence byte size must not be negative" }
        require(contentHash == null || contentHash.isNotBlank()) { "Source-evidence hash must not be blank" }
        require(error == null || error.isNotBlank()) { "Source-evidence error must not be blank" }
    }

    fun request(
        projectId: String,
        fileId: String,
    ): EvidenceSourceContentState {
        require(projectId.isNotBlank()) { "Source-evidence project identity must not be blank" }
        require(fileId.isNotBlank()) { "Source-evidence file identity must not be blank" }
        if (matches(projectId, fileId) && (loading || content != null)) return this
        return EvidenceSourceContentState(
            requestedProjectId = projectId,
            requestedFileId = fileId,
            loading = true,
            requestRevision = requestRevision + 1,
        )
    }

    fun complete(
        projectId: String,
        fileId: String,
        sourceContent: String,
        sourceContentHash: String?,
        sourceEncodedByteSize: Long,
    ): EvidenceSourceContentState {
        if (!matches(projectId, fileId)) return this
        return copy(
            content = sourceContent,
            contentHash = sourceContentHash,
            encodedByteSize = sourceEncodedByteSize,
            loading = false,
            error = null,
        )
    }

    fun fail(
        projectId: String,
        fileId: String,
        message: String,
    ): EvidenceSourceContentState {
        require(message.isNotBlank()) { "Source-evidence error must not be blank" }
        if (!matches(projectId, fileId)) return this
        return copy(
            content = null,
            contentHash = null,
            encodedByteSize = null,
            loading = false,
            error = message,
        )
    }

    fun matches(
        projectId: String,
        fileId: String,
    ): Boolean = requestedProjectId == projectId && requestedFileId == fileId

    fun contentFor(
        projectId: String,
        fileId: String,
    ): String? = content.takeIf { matches(projectId, fileId) }

    fun errorFor(
        projectId: String,
        fileId: String,
    ): String? = error.takeIf { matches(projectId, fileId) }
}
