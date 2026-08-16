package zone.clanker.docx.index.performance

import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphNodePresentation
import zone.clanker.report.model.WorkspaceGraphNodeRole
import java.nio.file.Files
import java.nio.file.Path

internal class WorkspaceAtlasPerformanceProfile(
    private val siteRoot: Path,
    private val reportDirectory: Path,
    private val sampleCount: Int,
) {
    private val recorder = PerformanceRecorder()
    private val memory = mutableListOf<MemorySnapshot>()

    fun run() {
        require(sampleCount in MINIMUM_SAMPLE_COUNT..MAXIMUM_SAMPLE_COUNT) {
            "Performance sample count must be between $MINIMUM_SAMPLE_COUNT and $MAXIMUM_SAMPLE_COUNT"
        }
        require(Files.isDirectory(siteRoot)) { "Repository-scale site does not exist: $siteRoot" }
        Files.createDirectories(reportDirectory)
        memory += memorySnapshot("baseline")

        val staticProbe = StaticAtlasPerformanceProbe(siteRoot)
        requireRepositoryScaleProfile(staticProbe.scaleProfile)
        measureInitialMap(staticProbe)
        measureWarmStaticProjection(staticProbe)
        measureStaticProjectDrills(staticProbe)
        val staticParitySlice = staticProbe.warmProjectSlice().also(::requireBoundedSlice)
        val staticDefaultSlice = staticProbe.defaultWorkspaceSlice().also(::requireBoundedSlice)
        val staticHardSlice = staticProbe.hardWorkspaceSlice().also(::requireBoundedSlice)
        memory += memorySnapshot("after_static_queries")

        val database = reportDirectory.resolve("workspace-atlas.sqlite")
        val liveResults =
            LiveAtlasPerformanceProbe(database, siteRoot, staticProbe.summary.workspace.id).use { liveProbe ->
                measureLiveIndex(liveProbe, staticProbe)
            }
        memory += memorySnapshot("after_live_queries")
        requireVisibleParity(staticParitySlice, liveResults.paritySlice)

        val result =
            WorkspaceAtlasPerformanceReport(
                profile = staticProbe.scaleProfile,
                samples = recorder.samples,
                summaries = METRICS.map(recorder::summary),
                artifacts = artifactSizes(siteRoot),
                payloads = staticProbe.payloadSizes(staticParitySlice),
                memory = memory.toList(),
                staticDefaultSlice = staticDefaultSlice,
                staticHardSlice = staticHardSlice,
                liveDefaultSlice = liveResults.defaultSlice,
                liveHardSlice = liveResults.hardSlice,
                paritySlice = staticParitySlice,
                databaseBytes = databaseBytes(database),
            )
        writeWorkspaceAtlasPerformanceReport(reportDirectory, result)
    }

    private fun measureInitialMap(probe: StaticAtlasPerformanceProbe) {
        repeat(sampleCount) { iteration ->
            val map = recorder.measure(STATIC_INITIAL_MAP_METRIC, iteration, probe::firstUsefulMapComponent)
            require(map.summary.projects.size == probe.scaleProfile.projects)
            require(map.atlas.totalNodeCount == probe.scaleProfile.files)
        }
    }

    private fun measureWarmStaticProjection(probe: StaticAtlasPerformanceProbe) {
        repeat(WARMUP_COUNT) { probe.warmProjectSlice() }
        repeat(sampleCount) { iteration ->
            recorder.measure(STATIC_QUERY_METRIC, iteration, probe::warmProjectSlice).also(::requireBoundedSlice)
        }
    }

    private fun measureStaticProjectDrills(probe: StaticAtlasPerformanceProbe) {
        repeat(sampleCount) { iteration ->
            recorder
                .measure(STATIC_DRILL_METRIC, iteration) { probe.projectDrill(iteration) }
                .also(::requireBoundedSlice)
        }
    }

    private fun measureLiveIndex(
        liveProbe: LiveAtlasPerformanceProbe,
        staticProbe: StaticAtlasPerformanceProbe,
    ): LivePerformanceResults {
        val indexResult = recorder.measure(LIVE_IMPORT_METRIC, 0, liveProbe::importSite)
        require(indexResult.generation.buildCount == staticProbe.scaleProfile.builds)
        require(indexResult.generation.projectCount == staticProbe.scaleProfile.projects)
        memory += memorySnapshot("after_sqlite_import")

        val paritySlice =
            recorder
                .measure(LIVE_FIRST_QUERY_METRIC, 0) { liveProbe.graphSlice(staticProbe.projectRequest) }
                .also(::requireBoundedSlice)
        repeat(WARMUP_COUNT) { liveProbe.graphSlice(staticProbe.projectRequest) }
        repeat(sampleCount) { iteration ->
            recorder
                .measure(LIVE_QUERY_METRIC, iteration) { liveProbe.graphSlice(staticProbe.projectRequest) }
                .also(::requireBoundedSlice)
        }
        repeat(WARMUP_COUNT) { liveProbe.symbolSearch() }
        repeat(sampleCount) { iteration ->
            val resultCount = recorder.measure(LIVE_SEARCH_METRIC, iteration, liveProbe::symbolSearch)
            require(resultCount > 0) { "Repository-scale symbol search returned no results" }
        }
        repeat(WARMUP_COUNT) { liveProbe.relationshipSummary() }
        repeat(sampleCount) { iteration ->
            val resultCount =
                recorder.measure(
                    LIVE_RELATIONSHIP_SUMMARY_METRIC,
                    iteration,
                    liveProbe::relationshipSummary,
                )
            require(resultCount > 0) { "Repository-scale relationship summary returned no results" }
        }
        return LivePerformanceResults(
            paritySlice = paritySlice,
            defaultSlice = liveProbe.graphSlice(staticProbe.defaultWorkspaceRequest).also(::requireBoundedSlice),
            hardSlice = liveProbe.graphSlice(staticProbe.hardProjectRequest).also(::requireBoundedSlice),
        )
    }

    private fun requireRepositoryScaleProfile(profile: ScaleProfileDescriptor) {
        require(profile.profile == "repository") { "Performance proof requires the repository profile" }
        require(profile.builds == EXPECTED_BUILD_COUNT)
        require(profile.projects == EXPECTED_PROJECT_COUNT)
        require(profile.logicalLines == EXPECTED_LOGICAL_LINE_COUNT)
        require(profile.materializedLines < profile.logicalLines)
    }

    private fun requireVisibleParity(
        staticSlice: zone.clanker.report.model.WorkspaceGraphSlice,
        liveSlice: zone.clanker.report.model.WorkspaceGraphSlice,
    ) {
        require(staticSlice.target == liveSlice.target)
        require(staticSlice.viewport == liveSlice.viewport)
        require(staticSlice.content.nodes.map(::nodeShape) == liveSlice.content.nodes.map(::nodeShape))
        require(staticSlice.content.relations == liveSlice.content.relations)
        require(staticSlice.counts == liveSlice.counts)
        require(staticSlice.limits == liveSlice.limits)
    }

    private fun nodeShape(node: zone.clanker.report.model.WorkspaceGraphNode): ComparableNodeShape =
        ComparableNodeShape(
            id = node.id,
            kind = node.kind,
            parentId = node.hierarchy.parentId,
            depth = node.hierarchy.depth,
            presentation = node.presentation,
            roles = node.roles,
        )

    private fun databaseBytes(database: Path): Long {
        val parent = requireNotNull(database.parent)
        val prefix = database.fileName.toString()
        return Files.list(parent).use { paths ->
            paths
                .filter { path -> path.fileName.toString().startsWith(prefix) }
                .mapToLong(Files::size)
                .sum()
        }
    }

    private companion object {
        const val MINIMUM_SAMPLE_COUNT = 5
        const val MAXIMUM_SAMPLE_COUNT = 200
        const val WARMUP_COUNT = 5
        const val EXPECTED_BUILD_COUNT = 80
        const val EXPECTED_PROJECT_COUNT = 2_000
        const val EXPECTED_LOGICAL_LINE_COUNT = 10_000_000L
        val METRICS =
            listOf(
                STATIC_INITIAL_MAP_METRIC,
                STATIC_QUERY_METRIC,
                STATIC_DRILL_METRIC,
                LIVE_IMPORT_METRIC,
                LIVE_FIRST_QUERY_METRIC,
                LIVE_QUERY_METRIC,
                LIVE_SEARCH_METRIC,
                LIVE_RELATIONSHIP_SUMMARY_METRIC,
            )
    }
}

private data class LivePerformanceResults(
    val paritySlice: zone.clanker.report.model.WorkspaceGraphSlice,
    val defaultSlice: zone.clanker.report.model.WorkspaceGraphSlice,
    val hardSlice: zone.clanker.report.model.WorkspaceGraphSlice,
)

private data class ComparableNodeShape(
    val id: String,
    val kind: WorkspaceGraphNodeKind,
    val parentId: String?,
    val depth: Int,
    val presentation: WorkspaceGraphNodePresentation,
    val roles: List<WorkspaceGraphNodeRole>,
)

fun main(arguments: Array<String>) {
    require(arguments.size == ARGUMENT_COUNT) {
        "Workspace Atlas performance profile needs site directory, report directory, and sample count"
    }
    WorkspaceAtlasPerformanceProfile(
        siteRoot = Path.of(arguments[0]).toAbsolutePath().normalize(),
        reportDirectory = Path.of(arguments[1]).toAbsolutePath().normalize(),
        sampleCount = arguments[2].toInt(),
    ).run()
}

private const val ARGUMENT_COUNT = 3
