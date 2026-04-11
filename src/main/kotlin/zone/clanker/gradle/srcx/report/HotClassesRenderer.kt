package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.HubClass

/**
 * Renders hub-classes.md listing hub classes with their dependents.
 *
 * Production classes are listed first, test classes at the end.
 * Icons: production classes unmarked, test classes prefixed with 🧪.
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
            val production = hubs.filter { !it.isTest }
            val test = hubs.filter { it.isTest }

            if (production.isNotEmpty()) {
                appendHubTable(production)
            }
            if (test.isNotEmpty()) {
                appendLine("### Test")
                appendLine()
                appendHubTable(test, prefix = "\uD83E\uDDEA ")
            }
            for (hub in hubs.filter { it.dependentCount >= DETAIL_THRESHOLD }) {
                val icon = if (hub.isTest) "\uD83E\uDDEA " else ""
                appendLine("## $icon${hub.name}")
                appendLine()
                for (dep in hub.dependents) {
                    appendLine("- ${dep.name} — ${dep.filePath}:${dep.line}")
                }
                appendLine()
            }
        }

    private fun StringBuilder.appendHubTable(
        hubList: List<HubClass>,
        prefix: String = "",
    ) {
        appendLine("| Class | File | Dependents | Role |")
        appendLine("|-------|------|------------|------|")
        for (hub in hubList) {
            appendLine("| $prefix`${hub.name}` | ${hub.filePath}:${hub.line} | ${hub.dependentCount} | ${hub.role} |")
        }
        appendLine()
    }

    companion object {
        private const val DETAIL_THRESHOLD = 3
    }
}
