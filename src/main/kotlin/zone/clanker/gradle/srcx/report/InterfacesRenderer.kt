package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind

/**
 * Renders the interfaces.md file listing all interfaces with implementation counts.
 *
 * Identifies interfaces by naming convention (prefixed with "I" or common interface
 * suffixes) and correlates with implementation classes. Tags mock implementations.
 *
 * @property summaries all project summaries to scan
 * @property interfaces pre-computed interface data (name, package, impl count, has mock)
 */
internal class InterfacesRenderer(
    private val interfaces: List<InterfaceInfo>,
) {
    /**
     * Pre-computed interface information.
     *
     * @property name simple class name
     * @property packageName package containing the interface
     * @property implementationCount number of known implementations
     * @property hasMock whether a mock implementation exists
     */
    data class InterfaceInfo(
        val name: String,
        val packageName: String,
        val implementationCount: Int,
        val hasMock: Boolean,
    )

    fun render(): String =
        buildString {
            appendLine("# Interfaces")
            appendLine()
            if (interfaces.isEmpty()) {
                appendLine("No interfaces detected.")
                appendLine()
                return@buildString
            }
            appendLine("| Interface | Package | Implementations | Has Mock |")
            appendLine("|-----------|---------|----------------|----------|")
            for (iface in interfaces) {
                val mockTag = if (iface.hasMock) "yes" else "no"
                appendLine("| `${iface.name}` | ${iface.packageName} | ${iface.implementationCount} | $mockTag |")
            }
            appendLine()
        }

    companion object {
        /**
         * Build interface info from project summaries by correlating class names.
         *
         * Identifies interfaces by common naming patterns and counts implementations
         * by looking for classes that match the interface name with common suffixes/prefixes.
         */
        fun fromSummaries(summaries: List<ProjectSummary>): List<InterfaceInfo> {
            val allClasses =
                summaries.flatMap { summary ->
                    summary.sourceSets.flatMap { ss ->
                        ss.symbols.filter { it.kind == SymbolKind.CLASS }
                    }
                }
            val classNames = allClasses.map { it.name.value }.toSet()

            // Find interfaces from analysis hubs or naming convention
            val potentialInterfaces = findInterfacesFromAnalysis(summaries)
            if (potentialInterfaces.isEmpty()) return emptyList()

            return potentialInterfaces
                .map { (name, pkg) ->
                    val implCount = countImplementations(name, classNames)
                    val hasMock =
                        classNames.any { cn ->
                            cn == "Mock$name" ||
                                cn == "${name}Mock" ||
                                cn == "Fake$name" ||
                                cn == "${name}Fake"
                        }
                    InterfaceInfo(name, pkg, implCount, hasMock)
                }.sortedByDescending { it.implementationCount }
        }

        private fun findInterfacesFromAnalysis(summaries: List<ProjectSummary>): List<Pair<String, String>> {
            // Look at findings that mention interfaces (from single-impl detection)
            val fromFindings = mutableListOf<Pair<String, String>>()
            for (summary in summaries) {
                val findings = summary.analysis?.findings ?: continue
                for (finding in findings) {
                    val match = INTERFACE_PATTERN.find(finding.message)
                    if (match != null) {
                        val ifaceName = match.groupValues[1]
                        fromFindings.add(ifaceName to summary.projectPath.value)
                    }
                }
            }

            // Also detect by naming convention from symbols
            val fromNaming =
                summaries.flatMap { summary ->
                    summary.sourceSets.flatMap { ss ->
                        ss.symbols
                            .filter { it.kind == SymbolKind.CLASS }
                            .filter { isLikelyInterface(it) }
                            .map { it.name.value to it.packageName.value }
                    }
                }

            return (fromFindings + fromNaming).distinctBy { it.first }
        }

        private fun isLikelyInterface(symbol: SymbolEntry): Boolean {
            val name = symbol.name.value
            // Common interface naming patterns
            return name.endsWith("Service") ||
                name.endsWith("Repository") ||
                name.endsWith("Provider") ||
                name.endsWith("Factory") ||
                (name.length > 2 && name.startsWith("I") && name[1].isUpperCase())
        }

        private fun countImplementations(interfaceName: String, classNames: Set<String>): Int {
            val baseName = interfaceName.removePrefix("I")
            return classNames.count { cn ->
                cn != interfaceName &&
                    (
                        cn == "${baseName}Impl" ||
                            cn == "Default$interfaceName" ||
                            cn == "Default$baseName" ||
                            (cn.endsWith(baseName) && cn != baseName)
                    )
            }
        }

        private val INTERFACE_PATTERN = Regex("""Interface `(\w+)` has""")
    }
}
