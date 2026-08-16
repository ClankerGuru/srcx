@file:Suppress("LongMethod", "TooManyFunctions")

package zone.clanker.gradle.docx.site

import zone.clanker.gradle.docx.DependencyInjectionFrameworkSelection
import zone.clanker.gradle.docx.DocxAnalysisPlan
import zone.clanker.gradle.docx.DocxBuildScopePlan
import zone.clanker.gradle.docx.DocxFeature
import zone.clanker.gradle.docx.DocxFeaturePlan
import zone.clanker.gradle.docx.GeneratedSourcePolicy
import zone.clanker.gradle.docx.GraphDepthMode
import zone.clanker.gradle.docx.MissingCapabilityPolicy
import zone.clanker.gradle.docx.NameSelectionPlan
import zone.clanker.gradle.docx.RelationshipKindSelection
import zone.clanker.gradle.docx.UnknownSelectionPolicy
import zone.clanker.report.model.ArchitectureComponentSnapshot
import zone.clanker.report.model.BuildEdgeSnapshot
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectAnalysisSnapshot
import zone.clanker.report.model.ProjectDependencySnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.ReferenceKind
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceSnapshot
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.WorkspaceSummaryShard

internal data class ProjectedDocxSite(
    val workspace: WorkspaceIdentity,
    val builds: List<BuildSnapshot>,
    val buildEdges: List<BuildEdgeSnapshot>,
    val projects: List<ProjectSnapshot>,
    val projectDependencies: List<ProjectDependencySnapshot>,
    val projectGraphs: List<ProjectGraphShard>,
    val sourceContents: List<ProjectedSourceContent>,
    val sourceSets: List<WorkspaceSourceSetSummary>,
    val warnings: List<String>,
) {
    fun summary(projectShards: List<ProjectShardReference>): WorkspaceSummaryShard =
        WorkspaceSummaryShard(
            workspace = workspace,
            builds = builds,
            projects = projects,
            sourceSets = sourceSets,
            projectShards = projectShards,
            buildEdges = buildEdges,
        )
}

