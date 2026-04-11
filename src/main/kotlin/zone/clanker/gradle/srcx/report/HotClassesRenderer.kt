package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.HubClass

/**
 * Renders the hub-classes.md file listing hub classes with their dependents.
 *
 * Hub classes are the most depended-on classes across the codebase.
 * Each hub with [DETAIL_THRESHOLD] or more dependents gets a detailed section.
 *
 * @property hubs the hub classes sorted by dependent count descending
 */
internal class HotClassesRenderer(
    private val hubs: List<HubClass>,
) {
    fun render(): String =
        buildString {
            appendLine("# Hub Classes")
            appendLine()
            if (hubs.isEmpty()) {
                appendLine("No hub classes detected.")
                appendLine()
                return@buildString
            }
            appendLine("| Class | File | Dependents | Role |")
            appendLine("|-------|------|------------|------|")
            for (hub in hubs) {
                appendLine("| `${hub.name}` | ${hub.filePath}:${hub.line} | ${hub.dependentCount} | ${hub.role} |")
            }
            appendLine()
            for (hub in hubs.filter { it.dependentCount >= DETAIL_THRESHOLD }) {
                appendLine("## ${hub.name}")
                appendLine()
                for (dep in hub.dependents) {
                    appendLine("- ${dep.name} — ${dep.filePath}:${dep.line}")
                }
                appendLine()
            }
        }

    companion object {
        private const val DETAIL_THRESHOLD = 3
    }
}
