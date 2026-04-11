package zone.clanker.gradle.srcx.report

/**
 * Renders a single flow Mermaid sequence diagram file for an entry point.
 *
 * Each entry point gets its own file under `flows/{EntryPointName}.md`.
 *
 * @property entryPointName simple class name of the entry point
 * @property sequenceDiagram the Mermaid sequence diagram content
 */
internal class FlowRenderer(
    private val entryPointName: String,
    private val sequenceDiagram: String,
) {
    fun render(): String =
        buildString {
            appendLine("# $entryPointName Flow")
            appendLine()
            appendLine(sequenceDiagram)
        }

    companion object {
        /**
         * Parse a combined sequence diagram output into per-entry-point chunks.
         *
         * The combined output from [zone.clanker.gradle.srcx.analysis.generateSequenceDiagrams]
         * uses `### {Name} Flow` headers to separate diagrams. This function splits
         * them into individual (name, content) pairs.
         */
        fun splitDiagrams(combinedOutput: String): List<Pair<String, String>> {
            if (combinedOutput.isBlank()) return emptyList()
            val sections = mutableListOf<Pair<String, String>>()
            val lines = combinedOutput.lines()
            var currentName: String? = null
            val currentContent = StringBuilder()

            for (line in lines) {
                if (line.startsWith("### ") && line.endsWith(" Flow")) {
                    if (currentName != null && currentContent.isNotBlank()) {
                        sections.add(currentName to currentContent.toString().trim())
                        currentContent.clear()
                    }
                    currentName = line.removePrefix("### ").removeSuffix(" Flow")
                } else if (currentName != null) {
                    currentContent.appendLine(line)
                }
            }
            if (currentName != null && currentContent.isNotBlank()) {
                sections.add(currentName to currentContent.toString().trim())
            }
            return sections
        }
    }
}
