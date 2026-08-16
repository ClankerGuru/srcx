package zone.clanker.docx.web.site

import zone.clanker.report.model.ProjectGraphShard

/** A decoded project shard paired with the size of its original encoded transport. */
internal data class LoadedProjectResource(
    val shard: ProjectGraphShard,
    val encodedByteSize: Long,
) {
    init {
        require(encodedByteSize > 0) { "Encoded project size must be positive" }
    }
}

/** Generation-local LRU bounded by encoded transport bytes rather than project count. */
internal class GenerationProjectCache(
    private val maxEncodedBytes: Long = DEFAULT_MAX_ENCODED_BYTES,
) {
    private var activeGenerationId: String? = null
    private var retainedEncodedBytes: Long = 0
    private val resources = linkedMapOf<String, LoadedProjectResource>()

    init {
        require(maxEncodedBytes > 0) { "Project cache byte bound must be positive" }
    }

    fun get(
        generationId: String,
        projectId: String,
    ): ProjectGraphShard? {
        selectGeneration(generationId)
        require(projectId.isNotBlank()) { "Project identity must not be blank" }
        val resource = resources.remove(projectId) ?: return null
        resources[projectId] = resource
        return resource.shard
    }

    fun put(
        generationId: String,
        resource: LoadedProjectResource,
    ) {
        selectGeneration(generationId)
        require(resource.shard.projectId.isNotBlank()) { "Project identity must not be blank" }
        remove(resource.shard.projectId)
        resources[resource.shard.projectId] = resource
        retainedEncodedBytes += resource.encodedByteSize
        evictLeastRecentlyUsed()
    }

    private fun evictLeastRecentlyUsed() {
        while (retainedEncodedBytes > maxEncodedBytes && resources.size > 1) {
            remove(resources.keys.first())
        }
    }

    private fun remove(projectId: String) {
        resources.remove(projectId)?.let { removed ->
            retainedEncodedBytes -= removed.encodedByteSize
        }
    }

    private fun selectGeneration(generationId: String) {
        require(generationId.isNotBlank()) { "Workspace generation identity must not be blank" }
        if (activeGenerationId != generationId) {
            activeGenerationId = generationId
            resources.clear()
            retainedEncodedBytes = 0
        }
    }

    private companion object {
        const val DEFAULT_MAX_ENCODED_BYTES: Long = 64L * 1024L * 1024L
    }
}
