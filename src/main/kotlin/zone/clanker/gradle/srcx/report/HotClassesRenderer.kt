package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.HubClass

/**
 * Renders hub-classes.md listing hub classes with their dependents in tree format.
 *
 * Production classes are listed first, test classes at the end.
 * Each hub with sufficient dependents gets a tree showing dependent file paths.
 */
internal class HotClassesRenderer(
    private val hubs: List<HubClass>,
) {
    fun render(): String =
        buildString {
            appendLine("# Hub Classes")
            appendLine()
            appendLine("Classes with the most inbound dependencies across the codebase.")
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
                appendLine("```")
                appendLine("${hub.filePath}:${hub.line} \u2014 ${hub.dependentCount} dependents")
                val productionDeps = hub.dependents.filter { !isTestPath(it.filePath) }
                val testDeps = hub.dependents.filter { isTestPath(it.filePath) }
                val allDeps = productionDeps + testDeps
                for ((index, dep) in allDeps.withIndex()) {
                    val isLast = index == allDeps.lastIndex
                    val prefix2 = if (isLast) "\u2514\u2500\u2500" else "\u251C\u2500\u2500"
                    val testMarker = if (isTestPath(dep.filePath)) " \uD83E\uDDEA" else ""
                    appendLine("$prefix2 ${dep.filePath}:${dep.line}$testMarker")
                }
                appendLine("```")
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

        private fun isTestPath(path: String): Boolean =
            path.contains("/test/") || path.contains("Test")
    }
}
