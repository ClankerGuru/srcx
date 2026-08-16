package zone.clanker.docx.index.performance

internal data class PerformanceSample(
    val metric: String,
    val iteration: Int,
    val elapsedNanos: Long,
) {
    val elapsedMillis: Double = elapsedNanos / NANOS_PER_MILLISECOND
}

internal class PerformanceRecorder {
    private val mutableSamples = mutableListOf<PerformanceSample>()

    val samples: List<PerformanceSample>
        get() = mutableSamples.toList()

    fun <T> measure(
        metric: String,
        iteration: Int,
        block: () -> T,
    ): T {
        val started = System.nanoTime()
        val result = block()
        mutableSamples += PerformanceSample(metric, iteration, System.nanoTime() - started)
        return result
    }

    fun summary(metric: String): PerformanceLatencySummary {
        val values = mutableSamples.filter { sample -> sample.metric == metric }.map(PerformanceSample::elapsedMillis)
        require(values.isNotEmpty()) { "Performance metric has no samples: $metric" }
        val sorted = values.sorted()
        return PerformanceLatencySummary(
            metric = metric,
            sampleCount = sorted.size,
            minimumMillis = sorted.first(),
            medianMillis = percentile(sorted, MEDIAN_PERCENTILE),
            p95Millis = percentile(sorted, P95_PERCENTILE),
            maximumMillis = sorted.last(),
        )
    }
}

internal data class PerformanceLatencySummary(
    val metric: String,
    val sampleCount: Int,
    val minimumMillis: Double,
    val medianMillis: Double,
    val p95Millis: Double,
    val maximumMillis: Double,
)

private fun percentile(
    sortedValues: List<Double>,
    percentile: Double,
): Double {
    val rank =
        kotlin.math
            .ceil(percentile * sortedValues.size)
            .toInt()
            .coerceIn(1, sortedValues.size)
    return sortedValues[rank - 1]
}

private const val NANOS_PER_MILLISECOND = 1_000_000.0
private const val MEDIAN_PERCENTILE = 0.50
private const val P95_PERCENTILE = 0.95
