package zone.clanker.gradle.conventions

import groovy.json.JsonOutput
import java.io.File
import java.time.Instant
import kotlin.math.ceil

internal enum class DocxWebProofGateStatus {
    PASS,
    FAIL,
    NOT_MEASURED,
}

internal data class DocxWebProofSample(
    val phase: String,
    val source: String,
    val metric: String,
    val value: Double,
    val unit: String,
    val detail: String = "",
)

internal data class DocxWebProofGate(
    val name: String,
    val status: DocxWebProofGateStatus,
    val target: String,
    val observed: String,
    val detail: String,
    val required: Boolean = false,
)

internal data class DocxWebProofCapture(
    val state: String,
    val file: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
)

internal class DocxWebBrowserProofReport {
    private val samples = mutableListOf<DocxWebProofSample>()
    private val gates = mutableListOf<DocxWebProofGate>()
    private val captures = mutableListOf<DocxWebProofCapture>()

    fun addSample(sample: DocxWebProofSample) {
        samples += sample
    }

    fun addSamples(newSamples: Iterable<DocxWebProofSample>) {
        samples += newSamples
    }

    fun addGate(gate: DocxWebProofGate) {
        gates.removeAll { existing -> existing.name == gate.name }
        gates += gate
    }

    fun addCapture(capture: DocxWebProofCapture) {
        captures += capture
    }

    fun requiredFailures(): List<DocxWebProofGate> =
        gates.filter { gate -> gate.required && gate.status == DocxWebProofGateStatus.FAIL }

    fun write(outputDirectory: File) {
        outputDirectory.mkdirs()
        outputDirectory.resolve(JSON_FILE).writeText(jsonDocument())
        outputDirectory.resolve(CSV_FILE).writeText(csvDocument())
    }

    fun addTimingGates() {
        val longTasks =
            samples.filter { sample ->
                sample.metric == "long-task-duration" &&
                    !sample.phase.endsWith("-cold-start") &&
                    sample.phase != "lifecycle"
            }
        val coldStartLongTasks =
            samples.filter { sample ->
                sample.metric == "long-task-duration" && sample.phase.endsWith("-cold-start")
            }
        val lifecycleLongTasks =
            samples.filter { sample -> sample.metric == "long-task-duration" && sample.phase == "lifecycle" }
        val frameIntervals = samples.filter { sample -> sample.metric == "frame-interval" }
        addGate(
            if (longTasks.isEmpty()) {
                DocxWebProofGate(
                    name = "no-input-blocking-long-task",
                    status = DocxWebProofGateStatus.NOT_MEASURED,
                    target = "No interaction-window task over 50 ms",
                    observed = "No PerformanceObserver long-task samples",
                    detail = "Observer support and an explicit interaction window are required before absence is proof.",
                )
            } else {
                val maximum = longTasks.maxOf(DocxWebProofSample::value)
                DocxWebProofGate(
                    name = "no-input-blocking-long-task",
                    status = if (maximum <= LONG_TASK_LIMIT_MILLIS) {
                        DocxWebProofGateStatus.PASS
                    } else {
                        DocxWebProofGateStatus.FAIL
                    },
                    target = "Maximum interaction-window task <= $LONG_TASK_LIMIT_MILLIS ms",
                    observed = "Maximum ${format(maximum)} ms across ${longTasks.size} raw samples",
                    detail = "Measured after ready during trusted interaction windows; cold boot and lifecycle stress are separate.",
                    required = true,
                )
            },
        )
        addLongTaskDiagnostic("cold-start-long-task", "cold startup", coldStartLongTasks)
        addLongTaskDiagnostic("renderer-lifecycle-long-task", "100-cycle renderer lifecycle stress", lifecycleLongTasks)
        addGate(
            if (frameIntervals.size < MINIMUM_FRAME_SAMPLES) {
                DocxWebProofGate(
                    name = "sixty-fps-frame-interval",
                    status = DocxWebProofGateStatus.NOT_MEASURED,
                    target = "rAF interval p95 <= $FRAME_INTERVAL_TARGET_MILLIS ms",
                    observed = "${frameIntervals.size} samples; need at least $MINIMUM_FRAME_SAMPLES",
                    detail = "The report retains every available interval without inferring a percentile.",
                )
            } else {
                val p95 = percentile(frameIntervals.map(DocxWebProofSample::value), 0.95)
                DocxWebProofGate(
                    name = "sixty-fps-frame-interval",
                    status = if (p95 <= FRAME_INTERVAL_TARGET_MILLIS) {
                        DocxWebProofGateStatus.PASS
                    } else {
                        DocxWebProofGateStatus.FAIL
                    },
                    target = "rAF interval p95 <= $FRAME_INTERVAL_TARGET_MILLIS ms",
                    observed = "p95 ${format(p95)} ms across ${frameIntervals.size} raw samples",
                    detail = "Headless fixture diagnostic; a representative-scale physical-browser p95 remains separate.",
                )
            },
        )
        listOf(
            Triple("cold-first-useful-workspace-p95", "p95 <= 2000 ms", "Representative 80-build/2,000-project browser population"),
            Triple("warm-indexed-interaction-p95", "p95 <= 100 ms", "Repeated indexed search/layer/scope/filter samples"),
            Triple("cold-bounded-drill-p95", "p95 <= 500 ms", "Repeated cold bounded drill samples"),
        ).forEach { (name, target, requirement) ->
            addGate(
                DocxWebProofGate(
                    name = name,
                    status = DocxWebProofGateStatus.NOT_MEASURED,
                    target = target,
                    observed = "Not measured by the small browser correctness fixture",
                    detail = "$requirement is required; no synthetic claim is substituted.",
                ),
            )
        }
    }

