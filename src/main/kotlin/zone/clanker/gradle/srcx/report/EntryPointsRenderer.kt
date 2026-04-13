package zone.clanker.gradle.srcx.report

/**
 * Renders the entry-points.md file listing app entry points, test classes,
 * and test doubles (Mock/Fake/Stub classes).
 *
 * Groups by source set: main entry points first, then test entry points and doubles.
 *
 * @property summaries all project summaries to scan for entry points
 * @property appEntryPoints pre-classified app entry points from the analysis layer
 */
internal class EntryPointsRenderer(
    private val classifiedEntryPoints: List<ClassifiedEntry> = emptyList(),
) {
    /**
     * A classified entry point with its kind.
     *
     * @property className simple class name
     * @property packageName package containing the class
     * @property kind APP, TEST, or MOCK
     */
    data class ClassifiedEntry(
        val className: String,
        val packageName: String,
        val kind: EntryKind,
    )

    enum class EntryKind {
        APP,
        TEST,
        MOCK,
    }

    fun render(): String =
        buildString {
            appendLine("# Entry Points")
            appendLine()
            appendLine("Classes that serve as application or test entry points into the codebase.")
            appendLine()
            appendSection("App Entry Points", classifiedEntryPoints.filter { it.kind == EntryKind.APP })
            appendSection("Test Entry Points", classifiedEntryPoints.filter { it.kind == EntryKind.TEST })
            appendMocks(classifiedEntryPoints.filter { it.kind == EntryKind.MOCK })
        }

    private fun StringBuilder.appendSection(title: String, entries: List<ClassifiedEntry>) {
        appendLine("## $title")
        appendLine()
        if (entries.isEmpty()) {
            appendLine("None detected.")
            appendLine()
            return
        }
        appendLine("| Class | Package |")
        appendLine("|-------|---------|")
        for (ep in entries) {
            appendLine("| `${ep.className}` | ${ep.packageName} |")
        }
        appendLine()
    }

    private fun StringBuilder.appendMocks(entries: List<ClassifiedEntry>) {
        appendLine("## Test Doubles")
        appendLine()
        if (entries.isEmpty()) {
            appendLine("None detected.")
            appendLine()
            return
        }
        appendLine("| Class | Package |")
        appendLine("|-------|---------|")
        for (ep in entries) {
            appendLine("| `${ep.className}` | ${ep.packageName} |")
        }
        appendLine()
    }
}