internal object DocxSiteProjection {
    fun apply(
        snapshot: WorkspaceSnapshot,
        plan: DocxAnalysisPlan,
    ): ProjectedDocxSite {
        validateFirstSliceOptions(plan)
        val capabilityWarnings = capabilityWarnings(snapshot, plan)
        val catalog = DocxProjectionCatalog(snapshot)
        val scope = ScopeResolver(catalog, snapshot.buildEdges, plan)
        val featurePlans = plan.features.associateBy(DocxFeaturePlan::feature)
        val projectedGraphs =
            scope.projects.map { project ->
                projectGraph(catalog, featurePlans, project, scope.sourceSetsFor(project.id))
            }
        val separatedGraphs = projectedGraphs.separateSourceContents()
        val selectedProjectIds = scope.projects.mapTo(mutableSetOf(), ProjectSnapshot::id)
        return ProjectedDocxSite(
            workspace = snapshot.workspace,
            builds = scope.builds,
            buildEdges = scope.buildEdges,
            projects = scope.projects,
            projectDependencies =
                snapshot.projectDependencies.filter { dependency ->
                    dependency.projectId in selectedProjectIds
                },
            projectGraphs = separatedGraphs.graphs,
            sourceContents = separatedGraphs.contents,
            sourceSets = separatedGraphs.graphs.workspaceSourceSetSummaries(),
            warnings = (scope.warnings + capabilityWarnings).sorted(),
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun projectGraph(
        catalog: DocxProjectionCatalog,
        featurePlans: Map<DocxFeature, DocxFeaturePlan>,
        project: ProjectSnapshot,
        ownedSourceSets: List<SourceSetSnapshot>,
    ): ProjectGraphShard {
        val ownedSourceSetIds = ownedSourceSets.mapTo(mutableSetOf(), SourceSetSnapshot::id)
        val ownedFiles = ownedSourceSetIds.flatMap { sourceSetId -> catalog.filesBySourceSetId[sourceSetId].orEmpty() }
        val ownedFileIds = ownedFiles.mapTo(mutableSetOf(), SourceFileSnapshot::id)
        val ownedSymbols = ownedFileIds.flatMap { fileId -> catalog.symbolsByFileId[fileId].orEmpty() }
        val ownedSymbolIds = ownedSymbols.mapTo(mutableSetOf(), SymbolSnapshot::id)
        val includeTestAnalysis = ownedSourceSets.any { sourceSet -> sourceSet.name.isTestSourceSetName() }
        val projectAnalysis = catalog.projectAnalysisByProjectId[project.id]
        val scopedAnalysisComponents =
            projectAnalysis
                ?.components
                .orEmpty()
                .filter { component ->
                    (!component.isTest || includeTestAnalysis) &&
                        component.sourceFileId?.let(ownedFileIds::contains) != false &&
                        component.symbolId?.let(ownedSymbolIds::contains) != false
                }
        val scopedAnalysisComponentIds =
            scopedAnalysisComponents.mapTo(mutableSetOf()) { component -> component.id }
        val relationshipFeature = featurePlans.getValue(DocxFeature.RELATIONSHIPS)
        val cycleFeature = featurePlans.getValue(DocxFeature.CYCLES)
        val relationshipKinds = relationshipFeature.selectedRelationshipKinds()
        val cycleKinds = cycleFeature.selectedRelationshipKinds()
        val regularRelationships =
            if (relationshipFeature.enabled) {
                ownedFileIds
                    .flatMap { fileId -> catalog.relationshipsBySourceFileId[fileId].orEmpty() }
                    .filter { relationship -> relationship.kind in relationshipKinds }
            } else {
                emptyList()
            }
        val cycles =
            selectedCycles(
                catalog = catalog,
                enabled = cycleFeature.enabled,
                allowedKinds = cycleKinds,
                ownedSymbolIds = ownedSymbolIds,
            )
        val cycleRelationshipIds = cycles.flatMapTo(mutableSetOf(), CycleSnapshot::relationshipIds)
        val cycleRelationships = cycleRelationshipIds.mapNotNull(catalog.relationshipById::get)
        val relationships = (regularRelationships + cycleRelationships).distinctBy { it.id }.sortedBy { it.id }
        val visibleReferenceIds = relationships.mapTo(mutableSetOf(), RelationshipSnapshot::referenceId)
        val visibleReferences =
            (
                visibleReferenceIds.mapNotNull(catalog.referenceById::get) +
                    if (relationshipFeature.enabled) {
                        ownedFileIds
                            .flatMap { fileId -> catalog.referencesBySourceFileId[fileId].orEmpty() }
                            .filter { reference -> reference.matchesAny(relationshipKinds) }
                    } else {
                        emptyList()
                    }
            ).distinctBy(ReferenceSnapshot::id).sortedBy(ReferenceSnapshot::id)
        val findingsFeature = featurePlans.getValue(DocxFeature.FINDINGS)
        val allowedSeverities = findingsFeature.selectedFindingSeverities()
        val findings =
            if (findingsFeature.enabled) {
                projectAnalysis
                    ?.findings
                    .orEmpty()
                    .filter { finding ->
                        finding.severity in allowedSeverities &&
                            finding.fileId?.let(ownedFileIds::contains) != false &&
                            finding.symbolIds.all(ownedSymbolIds::contains) &&
                            finding.resolvedComponentIds.all(scopedAnalysisComponentIds::contains)
                    }
            } else {
                emptyList()
            }
        val symbolsFeature = featurePlans.getValue(DocxFeature.SYMBOLS)
        val includeAllOwnedSymbols = symbolsFeature.enabled && symbolsFeature.includeDisconnected != false
        val visibleSymbolIds =
            buildSet {
                relationships.forEach { relationship ->
                    relationship.sourceSymbolId?.let(::add)
                    add(relationship.targetSymbolId)
                }
                visibleReferences.mapNotNullTo(this) { it.sourceSymbolId }
                findings.forEach { finding -> addAll(finding.symbolIds) }
                cycles.forEach { cycle -> addAll(cycle.symbolIds) }
            }
        val projectedAnalysis =
            projectAnalysis?.projectForShard(
                findings = findings,
                scopedComponents = scopedAnalysisComponents,
                ownedFileIds = ownedFileIds,
                includeTestAnalysis = includeTestAnalysis,
                symbolsEnabled = symbolsFeature.enabled,
                includeDisconnectedSymbols = symbolsFeature.includeDisconnected != false,
                visibleSymbolIds = visibleSymbolIds,
                relationshipsEnabled = relationshipFeature.enabled,
                cyclesEnabled = cycleFeature.enabled,
            )
        val includedSymbolIds =
            buildSet {
                if (includeAllOwnedSymbols) addAll(ownedSymbolIds)
                addAll(visibleSymbolIds)
                projectedAnalysis?.components?.mapNotNullTo(this) { component -> component.symbolId }
            }
        val filesFeature = featurePlans.getValue(DocxFeature.FILES)
        val sourcesFeature = featurePlans.getValue(DocxFeature.SOURCES)
        val includeAllOwnedFiles = filesFeature.enabled || sourcesFeature.enabled || includeAllOwnedSymbols
        val includedFileIds =
            buildSet {
                if (includeAllOwnedFiles) addAll(ownedFileIds)
                visibleReferences.mapTo(this) { it.sourceFileId }
                includedSymbolIds.mapNotNullTo(this) { catalog.symbolById[it]?.fileId }
                findings.mapNotNullTo(this) { it.fileId }
                projectedAnalysis?.components?.mapNotNullTo(this) { component -> component.sourceFileId }
                projectedAnalysis?.hubs?.mapNotNullTo(this) { hub -> hub.sourceFileId }
            }
        val includedSourceSetIds =
            includedFileIds.mapNotNullTo(mutableSetOf()) { fileId -> catalog.fileById[fileId]?.sourceSetId }
        includedSourceSetIds.addAll(ownedSourceSetIds)
        val includeSourceContent = sourcesFeature.enabled && sourcesFeature.includeSourceContent == true
        return ProjectGraphShard(
            projectId = project.id,
            sourceSets = includedSourceSetIds.mapNotNull(catalog.sourceSetById::get).sortedBy { it.id },
            files =
                includedFileIds
                    .mapNotNull(catalog.fileById::get)
                    .map { file -> file.withProjectedContent(ownedFileIds, includeSourceContent) }
                    .sortedBy { it.id },
            symbols = includedSymbolIds.mapNotNull(catalog.symbolById::get).sortedBy { it.id },
            ownedSourceSetIds = ownedSourceSetIds.sorted(),
            ownedFileIds = includedFileIds.filter(ownedFileIds::contains).sorted(),
            ownedSymbolIds = includedSymbolIds.filter(ownedSymbolIds::contains).sorted(),
            references = visibleReferences,
            relationships = relationships,
            findings = findings.sortedBy { it.id },
            cycles = cycles,
            analysis = projectedAnalysis,
        )
    }

    @Suppress("LongParameterList")
    private fun selectedCycles(
        catalog: DocxProjectionCatalog,
        enabled: Boolean,
        allowedKinds: Set<RelationshipKind>,
        ownedSymbolIds: Set<String>,
    ): List<CycleSnapshot> {
        if (!enabled) return emptyList()
        return ownedSymbolIds
            .asSequence()
            .flatMap { symbolId -> catalog.cyclesByMemberSymbolId[symbolId].orEmpty().asSequence() }
            .distinctBy(CycleSnapshot::id)
            .filter { cycle ->
                cycle.relationshipIds.all { relationshipId ->
                    catalog.relationshipById[relationshipId]?.let { relationship ->
                        relationship.kind in allowedKinds
                    } == true
                }
            }.sortedBy(CycleSnapshot::id)
            .toList()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
    private fun ProjectAnalysisSnapshot.projectForShard(
        findings: List<FindingSnapshot>,
        scopedComponents: List<ArchitectureComponentSnapshot>,
        ownedFileIds: Set<String>,
        includeTestAnalysis: Boolean,
        symbolsEnabled: Boolean,
        includeDisconnectedSymbols: Boolean,
        visibleSymbolIds: Set<String>,
        relationshipsEnabled: Boolean,
        cyclesEnabled: Boolean,
    ): ProjectAnalysisSnapshot? {
        val scopedComponentIds = scopedComponents.mapTo(mutableSetOf(), ArchitectureComponentSnapshot::id)
        val scopedDependencies =
            dependencies.filter { dependency ->
                dependency.sourceComponentId in scopedComponentIds &&
                    dependency.targetComponentId in scopedComponentIds
            }
        val scopedDependencySteps =
            scopedDependencies.mapTo(mutableSetOf()) { dependency ->
                dependency.sourceComponentId to dependency.targetComponentId
            }
        val projectedCycles =
            if (cyclesEnabled) {
                cycles.filter { cycle ->
                    cycle.componentIds.all(scopedComponentIds::contains) &&
                        cycle.componentIds.zipWithNext().all(scopedDependencySteps::contains)
                }
            } else {
                emptyList()
            }
        val cycleSteps = projectedCycles.flatMapTo(mutableSetOf()) { cycle -> cycle.componentIds.zipWithNext() }
        val projectedDependencies =
            scopedDependencies.filter { dependency ->
                relationshipsEnabled ||
                    (dependency.sourceComponentId to dependency.targetComponentId) in cycleSteps
            }
        val projectedEntryPoints =
            if (symbolsEnabled) {
                entryPoints.filter { entryPoint -> entryPoint.componentId in scopedComponentIds }
            } else {
                emptyList()
            }
        val projectedHubs =
            if (relationshipsEnabled) {
                hubs.filter { hub ->
                    (!hub.isTest || includeTestAnalysis) &&
                        hub.sourceFileId?.let(ownedFileIds::contains) != false
                }
            } else {
                emptyList()
            }
        val projectedComponentIds =
            buildSet {
                if (symbolsEnabled) {
                    if (includeDisconnectedSymbols) {
                        addAll(scopedComponentIds)
                    } else {
                        scopedComponents
                            .filter { component -> component.symbolId?.let(visibleSymbolIds::contains) == true }
                            .mapTo(this, ArchitectureComponentSnapshot::id)
                    }
                    projectedEntryPoints.mapTo(this) { entryPoint -> entryPoint.componentId }
                }
                findings.flatMapTo(this, FindingSnapshot::resolvedComponentIds)
                projectedDependencies.forEach { dependency ->
                    add(dependency.sourceComponentId)
                    add(dependency.targetComponentId)
                }
                projectedCycles.flatMapTo(this) { cycle -> cycle.componentIds }
            }
        val projection =
            copy(
                findings = findings.sortedBy { it.id },
                hubs = projectedHubs.sortedBy { it.id },
                components =
                    scopedComponents
                        .filter { component -> component.id in projectedComponentIds }
                        .sortedBy { it.id },
                dependencies = projectedDependencies.sortedBy { it.id },
                entryPoints = projectedEntryPoints.sortedBy { it.id },
                cycles = projectedCycles.sortedBy { it.id },
                legacyNameCycles =
                    if (cyclesEnabled) {
                        legacyNameCycles.sortedBy { it.id }
                    } else {
                        emptyList()
                    },
            )
        return projection.takeIf { it.hasProjectedFacts() }
    }

    private fun validateFirstSliceOptions(plan: DocxAnalysisPlan) {
        val hopFeatures =
            plan.features
                .filter { feature -> feature.enabled && feature.depth?.mode == GraphDepthMode.HOPS }
                .map(DocxFeaturePlan::feature)
        require(hopFeatures.isEmpty()) {
            "Graph hop limits are not implemented by the first DOCX static checkpoint: " +
                hopFeatures.joinToString()
        }
        val dependencyInjection = plan.features.single { it.feature == DocxFeature.DEPENDENCY_INJECTION }
        if (!dependencyInjection.enabled) return
        require(dependencyInjection.includeDisconnected == true) {
            "Dependency-injection includeDisconnected filtering is not implemented by the first DOCX static checkpoint"
        }
        require(
            dependencyInjection.relationshipKinds == RelationshipKindSelection.entries.sortedBy(Enum<*>::name),
        ) { "Dependency-injection relationship-kind filtering is not implemented by the first DOCX static checkpoint" }
        require(
            dependencyInjection.dependencyInjectionFrameworks ==
                DependencyInjectionFrameworkSelection.entries.sortedBy(Enum<*>::name),
        ) { "Dependency-injection framework filtering is not implemented by the first DOCX static checkpoint" }
        require(dependencyInjection.generatedSources == GeneratedSourcePolicy.INCLUDE) {
            "Dependency-injection generated-source filtering is not implemented by the first DOCX static checkpoint"
        }
    }

    private fun capabilityWarnings(
        snapshot: WorkspaceSnapshot,
        plan: DocxAnalysisPlan,
    ): List<String> {
        val dependencyInjection = plan.features.single { it.feature == DocxFeature.DEPENDENCY_INJECTION }
        if (!dependencyInjection.enabled) return emptyList()
        if (snapshot.dependencyInjection != null) {
            return listOf(
                "Dependency-injection facts are present but omitted because the first DOCX static viewer " +
                    "does not render them yet.",
            )
        }
        val message =
            "Dependency-injection capability is unavailable in this workspace snapshot; " +
                "the dependency-injection view was omitted."
        require(plan.missingCapabilities != MissingCapabilityPolicy.FAIL) { message }
        return listOf(message)
    }
}

private fun List<ProjectGraphShard>.workspaceSourceSetSummaries(): List<WorkspaceSourceSetSummary> =
    flatMap { graph ->
        val ownedSourceSetIds = graph.ownedSourceSetIds.toSet()
        val ownedFileIds = graph.ownedFileIds.toSet()
        val fileCounts =
            graph.files
                .filter { file -> file.id in ownedFileIds }
                .groupingBy(SourceFileSnapshot::sourceSetId)
                .eachCount()
        graph.sourceSets
            .filter { sourceSet -> sourceSet.id in ownedSourceSetIds }
            .map { sourceSet ->
                WorkspaceSourceSetSummary(
                    id = sourceSet.id,
                    projectId = sourceSet.projectId,
                    name = sourceSet.name,
                    fileCount = fileCounts[sourceSet.id] ?: 0,
                )
            }
    }.sortedBy(WorkspaceSourceSetSummary::id)

private fun ProjectAnalysisSnapshot.hasProjectedFacts(): Boolean =
    findings.isNotEmpty() ||
        hubs.isNotEmpty() ||
        components.isNotEmpty() ||
        dependencies.isNotEmpty() ||
        entryPoints.isNotEmpty() ||
        cycles.isNotEmpty() ||
        legacyNameCycles.isNotEmpty()

private class ScopeResolver(
    private val catalog: DocxProjectionCatalog,
    workspaceBuildEdges: List<BuildEdgeSnapshot>,
    private val plan: DocxAnalysisPlan,
) {
    private val configuredBuilds = plan.scope.configuredBuilds.associateBy(DocxBuildScopePlan::name)
    private val selectedBuildIds =
        catalog.builds
            .filter { plan.scope.builds.matches(it.name) }
            .mapTo(mutableSetOf(), BuildSnapshot::id)

    private val unknownWarnings: List<String> = unknownSelectionWarnings().also(::enforceUnknownSelectionPolicy)
    private val candidateProjects: List<ProjectSnapshot> =
        catalog.projects.filter { project ->
            project.buildId in selectedBuildIds && projectSelection(project).matches(project.path)
        }
    private val candidateProjectIds = candidateProjects.mapTo(mutableSetOf(), ProjectSnapshot::id)
    private val selectedSourceSets: List<SourceSetSnapshot> =
        catalog.sourceSets.filter { sourceSet ->
            if (sourceSet.projectId !in candidateProjectIds) return@filter false
            val project = catalog.projectById.getValue(sourceSet.projectId)
            sourceSetSelection(project).matches(sourceSet.name) &&
                (includeTests(project) || !sourceSet.name.isTestSourceSetName())
        }
    private val selectedSourceSetsByProjectId = selectedSourceSets.groupBy(SourceSetSnapshot::projectId)
    private val selectedProjectIds = selectedSourceSets.mapTo(mutableSetOf(), SourceSetSnapshot::projectId)
    val projects: List<ProjectSnapshot> = candidateProjects.filter { it.id in selectedProjectIds }
    val warnings: List<String> =
        (
            unknownWarnings +
                candidateProjects
                    .filterNot { it.id in selectedProjectIds }
                    .map { project ->
                        "DOCX project ${project.path} was omitted because no source sets matched its scope."
                    }
        ).sorted()
    private val displayedBuildIds = selectedBuildIds + catalog.builds.single { it.kind.name == "ROOT" }.id

    val builds: List<BuildSnapshot> = catalog.builds.filter { it.id in displayedBuildIds }
    val buildEdges: List<BuildEdgeSnapshot> =
        workspaceBuildEdges.filter { edge ->
            edge.sourceBuildId in displayedBuildIds && edge.targetBuildId in displayedBuildIds
        }

    fun sourceSetsFor(projectId: String): List<SourceSetSnapshot> =
        selectedSourceSetsByProjectId[projectId].orEmpty()

    private fun projectSelection(project: ProjectSnapshot): NameSelectionPlan =
        configuredBuild(project)?.projects ?: plan.scope.projects

    private fun sourceSetSelection(project: ProjectSnapshot): NameSelectionPlan =
        configuredBuild(project)?.sourceSets ?: plan.scope.sourceSets

    private fun includeTests(project: ProjectSnapshot): Boolean =
        configuredBuild(project)?.includeTests ?: plan.scope.includeTests

    private fun configuredBuild(project: ProjectSnapshot): DocxBuildScopePlan? =
        catalog.buildById[project.buildId]?.name?.let(configuredBuilds::get)

    private fun unknownSelectionWarnings(): List<String> =
        buildList {
            addUnknown("build", plan.scope.builds, catalog.buildNames)
            addAll(
                (configuredBuilds.keys - catalog.buildNames)
                    .sorted()
                    .map { "Unknown DOCX configured build selection: $it" },
            )
            addUnknown("project", plan.scope.projects, catalog.projectPaths)
            addUnknown("source-set", plan.scope.sourceSets, catalog.sourceSetNames)
            configuredBuilds.toSortedMap().forEach { (buildName, configured) ->
                val build = catalog.buildByName[buildName] ?: return@forEach
                val buildProjects = catalog.projectsByBuildId[build.id].orEmpty()
                val projectNames = buildProjects.mapTo(mutableSetOf(), ProjectSnapshot::path)
                val sourceSetNames =
                    buildProjects
                        .flatMap { project -> catalog.sourceSetsByProjectId[project.id].orEmpty() }
                        .mapTo(mutableSetOf(), SourceSetSnapshot::name)
                addUnknown("project in build $buildName", configured.projects, projectNames)
                addUnknown("source-set in build $buildName", configured.sourceSets, sourceSetNames)
            }
        }.sorted()

    private fun MutableList<String>.addUnknown(
        label: String,
        selection: NameSelectionPlan,
        available: Set<String>,
    ) {
        ((selection.included + selection.excluded).toSet() - available).sorted().forEach { name ->
            add("Unknown DOCX $label selection: $name")
        }
    }

    private fun enforceUnknownSelectionPolicy(messages: List<String>) {
        if (messages.isNotEmpty() && plan.scope.unknownSelections == UnknownSelectionPolicy.FAIL) {
            throw IllegalArgumentException(messages.joinToString(separator = "; "))
        }
    }
}

private fun NameSelectionPlan.matches(name: String): Boolean =
    (all || name in included) && name !in excluded

private fun String.isTestSourceSetName(): Boolean = contains("test", ignoreCase = true)

private fun DocxFeaturePlan.selectedRelationshipKinds(): Set<RelationshipKind> =
    relationshipKinds.mapTo(mutableSetOf()) { selection ->
        RelationshipKind.valueOf(selection.name)
    }

private fun DocxFeaturePlan.selectedFindingSeverities(): Set<FindingSeverity> =
    severities.mapTo(mutableSetOf()) { selection ->
        FindingSeverity.valueOf(selection.name)
    }

private fun ReferenceSnapshot.matchesAny(kinds: Set<RelationshipKind>): Boolean =
    when (kind) {
        ReferenceKind.SUPERTYPE -> RelationshipKind.EXTENDS in kinds || RelationshipKind.IMPLEMENTS in kinds
        else -> kinds.any { selected -> selected.name == kind.name }
    }

private fun SourceFileSnapshot.withProjectedContent(
    ownedFileIds: Set<String>,
    includeSourceContent: Boolean,
): SourceFileSnapshot =
    if (id in ownedFileIds && includeSourceContent) this else copy(content = null)