    private fun addLongTaskDiagnostic(
        name: String,
        label: String,
        tasks: List<DocxWebProofSample>,
    ) {
        val maximum = tasks.maxOfOrNull(DocxWebProofSample::value)
        addGate(
            DocxWebProofGate(
                name = name,
                status = if (maximum == null) DocxWebProofGateStatus.NOT_MEASURED else DocxWebProofGateStatus.PASS,
                target = "Record $label tasks without conflating them with input latency",
                observed = maximum?.let { value -> "Maximum ${format(value)} ms across ${tasks.size} raw samples" }
                    ?: "No Long Tasks API samples",
                detail = "Reported independently; this diagnostic does not weaken the required interaction gate.",
            ),
        )
    }

    private fun jsonDocument(): String {
        val document =
            linkedMapOf(
                "schemaVersion" to 1,
                "generatedAt" to Instant.now().toString(),
                "captures" to
                    captures.map { capture ->
                        linkedMapOf(
                            "state" to capture.state,
                            "file" to capture.file,
                            "bytes" to capture.bytes,
                            "width" to capture.width,
                            "height" to capture.height,
                        )
                    },
                "gates" to
                    gates.map { gate ->
                        linkedMapOf(
                            "name" to gate.name,
                            "status" to gate.status.name.lowercase(),
                            "target" to gate.target,
                            "observed" to gate.observed,
                            "detail" to gate.detail,
                            "required" to gate.required,
                        )
                    },
                "samples" to
                    samples.map { sample ->
                        linkedMapOf(
                            "phase" to sample.phase,
                            "source" to sample.source,
                            "metric" to sample.metric,
                            "value" to sample.value,
                            "unit" to sample.unit,
                            "detail" to sample.detail,
                        )
                    },
            )
        return JsonOutput.prettyPrint(JsonOutput.toJson(document)) + "\n"
    }

    private fun csvDocument(): String =
        buildString {
            appendLine("phase,source,metric,value,unit,detail")
            samples.forEach { sample ->
                appendLine(
                    listOf(
                        sample.phase,
                        sample.source,
                        sample.metric,
                        sample.value.toString(),
                        sample.unit,
                        sample.detail,
                    ).joinToString(",", transform = ::csvCell),
                )
            }
        }

    private fun csvCell(value: String): String =
        if (value.any { character -> character == ',' || character == '"' || character == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    private fun percentile(
        values: List<Double>,
        fraction: Double,
    ): Double {
        val sorted = values.sorted()
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun format(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    companion object {
        const val JSON_FILE = "browser-proof.json"
        const val CSV_FILE = "browser-proof.csv"
        private const val LONG_TASK_LIMIT_MILLIS = 50.0
        private const val FRAME_INTERVAL_TARGET_MILLIS = 16.667
        private const val MINIMUM_FRAME_SAMPLES = 30
    }
}
