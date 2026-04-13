package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind

/**
 * Renders the interfaces.md file listing all interfaces with implementation counts.
 *
 * Identifies interfaces by naming convention (prefixed with "I" or common interface
 * suffixes) and correlates with implementation classes. Tags mock implementations.
 * Groups by source set (main vs test).
 *
 * @property interfaces pre-computed interface data (name, package, impl count, has mock, source set)
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
     * @property sourceSet the source set this interface belongs to (main, test, etc.)
     */
    data class InterfaceInfo(
        val name: String,
        val packageName: String,
        val implementationCount: Int,
        val hasMock: Boolean,
        val sourceSet: String = "main",
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
            val mainInterfaces = interfaces.filter { !it.sourceSet.contains("test", ignoreCase = true) }
            val testInterfaces = interfaces.filter { it.sourceSet.contains("test", ignoreCase = true) }

            if (mainInterfaces.isNotEmpty()) {
                appendInterfaceTable(mainInterfaces)
            }
            if (testInterfaces.isNotEmpty()) {
                appendLine("### Test")
                appendLine()
                appendInterfaceTable(testInterfaces)
            }
        }

    private fun StringBuilder.appendInterfaceTable(items: List<InterfaceInfo>) {
        appendLine("| Interface | Package | Implementations | Has Mock |")
        appendLine("|-----------|---------|----------------|----------|")
        for (iface in items) {
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
         * Excludes enum values and non-interface-like classes.
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
                .map { (name, pkg, sourceSet) ->
                    val implCount = countImplementations(name, classNames)
                    val hasMock =
                        classNames.any { cn ->
                            cn == "Mock$name" ||
                                cn == "${name}Mock" ||
                                cn == "Fake$name" ||
                                cn == "${name}Fake"
                        }
                    InterfaceInfo(name, pkg, implCount, hasMock, sourceSet)
                }.sortedByDescending { it.implementationCount }
        }

        private data class InterfaceCandidate(
            val name: String,
            val packageName: String,
            val sourceSet: String,
        )

        @Suppress("NestedBlockDepth")
        private fun findInterfacesFromAnalysis(summaries: List<ProjectSummary>): List<InterfaceCandidate> {
            // Look at findings that mention interfaces (from single-impl detection)
            val fromFindings = mutableListOf<InterfaceCandidate>()
            for (summary in summaries) {
                val findings = summary.analysis?.findings ?: continue
                for (finding in findings) {
                    val match = INTERFACE_PATTERN.find(finding.message)
                    if (match != null) {
                        val ifaceName = match.groupValues[1]
                        if (!isEnumLikeName(ifaceName)) {
                            fromFindings.add(InterfaceCandidate(ifaceName, summary.projectPath.value, "main"))
                        }
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
                            .map { InterfaceCandidate(it.name.value, it.packageName.value, ss.name.value) }
                    }
                }

            return (fromFindings + fromNaming).distinctBy { it.name }
        }

        private fun isLikelyInterface(symbol: SymbolEntry): Boolean {
            val name = symbol.name.value
            // Exclude enum-like names (ALL_CAPS, single-word uppercase)
            if (isEnumLikeName(name)) return false
            // Common interface naming patterns
            return name.endsWith("Service") ||
                name.endsWith("Repository") ||
                name.endsWith("Provider") ||
                name.endsWith("Factory") ||
                (name.length > 2 && name.startsWith("I") && name[1].isUpperCase())
        }

        /**
         * Returns true if the name looks like an enum value rather than an interface.
         * Enum values are typically ALL_CAPS or single uppercase words without lowercase.
         */
        @Suppress("ReturnCount")
        private fun isEnumLikeName(name: String): Boolean {
            if (name.isEmpty()) return false
            if (name.all { it.isUpperCase() || it == '_' || it.isDigit() }) return true
            if (name.contains('.')) return true
            return false
        }

        private fun countImplementations(interfaceName: String, classNames: Set<String>): Int {
            val baseName = interfaceName.removePrefix("I")
            return classNames.count { cn ->
                cn != interfaceName &&
                    !isMockOrFake(cn) &&
                    (
                        cn == "${baseName}Impl" ||
                            cn == "Default$interfaceName" ||
                            cn == "Default$baseName" ||
                            (cn.endsWith(baseName) && cn != baseName)
                    )
            }
        }

        private fun isMockOrFake(className: String): Boolean {
            val lower = className.lowercase()
            return lower.startsWith("mock") ||
                lower.startsWith("fake") ||
                lower.startsWith("stub") ||
                lower.endsWith("mock") ||
                lower.endsWith("fake") ||
                lower.endsWith("stub")
        }

        private val INTERFACE_PATTERN = Regex("""Interface `(\w+)` has""")
    }
}
