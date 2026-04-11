package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind

/**
 * Renders the entry-points.md file listing app entry points, test classes,
 * and test doubles (Mock/Fake/Stub classes).
 *
 * @property summaries all project summaries to scan for entry points
 * @property appEntryPoints pre-classified app entry points from the analysis layer
 */
internal class EntryPointsRenderer(
    private val summaries: List<ProjectSummary>,
    private val appEntryPoints: List<EntryPoint> = emptyList(),
) {
    /**
     * A classified app entry point.
     *
     * @property className simple class name
     * @property packageName package containing the class
     * @property firstCall name of the first method called (if known)
     */
    data class EntryPoint(
        val className: String,
        val packageName: String,
        val firstCall: String = "",
    )

    fun render(): String =
        buildString {
            appendLine("# Entry Points")
            appendLine()
            appendAppEntryPoints()
            appendTestEntryPoints()
            appendTestDoubles()
        }

    private fun StringBuilder.appendAppEntryPoints() {
        appendLine("## App Entry Points")
        appendLine()
        if (appEntryPoints.isEmpty()) {
            appendLine("No app entry points detected.")
            appendLine()
            return
        }
        appendLine("| Class | Package | First Call |")
        appendLine("|-------|---------|------------|")
        for (ep in appEntryPoints) {
            val firstCall = ep.firstCall.ifEmpty { "-" }
            appendLine("| `${ep.className}` | ${ep.packageName} | $firstCall |")
        }
        appendLine()
    }

    private fun StringBuilder.appendTestEntryPoints() {
        val testClasses = findTestClasses()
        appendLine("## Test Entry Points")
        appendLine()
        if (testClasses.isEmpty()) {
            appendLine("No test classes found.")
            appendLine()
            return
        }
        appendLine("| Class | Package |")
        appendLine("|-------|---------|")
        for (tc in testClasses) {
            appendLine("| `${tc.name}` | ${tc.packageName} |")
        }
        appendLine()
    }

    private fun StringBuilder.appendTestDoubles() {
        val doubles = findTestDoubles()
        appendLine("## Test Doubles")
        appendLine()
        if (doubles.isEmpty()) {
            appendLine("No test doubles found.")
            appendLine()
            return
        }
        appendLine("| Class | Package | Kind |")
        appendLine("|-------|---------|------|")
        for (td in doubles) {
            appendLine("| `${td.name}` | ${td.packageName} | ${td.kind} |")
        }
        appendLine()
    }

    private fun findTestClasses(): List<SymbolEntry> =
        summaries
            .flatMap { summary ->
                summary.sourceSets
                    .filter { it.name.value.contains("test", ignoreCase = true) }
                    .flatMap { ss ->
                        ss.symbols.filter { it.kind == SymbolKind.CLASS && it.name.value.endsWith("Test") }
                    }
            }.distinctBy { "${it.packageName}.${it.name}" }

    private fun findTestDoubles(): List<TestDouble> {
        val allClasses =
            summaries.flatMap { summary ->
                summary.sourceSets.flatMap { ss ->
                    ss.symbols.filter { it.kind == SymbolKind.CLASS }
                }
            }
        return allClasses
            .mapNotNull { symbol ->
                val name = symbol.name.value
                val kind =
                    when {
                        name.startsWith("Mock") || name.endsWith("Mock") -> "Mock"
                        name.startsWith("Fake") || name.endsWith("Fake") -> "Fake"
                        name.startsWith("Stub") || name.endsWith("Stub") -> "Stub"
                        else -> null
                    }
                kind?.let { TestDouble(symbol.name.value, symbol.packageName.value, it) }
            }.distinctBy { "${it.packageName}.${it.name}" }
    }

    private data class TestDouble(
        val name: String,
        val packageName: String,
        val kind: String,
    )
}
