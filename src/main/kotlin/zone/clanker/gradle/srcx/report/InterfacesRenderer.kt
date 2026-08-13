package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolIdentity

/**
 * Renders the interfaces.md file listing all interfaces with implementation counts.
 *
 * Uses exact workspace symbols when available, with legacy naming conventions as a fallback.
 * Correlates implementation classes, tags mock implementations, and groups by source set.
 *
 * @property interfaces pre-computed interface data with declaration scope and implementation coverage
 */
internal class InterfacesRenderer(
    private val interfaces: List<InterfaceInfo>,
) {
    /**
     * Pre-computed interface information.
     *
     * @property name simple class name
     * @property packageName proven package containing the interface, when available
     * @property implementationCount number of known implementations
     * @property hasMock whether a mock implementation exists
     * @property sourceSet the source set this interface belongs to (main, test, etc.)
     * @property build owning Gradle build, when exact workspace facts are available
     * @property project owning Gradle project, when known
     * @property qualifiedName fully qualified declaration name
     * @property identity stable workspace declaration identity, when exact facts are available
     */
    @Suppress("LongParameterList")
    data class InterfaceInfo(
        val name: String,
        val packageName: String?,
        val implementationCount: Int,
        val hasMock: Boolean,
        val sourceSet: String = "main",
        val build: String? = null,
        val project: String? = null,
        val qualifiedName: String =
            packageName
                ?.takeUnless { it == "_root_" }
                ?.let { "$it.$name" }
                ?: name,
        val identity: WorkspaceSymbolIdentity? = null,
    ) {
        init {
            require(name.isNotBlank()) { "name must not be blank" }
            require(packageName == null || packageName.isNotBlank()) { "packageName must not be blank" }
            require(implementationCount >= 0) { "implementationCount must be >= 0" }
            require(sourceSet.isNotBlank()) { "sourceSet must not be blank" }
            require(build == null || build.isNotBlank()) { "build must not be blank" }
            require(project == null || project.isNotBlank()) { "project must not be blank" }
            require(qualifiedName.isNotBlank()) { "qualifiedName must not be blank" }
            identity?.let { exactIdentity ->
                require(build == exactIdentity.build) { "build must match identity" }
                require(project == exactIdentity.project) { "project must match identity" }
                require(sourceSet == exactIdentity.sourceSet) { "sourceSet must match identity" }
                require(qualifiedName == exactIdentity.qualifiedName) { "qualifiedName must match identity" }
            }
        }
    }

    fun render(): String =
        buildString {
            appendLine("# Interfaces")
            appendLine()
            if (interfaces.isEmpty()) {
                appendLine("No interfaces detected.")
                appendLine()
                return@buildString
            }
            val ordered = interfaces.sortedWith(interfaceComparator)
            val mainInterfaces = ordered.filter { !it.sourceSet.contains("test", ignoreCase = true) }
            val testInterfaces = ordered.filter { it.sourceSet.contains("test", ignoreCase = true) }

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
        appendLine("| Interface | Qualified name | Scope | Implementations | Has Mock |")
        appendLine("|-----------|----------------|-------|-----------------|----------|")
        for (iface in items) {
            val mockTag = if (iface.hasMock) "yes" else "no"
            val scope = listOfNotNull(iface.build, iface.project, iface.sourceSet).joinToString(" / ")
            appendLine(
                "| `${iface.name}` | `${iface.qualifiedName}` | `$scope` | ${iface.implementationCount} | $mockTag |",
            )
        }
        appendLine()
    }

    companion object {
        /**
         * Build interface info from exact workspace symbols, with a naming fallback for legacy summaries.
         *
         * Exact interface declarations use resolved implementation relationships. Legacy summaries
         * retain their existing name-based interface and implementation matching.
         */
        fun fromSummaries(
            summaries: List<ProjectSummary>,
            workspaceIndex: WorkspaceIndex = WorkspaceIndex(),
        ): List<InterfaceInfo> {
            val allClasses =
                summaries.flatMap { summary ->
                    summary.sourceSets.flatMap { ss ->
                        ss.symbols.filter { it.kind == SymbolKind.CLASS }
                    }
                }
            val classNames = allClasses.map { it.name.value }.toSet()

            val potentialInterfaces = findInterfaceCandidates(summaries, workspaceIndex)
            if (potentialInterfaces.isEmpty()) return emptyList()

            return potentialInterfaces
                .map { candidate ->
                    val exactImplementations =
                        candidate.identity?.let { identity ->
                            workspaceIndex.relationships
                                .filter {
                                    it.kind == WorkspaceRelationshipKind.IMPLEMENTS && it.targetIdentity == identity
                                }.mapNotNull { it.source }
                                .distinctBy { it.identity }
                        }
                    val implCount =
                        exactImplementations?.count { !isMockOrFake(it.name) }
                            ?: countImplementations(candidate.name, classNames)
                    val hasMock =
                        exactImplementations?.any { isMockOrFake(it.name) }
                            ?: hasNamedMock(candidate.name, classNames)
                    InterfaceInfo(
                        name = candidate.name,
                        packageName = candidate.packageName,
                        implementationCount = implCount,
                        hasMock = hasMock,
                        sourceSet = candidate.sourceSet,
                        build = candidate.build,
                        project = candidate.project,
                        qualifiedName = candidate.qualifiedName,
                        identity = candidate.identity,
                    )
                }.sortedWith(interfaceComparator)
        }

        private data class InterfaceCandidate(
            val name: String,
            val packageName: String?,
            val sourceSet: String,
            val qualifiedName: String,
            val build: String? = null,
            val project: String? = null,
            val identity: WorkspaceSymbolIdentity? = null,
        )

        private fun findInterfaceCandidates(
            summaries: List<ProjectSummary>,
            workspaceIndex: WorkspaceIndex,
        ): List<InterfaceCandidate> {
            val exactCandidates =
                workspaceIndex.symbols
                    .asSequence()
                    .filter { it.kind == SymbolDetailKind.INTERFACE }
                    .distinctBy { it.identity }
                    .map { symbol ->
                        InterfaceCandidate(
                            name = symbol.name,
                            packageName = findPackageName(symbol, summaries),
                            sourceSet = symbol.sourceSet,
                            qualifiedName = symbol.qualifiedName,
                            build = symbol.build,
                            project = symbol.project,
                            identity = symbol.identity,
                        )
                    }.sortedBy { it.identity?.value }
                    .toList()
            if (exactCandidates.isNotEmpty()) return exactCandidates

            return findNamingCandidates(summaries)
        }

        private fun findNamingCandidates(summaries: List<ProjectSummary>): List<InterfaceCandidate> =
            summaries
                .flatMap { summary ->
                    summary.sourceSets.flatMap { sourceSet ->
                        sourceSet.symbols
                            .filter { it.kind == SymbolKind.CLASS }
                            .filter { isLikelyInterface(it) }
                            .map { symbol ->
                                val name = symbol.name.value
                                val packageName = symbol.packageName.value
                                InterfaceCandidate(
                                    name = name,
                                    packageName = packageName,
                                    sourceSet = sourceSet.name.value,
                                    qualifiedName =
                                        if (packageName == "_root_") name else "$packageName.$name",
                                    project = summary.projectPath.value,
                                )
                            }
                    }
                }.distinctBy { it.name }

        private fun findPackageName(
            symbol: WorkspaceSymbol,
            summaries: List<ProjectSummary>,
        ): String? =
            summaries
                .asSequence()
                .filter { it.projectPath.value == symbol.project }
                .flatMap { it.sourceSets.asSequence() }
                .filter { it.name.value == symbol.sourceSet }
                .flatMap { it.symbols.asSequence() }
                .filter { it.kind == SymbolKind.CLASS }
                .filter { it.name.value == symbol.name }
                .filter { it.lineNumber == symbol.declarationLine }
                .filter { sameFile(symbol.projectRelativeFile, it.filePath.value) }
                .map { it.packageName.value }
                .filter { it == "_root_" || symbol.qualifiedName.startsWith("$it.") }
                .singleOrNull()

        private fun sameFile(
            projectRelativeFile: String,
            sourceRelativeFile: String,
        ): Boolean {
            val projectFile = projectRelativeFile.replace('\\', '/')
            val sourceFile = sourceRelativeFile.replace('\\', '/')
            return projectFile == sourceFile || projectFile.endsWith("/$sourceFile")
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

        private fun hasNamedMock(interfaceName: String, classNames: Set<String>): Boolean =
            classNames.any { className ->
                className == "Mock$interfaceName" ||
                    className == "${interfaceName}Mock" ||
                    className == "Fake$interfaceName" ||
                    className == "${interfaceName}Fake"
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

        private val interfaceComparator =
            compareByDescending<InterfaceInfo> { it.implementationCount }
                .thenBy { it.build.orEmpty() }
                .thenBy { it.project.orEmpty() }
                .thenBy { it.sourceSet }
                .thenBy { it.qualifiedName }
                .thenBy { it.identity?.value.orEmpty() }
    }
}
