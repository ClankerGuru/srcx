package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.UnusedClass

/** Renders conservative unused class candidates across the root and included builds. */
internal class UnusedClassesRenderer(
    private val unusedClasses: List<UnusedClass>,
) {
    fun render(): String =
        buildString {
            appendLine("# Potentially Unused Classes")
            appendLine()
            appendLine(
                "Production class-like declarations with no resolvable inbound source references across the root " +
                    "and included builds.",
            )
            appendLine()
            appendLine(
                "> Conservative source analysis: reflection, generated code, external consumers, and framework " +
                    "registration may not be visible. Verify each candidate before deleting it.",
            )
            appendLine()
            if (unusedClasses.isEmpty()) {
                appendLine("No potentially unused classes detected.")
                return@buildString
            }

            appendLine("**${unusedClasses.size} candidates**")
            appendLine()
            appendLine("| Class | Kind | Build | Project | Source Set | Location |")
            appendLine("|-------|------|-------|---------|------------|----------|")
            unusedClasses.forEach { candidate ->
                appendLine(
                    "| `${candidate.qualifiedName}` | ${candidate.kind.label} | `${candidate.buildName}` | " +
                        "`${candidate.projectPath}` | `${candidate.sourceSet}` | " +
                        "${candidate.filePath}:${candidate.line} |",
                )
            }
        }
}
