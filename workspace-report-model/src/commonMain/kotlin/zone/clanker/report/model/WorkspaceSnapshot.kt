@file:Suppress("LongParameterList", "TooManyFunctions")

package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** Versioned, renderer-neutral description of one analyzed Gradle workspace. */
@Serializable
data class WorkspaceSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val workspace: WorkspaceIdentity,
    val builds: List<BuildSnapshot>,
    val buildEdges: List<BuildEdgeSnapshot> = emptyList(),
    val projects: List<ProjectSnapshot>,
    val projectDependencies: List<ProjectDependencySnapshot> = emptyList(),
    val sourceSets: List<SourceSetSnapshot>,
    val files: List<SourceFileSnapshot>,
    val symbols: List<SymbolSnapshot>,
    val references: List<ReferenceSnapshot> = emptyList(),
    val relationships: List<RelationshipSnapshot>,
    val relationshipCycles: List<CycleSnapshot> = emptyList(),
    val projectAnalyses: List<ProjectAnalysisSnapshot> = emptyList(),
    val aggregateAnalysisPresent: Boolean = false,
    val aggregateFindings: List<FindingSnapshot> = emptyList(),
    val aggregateHubs: List<HubSnapshot> = emptyList(),
    val aggregateNamedCycles: List<NamedCycleSnapshot> = emptyList(),
    val entryPoints: List<WorkspaceEntryPointSnapshot> = emptyList(),
    val interfaces: List<InterfaceSnapshot> = emptyList(),
    val importantSymbols: List<ImportantSymbolSnapshot> = emptyList(),
    val dependencyInjection: DependencyInjectionSnapshot? = null,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported workspace snapshot schema version: $schemaVersion"
        }
        requireTopLevelOrder()
        val catalog = SnapshotCatalog(this)
        requireBuildOwnership(catalog)
        requireSourceOwnership(catalog)
        requireRelationshipOwnership(catalog)
        requireAnalysisOwnership(catalog)
        requireSummaryOwnership(catalog)
        requireDependencyInjectionOwnership(catalog)
    }

    private fun requireTopLevelOrder() {
        requireUniqueAndSorted(builds.map(BuildSnapshot::id), "builds")
        requireUniqueAndSorted(buildEdges.map(BuildEdgeSnapshot::id), "build edges")
        requireUniqueAndSorted(projects.map(ProjectSnapshot::id), "projects")
        requireUniqueAndSorted(projectDependencies.map(ProjectDependencySnapshot::id), "project dependencies")
        requireUniqueAndSorted(sourceSets.map(SourceSetSnapshot::id), "source sets")
        requireUniqueAndSorted(files.map(SourceFileSnapshot::id), "files")
        requireUniqueAndSorted(symbols.map(SymbolSnapshot::id), "symbols")
        requireUniqueAndSorted(references.map(ReferenceSnapshot::id), "references")
        requireUniqueAndSorted(relationships.map(RelationshipSnapshot::id), "relationships")
        requireUniqueAndSorted(relationshipCycles.map(CycleSnapshot::id), "relationship cycles")
        requireUniqueAndSorted(projectAnalyses.map(ProjectAnalysisSnapshot::id), "project analyses")
        requireUniqueAndSorted(aggregateFindings.map(FindingSnapshot::id), "aggregate findings")
        requireUniqueAndSorted(aggregateHubs.map(HubSnapshot::id), "aggregate hubs")
        requireUniqueAndSorted(aggregateNamedCycles.map(NamedCycleSnapshot::id), "aggregate named cycles")
        requireUniqueAndSorted(entryPoints.map(WorkspaceEntryPointSnapshot::id), "entry points")
        requireUniqueAndSorted(interfaces.map(InterfaceSnapshot::id), "interfaces")
        requireUniqueAndSorted(importantSymbols.map(ImportantSymbolSnapshot::id), "important symbols")
    }

    private fun requireBuildOwnership(catalog: SnapshotCatalog) {
        require(builds.count { it.kind == BuildKind.ROOT } == 1) { "A snapshot must contain exactly one root build" }
        require(builds.single { it.kind == BuildKind.ROOT }.relativePath == ".") {
            "The root build must use '.' as its relative path"
        }
        require(builds.map(BuildSnapshot::name).distinct().size == builds.size) { "Build names must be unique" }
        require(
            buildEdges.all { edge ->
                edge.sourceBuildId in catalog.builds && edge.targetBuildId in catalog.builds
            },
        ) { "Build edges must reference known builds" }
        require(projects.all { it.buildId in catalog.builds }) { "Projects must reference known builds" }
        require(projectDependencies.all { it.projectId in catalog.projects }) {
            "Project dependencies must reference known projects"
        }
    }

    private fun requireSourceOwnership(catalog: SnapshotCatalog) {
        require(sourceSets.all { it.projectId in catalog.projects }) { "Source sets must reference known projects" }
        require(files.all { it.sourceSetId in catalog.sourceSets }) { "Files must reference known source sets" }
        symbols.forEach { symbol ->
            val file = requireNotNull(catalog.files[symbol.fileId]) { "Symbols must reference known files" }
            requireLineWithinContent(symbol.declarationLine, file.content, "Symbol declaration line")
            symbol.declarationRange?.let { range ->
                requireRangeWithinContent(range, symbol.declarationLine, file.content, "Symbol declaration range")
            }
            symbol.ownerSymbolId?.let { ownerId -> requireSymbolOwner(symbol, ownerId, catalog) }
        }
        references.forEach { reference ->
            val file =
                requireNotNull(catalog.files[reference.sourceFileId]) {
                    "References must reference known source files"
                }
            requireLineWithinContent(reference.line, file.content, "Reference line")
            reference.sourceSymbolId?.let { symbolId ->
                require(catalog.symbols[symbolId]?.fileId == reference.sourceFileId) {
                    "Reference source symbols must belong to their source file"
                }
            }
        }
    }

    private fun requireSymbolOwner(
        symbol: SymbolSnapshot,
        ownerId: String,
        catalog: SnapshotCatalog,
    ) {
        val owner = requireNotNull(catalog.symbols[ownerId]) { "Symbol owners must reference known declarations" }
        require(owner.id != symbol.id) { "Symbols must not own themselves" }
        require(owner.fileId == symbol.fileId) { "Symbol owners must belong to the same source file" }
        val ownerRange = owner.declarationRange
        val childRange = symbol.declarationRange
        if (ownerRange != null && childRange != null) {
            require(
                ownerRange.startOffset <= childRange.startOffset &&
                    ownerRange.endOffsetExclusive >= childRange.endOffsetExclusive,
            ) {
                "Symbol owner ranges must contain their declarations: " +
                    "owner=${owner.qualifiedName}(${owner.id})[$ownerRange], " +
                    "child=${symbol.qualifiedName}(${symbol.id})[$childRange]"
            }
        }
    }

    private fun requireRelationshipOwnership(catalog: SnapshotCatalog) {
        relationships.forEach { relationship ->
            val reference =
                requireNotNull(catalog.references[relationship.referenceId]) {
                    "Relationships must reference known source evidence"
                }
            val target =
                requireNotNull(catalog.symbols[relationship.targetSymbolId]) {
                    "Relationships must reference known target symbols"
                }
            require(
                target.name.substringAfterLast('.') == reference.targetName &&
                    (reference.targetQualifiedName == null || target.qualifiedName == reference.targetQualifiedName),
            ) {
                "Relationship targets must match their resolved source evidence"
            }
            require(relationship.kind.accepts(reference.kind)) {
                "Relationship kinds must agree with their source-reference kind"
            }
            if (relationship.kind == RelationshipKind.IMPORT) {
                require(relationship.sourceSymbolId == null) { "Import relationships do not have a source declaration" }
            } else {
                require(relationship.sourceSymbolId != null) {
                    "Non-import relationships require a source declaration"
                }
                require(relationship.sourceSymbolId == reference.sourceSymbolId) {
                    "Relationship source declarations must match their source evidence"
                }
            }
        }
        relationshipCycles.forEach { cycle -> requireRelationshipCycle(cycle, catalog) }
    }

    private fun requireRelationshipCycle(
        cycle: CycleSnapshot,
        catalog: SnapshotCatalog,
    ) {
        require(cycle.symbolIds.all(catalog.symbols::containsKey)) {
            "Relationship cycles must reference known symbols"
        }
        cycle.relationshipIds.zip(cycle.symbolIds.zipWithNext()).forEach { (relationshipId, step) ->
            val relationship =
                requireNotNull(catalog.relationships[relationshipId]) {
                    "Relationship cycles must reference known relationship evidence"
                }
            require(relationship.kind != RelationshipKind.IMPORT) {
                "Relationship cycles cannot use import-only evidence"
            }
            require(relationship.sourceSymbolId == step.first && relationship.targetSymbolId == step.second) {
                "Relationship-cycle evidence must follow its directed symbol route"
            }
        }
    }

    private fun requireAnalysisOwnership(catalog: SnapshotCatalog) {
        require(projectAnalyses.all { it.projectId in catalog.projects }) {
            "Project analyses must reference known projects"
        }
        require(projectAnalyses.map(ProjectAnalysisSnapshot::projectId).distinct().size == projectAnalyses.size) {
            "A project may have at most one analysis"
        }
        projectAnalyses.forEach { analysis ->
            analysis.components.forEach { component ->
                requireComponentOwnership(component, analysis.projectId, catalog)
            }
            analysis.findings.forEach { finding -> requireFindingOwnership(finding, analysis.projectId, catalog) }
            analysis.hubs.forEach { hub -> requireHubOwnership(hub, analysis.projectId, catalog) }
        }
        aggregateHubs.forEach { hub ->
            hub.sourceFileId?.let { fileId ->
                val file = requireNotNull(catalog.files[fileId]) { "Aggregate hubs must reference known files" }
                hub.line?.let { line -> requireLineWithinContent(line, file.content, "Aggregate hub line") }
            }
        }
        val hasNoAggregateFacts =
            aggregateFindings.isEmpty() && aggregateHubs.isEmpty() && aggregateNamedCycles.isEmpty()
        require(aggregateAnalysisPresent || hasNoAggregateFacts) {
            "Aggregate facts require an aggregate analysis boundary"
        }
        require(
            aggregateFindings.all {
                it.fileId == null && it.resolvedComponentIds.isEmpty() && it.symbolIds.isEmpty()
            },
        ) {
            "Aggregate findings retain raw analyzer scope rather than project-resolved identities"
        }
    }

    private fun requireComponentOwnership(
        component: ArchitectureComponentSnapshot,
        projectId: String,
        catalog: SnapshotCatalog,
    ) {
        component.sourceFileId?.let { fileId ->
            require(catalog.projectIdForFile(fileId) == projectId) {
                "Architecture component files must belong to their project analysis"
            }
            requireLineWithinContent(component.line, catalog.files.getValue(fileId).content, "Component line")
        }
        component.symbolId?.let { symbolId ->
            val symbol =
                requireNotNull(catalog.symbols[symbolId]) {
                    "Architecture components must reference known symbols"
                }
            require(component.sourceFileId == null || symbol.fileId == component.sourceFileId) {
                "Architecture component symbols must belong to their source file"
            }
        }
    }

    private fun requireFindingOwnership(
        finding: FindingSnapshot,
        projectId: String,
        catalog: SnapshotCatalog,
    ) {
        finding.fileId?.let { fileId ->
            require(catalog.projectIdForFile(fileId) == projectId) {
                "Finding files must belong to their project analysis"
            }
            finding.line?.let { line ->
                requireLineWithinContent(line, catalog.files.getValue(fileId).content, "Finding line")
            }
        }
        require(
            finding.symbolIds.all { symbolId ->
                catalog.symbols[symbolId]?.fileId?.let(catalog::projectIdForFile) == projectId
            },
        ) { "Finding symbols must belong to their project analysis" }
    }

    private fun requireHubOwnership(
        hub: HubSnapshot,
        projectId: String,
        catalog: SnapshotCatalog,
    ) {
        hub.sourceFileId?.let { fileId ->
            require(catalog.projectIdForFile(fileId) == projectId) {
                "Project hub files must belong to their project analysis"
            }
            hub.line?.let { line ->
                requireLineWithinContent(line, catalog.files.getValue(fileId).content, "Hub line")
            }
        }
    }

    private fun requireSummaryOwnership(catalog: SnapshotCatalog) {
        entryPoints.forEach { entryPoint ->
            entryPoint.projectId?.let {
                require(it in catalog.projects) { "Entry points must reference known projects" }
            }
            entryPoint.symbolId?.let { symbolId ->
                val symbol =
                    requireNotNull(catalog.symbols[symbolId]) {
                        "Entry points must reference known symbols"
                    }
                require(
                    entryPoint.projectId == null || catalog.projectIdForFile(symbol.fileId) == entryPoint.projectId,
                ) { "Entry-point symbols must belong to their project" }
            }
        }
        interfaces.forEach { contract ->
            contract.buildId?.let { require(it in catalog.builds) { "Interfaces must reference known builds" } }
            contract.projectId?.let { projectId ->
                require(catalog.projects[projectId]?.buildId == contract.buildId) {
                    "Interface projects must belong to their build"
                }
            }
            contract.symbolId?.let { symbolId ->
                val symbol =
                    requireNotNull(catalog.symbols[symbolId]) {
                        "Interfaces must reference known symbols"
                    }
                require(contract.projectId == null || catalog.projectIdForFile(symbol.fileId) == contract.projectId) {
                    "Interface symbols must belong to their project"
                }
            }
        }
        require(importantSymbols.all { it.symbolId in catalog.symbols }) {
            "Important symbols must reference known declarations"
        }
        importantSymbols.forEach { important -> requireImportantSymbolUsage(important, catalog) }
    }

    private fun requireImportantSymbolUsage(
        important: ImportantSymbolSnapshot,
        catalog: SnapshotCatalog,
    ) {
        val usage = important.usage
        val incoming = usage.incomingRelationshipIds.map { relationshipId -> catalog.relationship(relationshipId) }
        val outgoing = usage.outgoingRelationshipIds.map { relationshipId -> catalog.relationship(relationshipId) }
        require(incoming.all { it.targetSymbolId == important.symbolId }) {
            "Important-symbol incoming usage must target the declaration"
        }
        require(outgoing.all { it.kind != RelationshipKind.IMPORT && it.sourceSymbolId == important.symbolId }) {
            "Important-symbol outgoing usage must be non-import evidence sourced by the declaration"
        }
        val countedIncoming = incoming.filter { relationship -> relationship.kind != RelationshipKind.IMPORT }
        require(usage.workspaceInboundCount == countedIncoming.size) {
            "Important-symbol workspace inbound count must equal its non-import incoming evidence"
        }
        val importantBuildId = catalog.buildIdForSymbol(important.symbolId)
        val importantProjectId = catalog.projectIdForSymbol(important.symbolId)
        val localInboundCount =
            countedIncoming.count { relationship ->
                val sourceId = requireNotNull(relationship.sourceSymbolId)
                catalog.buildIdForSymbol(sourceId) == importantBuildId &&
                    catalog.projectIdForSymbol(sourceId) == importantProjectId
            }
        val crossBuildInboundCount =
            countedIncoming.count { relationship ->
                catalog.buildIdForSymbol(requireNotNull(relationship.sourceSymbolId)) != importantBuildId
            }
        require(usage.localInboundCount == localInboundCount) {
            "Important-symbol local inbound count must agree with its relationship evidence"
        }
        require(usage.crossBuildInboundCount == crossBuildInboundCount) {
            "Important-symbol cross-build inbound count must agree with its relationship evidence"
        }
    }

    private fun requireDependencyInjectionOwnership(catalog: SnapshotCatalog) {
        require(
            dependencyInjection
                ?.bindings
                ?.mapNotNull(DependencyInjectionBindingSnapshot::declaringSymbolId)
                ?.all(catalog.symbols::containsKey) != false,
        ) { "Dependency-injection bindings must reference known declarations" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

private class SnapshotCatalog(
    snapshot: WorkspaceSnapshot,
) {
    val builds = snapshot.builds.associateBy(BuildSnapshot::id)
    val projects = snapshot.projects.associateBy(ProjectSnapshot::id)
    val sourceSets = snapshot.sourceSets.associateBy(SourceSetSnapshot::id)
    val files = snapshot.files.associateBy(SourceFileSnapshot::id)
    val symbols = snapshot.symbols.associateBy(SymbolSnapshot::id)
    val references = snapshot.references.associateBy(ReferenceSnapshot::id)
    val relationships = snapshot.relationships.associateBy(RelationshipSnapshot::id)

    fun relationship(id: String): RelationshipSnapshot =
        requireNotNull(relationships[id]) { "Important-symbol usage must reference known relationships" }

    fun projectIdForFile(fileId: String): String? =
        files[fileId]
            ?.sourceSetId
            ?.let(sourceSets::get)
            ?.projectId

    fun projectIdForSymbol(symbolId: String): String? = symbols[symbolId]?.fileId?.let(::projectIdForFile)

    fun buildIdForSymbol(symbolId: String): String? =
        projectIdForSymbol(symbolId)
            ?.let(projects::get)
            ?.buildId
}

internal fun RelationshipKind.accepts(referenceKind: ReferenceKind): Boolean =
    when (referenceKind) {
        ReferenceKind.SUPERTYPE -> this == RelationshipKind.EXTENDS || this == RelationshipKind.IMPLEMENTS
        else -> name == referenceKind.name
    }
