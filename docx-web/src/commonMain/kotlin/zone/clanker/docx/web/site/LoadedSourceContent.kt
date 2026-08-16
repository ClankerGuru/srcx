package zone.clanker.docx.web.site

data class LoadedSourceContent(
    val fileId: String,
    val content: String,
    val encodedByteSize: Long,
    val contentHash: String? = null,
) {
    init {
        require(fileId.isNotBlank()) { "Loaded source file identity must not be blank" }
        require(encodedByteSize >= 0) { "Loaded source byte size must not be negative" }
        require(contentHash == null || contentHash.isNotBlank()) { "Loaded source hash must not be blank" }
    }
}
