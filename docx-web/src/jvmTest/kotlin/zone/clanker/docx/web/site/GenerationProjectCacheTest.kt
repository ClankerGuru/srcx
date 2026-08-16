package zone.clanker.docx.web.site

import zone.clanker.report.model.ProjectGraphShard
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class GenerationProjectCacheTest {
    @Test
    fun evictsTheLeastRecentlyUsedShardAtItsByteBound() {
        val cache = GenerationProjectCache(maxEncodedBytes = 20)
        val first = project("project:first")
        val second = project("project:second")
        val third = project("project:third")

        cache.put(GENERATION, resource(first, 10))
        cache.put(GENERATION, resource(second, 10))
        assertSame(first, cache.get(GENERATION, first.projectId))
        cache.put(GENERATION, resource(third, 10))

        assertNull(cache.get(GENERATION, second.projectId))
        assertSame(first, cache.get(GENERATION, first.projectId))
        assertSame(third, cache.get(GENERATION, third.projectId))
    }

    @Test
    fun retainsOneShardThatIsLargerThanTheByteBound() {
        val cache = GenerationProjectCache(maxEncodedBytes = 10)
        val oversized = project("project:oversized")

        cache.put(GENERATION, resource(oversized, 25))

        assertSame(oversized, cache.get(GENERATION, oversized.projectId))
    }

    @Test
    fun discardsCachedProjectsWhenTheWorkspaceGenerationChanges() {
        val cache = GenerationProjectCache()
        val project = project("project:fixture")

        cache.put("generation:one", resource(project, 10))

        assertNull(cache.get("generation:two", project.projectId))
    }

    private fun resource(
        project: ProjectGraphShard,
        encodedByteSize: Long,
    ): LoadedProjectResource = LoadedProjectResource(project, encodedByteSize)

    private fun project(projectId: String): ProjectGraphShard =
        ProjectGraphShard(
            projectId = projectId,
            sourceSets = emptyList(),
            files = emptyList(),
            symbols = emptyList(),
            relationships = emptyList(),
        )

    private companion object {
        const val GENERATION: String = "generation:fixture"
    }
}
