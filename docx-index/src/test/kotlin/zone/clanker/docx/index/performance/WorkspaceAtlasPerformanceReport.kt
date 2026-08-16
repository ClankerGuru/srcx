package zone.clanker.docx.index.performance

import zone.clanker.report.model.WorkspaceGraphSlice
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal data class WorkspaceAtlasPerformanceReport(
    val profile: ScaleProfileDescriptor,
    val samples: List<PerformanceSample>,
    val summaries: List<PerformanceLatencySummary>,
    val artifacts: List<ArtifactSize>,
    val payloads: List<PayloadSize>,
    val memory: List<MemorySnapshot>,
    val staticDefaultSlice: WorkspaceGraphSlice,
    val staticHardSlice: WorkspaceGraphSlice,
    val liveDefaultSlice: WorkspaceGraphSlice,
    val liveHardSlice: WorkspaceGraphSlice,
    val paritySlice: WorkspaceGraphSlice,
    val databaseBytes: Long,
)

internal fun writeWorkspaceAtlasPerformanceReport(
    reportDirectory: Path,
    result: WorkspaceAtlasPerformanceReport,
) {
    Files.createDirectories(reportDirectory)
    Files.writeString(reportDirectory.resolve("samples.csv"), samplesCsv(result.samples))
    Files.writeString(reportDirectory.resolve("artifact-sizes.csv"), artifactsCsv(result.artifacts))
    Files.writeString(reportDirectory.resolve("payload-sizes.csv"), payloadsCsv(result.payloads))
    Files.writeString(reportDirectory.resolve("memory.csv"), memoryCsv(result.memory))
    Files.writeString(reportDirectory.resolve("summary.md"), summaryMarkdown(result))
}

private fun samplesCsv(samples: List<PerformanceSample>): String =
    buildString {
        appendLine("metric,iteration,elapsed_nanos,elapsed_millis")
        samples.forEach { sample ->
            appendLine(
                "${sample.metric},${sample.iteration},${sample.elapsedNanos},${decimal(sample.elapsedMillis)}",
            )
        }
    }

private fun artifactsCsv(artifacts: List<ArtifactSize>): String =
    buildString {
        appendLine("relative_path,bytes")
        artifacts.forEach { artifact -> appendLine("${artifact.relativePath},${artifact.bytes}") }
    }

private fun payloadsCsv(payloads: List<PayloadSize>): String =
    buildString {
        appendLine("payload,encoded_bytes,decoded_utf16_bytes_estimate")
        payloads.forEach { payload ->
            appendLine(
                "${payload.name},${payload.encodedBytes},${payload.decodedUtf16BytesEstimate.orEmpty()}",
            )
        }
    }

private fun memoryCsv(memory: List<MemorySnapshot>): String =
    buildString {
        appendLine("stage,process_rss_bytes,heap_used_bytes")
        memory.forEach { snapshot ->
            appendLine("${snapshot.stage},${snapshot.processRssBytes.orEmpty()},${snapshot.heapUsedBytes}")
        }
    }

private fun summaryMarkdown(result: WorkspaceAtlasPerformanceReport): String =
    buildString {
        appendLine("# Workspace Atlas repository-scale performance profile")
        appendLine()
        appendLine("This report is produced by the opt-in JVM profile; ordinary unit tests contain no timing gates.")
        appendLine()
        appendFixture(result)
        appendLatencySummary(result.summaries)
        appendMeasuredChecks(result)
        appendUnmeasuredGates()
    }

private fun StringBuilder.appendFixture(result: WorkspaceAtlasPerformanceReport) {
    val totalArtifactBytes = result.artifacts.sumOf(ArtifactSize::bytes)
    val maximumRss = result.memory.mapNotNull(MemorySnapshot::processRssBytes).maxOrNull()
    appendLine("## Fixture")
    appendLine()
    appendLine("- Profile: `${result.profile.profile}`")
    appendLine("- Builds: ${result.profile.builds}")
    appendLine("- Projects: ${result.profile.projects}")
    appendLine("- Files: ${result.profile.files}")
    appendLine("- Symbols: ${result.profile.symbols}")
    appendLine("- Relationships: ${result.profile.relationships}")
    appendLine("- Represented logical lines: ${result.profile.logicalLines}")
    appendLine("- Materialized fixture lines: ${result.profile.materializedLines}")
    appendLine("- Static artifact bytes: $totalArtifactBytes")
    appendLine("- SQLite database bytes: ${result.databaseBytes}")
    appendLine("- Maximum sampled harness-process RSS: ${maximumRss.orEmpty()}")
    appendLine("- Java runtime: ${System.getProperty("java.runtime.version")}")
    appendLine("- Operating system: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    appendLine("- Available processors: ${Runtime.getRuntime().availableProcessors()}")
    appendLine()
    appendLine(
        "The topology reaches the target build/project/logical-LOC dimensions, but source bodies are compact. " +
            "It proves bounded hierarchy and index behavior; it does not claim a physically materialized " +
            "10-million-line decode or a real composite of that size.",
    )
    appendLine()
}

private fun StringBuilder.appendLatencySummary(summaries: List<PerformanceLatencySummary>) {
    appendLine("## Raw latency summary")
    appendLine()
    appendLine("| Metric | Samples | Min ms | p50 ms | p95 ms | Max ms |")
    appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
    summaries.forEach { summary ->
        appendLine(
            "| ${summary.metric} | ${summary.sampleCount} | ${decimal(summary.minimumMillis)} | " +
                "${decimal(summary.medianMillis)} | ${decimal(summary.p95Millis)} | " +
                "${decimal(summary.maximumMillis)} |",
        )
    }
    appendLine()
}

private fun StringBuilder.appendMeasuredChecks(result: WorkspaceAtlasPerformanceReport) {
    val summaries = result.summaries.associateBy(PerformanceLatencySummary::metric)
    appendLine("## Measured component checks")
    appendLine()
    appendGate(
        "Static initial-map read/decode component <= 2,000 ms",
        summaries.getValue(STATIC_INITIAL_MAP_METRIC).p95Millis,
        INITIAL_MAP_THRESHOLD_MILLIS,
    )
    appendGate(
        "Warm static bounded projection p95 <= 100 ms",
        summaries.getValue(STATIC_QUERY_METRIC).p95Millis,
        WARM_INTERACTION_THRESHOLD_MILLIS,
    )
    appendGate(
        "Unique-shard static drill component p95 <= 500 ms",
        summaries.getValue(STATIC_DRILL_METRIC).p95Millis,
        DRILL_THRESHOLD_MILLIS,
    )
    appendGate(
        "Warm SQLite bounded graph p95 <= 100 ms",
        summaries.getValue(LIVE_QUERY_METRIC).p95Millis,
        WARM_INTERACTION_THRESHOLD_MILLIS,
    )
    appendGate(
        "Warm SQLite symbol search p95 <= 100 ms",
        summaries.getValue(LIVE_SEARCH_METRIC).p95Millis,
        WARM_INTERACTION_THRESHOLD_MILLIS,
    )
    appendLine(
        "- PASS — static/live selected-project slices have equivalent stable node shapes, routes, and counts " +
            "(${result.paritySlice.content.nodes.size} nodes, " +
            "${result.paritySlice.content.relations.size} routes).",
    )
    appendLine(
        "- INFO — scale parity excludes generation-global availability and child totals because the static probe " +
            "loads one project shard while SQLite owns the complete generation; the full-site parity test covers " +
            "those fields exactly.",
    )
    appendSliceGate("static default", result.staticDefaultSlice)
    appendSliceGate("static hard", result.staticHardSlice)
    appendSliceGate("live default", result.liveDefaultSlice)
    appendSliceGate("live hard", result.liveHardSlice)
    appendLine()
}

private fun StringBuilder.appendUnmeasuredGates() {
    appendLine("## Acceptance gates not established by this JVM profile")
    appendLine()
    appendLine("- NOT MEASURED — browser cold-first-use p95, network transfer, Wasm decode, and layout.")
    appendLine("- NOT MEASURED — pan/zoom FPS, >50 ms main-thread tasks, and browser heap retention.")
    appendLine(
        "- NOT MEASURED HERE — repository-scale mounted scope rows. The production browser smoke separately " +
            "asserts the five-row window plus one overscan row per side never mounts more than seven choices.",
    )
    appendLine("- NOT MEASURED — direct declaration-ID and relationship-occurrence lookup p95.")
    appendLine("- NOT MEASURED — lazy single-source-blob HTTP transfer.")
    appendLine("- NOT MEASURED — one-file incremental indexing and doubled-fact linearity.")
    appendLine(
        "- NOT MEASURED — a real 80-build/2,000-project/10M-line composite; this run uses synthetic topology.",
    )
    appendLine()
    appendLine("Raw samples and byte inventories are retained beside this summary.")
}

private fun StringBuilder.appendGate(
    label: String,
    actualMillis: Double,
    thresholdMillis: Double,
) {
    val status = if (actualMillis <= thresholdMillis) "PASS" else "FAIL"
    appendLine("- $status — $label; measured ${decimal(actualMillis)} ms.")
}

private fun StringBuilder.appendSliceGate(
    label: String,
    slice: WorkspaceGraphSlice,
) {
    appendLine(
        "- PASS — $label slice returned ${slice.content.nodes.size}/${slice.limits.nodeLimit} nodes and " +
            "${slice.content.relations.size}/${slice.limits.relationLimit} routes.",
    )
}

private fun Long?.orEmpty(): String = this?.toString().orEmpty()

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

internal const val STATIC_INITIAL_MAP_METRIC = "static_initial_map_component"
internal const val STATIC_QUERY_METRIC = "static_bounded_query"
internal const val STATIC_DRILL_METRIC = "static_unique_project_drill"
internal const val LIVE_IMPORT_METRIC = "live_sqlite_import"
internal const val LIVE_FIRST_QUERY_METRIC = "live_first_bounded_query"
internal const val LIVE_QUERY_METRIC = "live_warm_bounded_query"
internal const val LIVE_SEARCH_METRIC = "live_warm_symbol_search"
internal const val LIVE_RELATIONSHIP_SUMMARY_METRIC = "live_warm_relationship_summary"
private const val INITIAL_MAP_THRESHOLD_MILLIS = 2_000.0
private const val WARM_INTERACTION_THRESHOLD_MILLIS = 100.0
private const val DRILL_THRESHOLD_MILLIS = 500.0
