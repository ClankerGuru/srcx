@file:Suppress("LongMethod", "TooManyFunctions")

package zone.clanker.gradle.srcx.snapshot

import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArchitectureComponent
import zone.clanker.gradle.srcx.model.ArchitectureEntryPoint
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.InterfaceSummary
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.report.model.ArchitectureComponentSnapshot
import zone.clanker.report.model.ArchitectureCycleSnapshot
import zone.clanker.report.model.ArchitectureDependencySnapshot
import zone.clanker.report.model.ArchitectureEntryPointKind
import zone.clanker.report.model.ArchitectureEntryPointSnapshot
import zone.clanker.report.model.ArchitectureLayer
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.HubDependentSnapshot
import zone.clanker.report.model.HubSnapshot
import zone.clanker.report.model.ImportantReasonSnapshot
import zone.clanker.report.model.ImportantSymbolReason
import zone.clanker.report.model.ImportantSymbolSnapshot
import zone.clanker.report.model.ImportantSymbolUsageSnapshot
import zone.clanker.report.model.InterfaceSnapshot
import zone.clanker.report.model.NamedCycleSnapshot
import zone.clanker.report.model.ProjectAnalysisSnapshot
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.WorkspaceEntryPointKind
import zone.clanker.report.model.WorkspaceEntryPointSnapshot

internal fun SnapshotMappingContext.projectAnalysisSnapshots(): List<ProjectAnalysisSnapshot> =
    projectScopes
        .mapNotNull { scope -> scope.summary.analysis?.let { analysis -> projectAnalysisSnapshot(scope, analysis) } }
        .sortedBy(ProjectAnalysisSnapshot::id)

private fun SnapshotMappingContext.projectAnalysisSnapshot(
    scope: SnapshotProjectScope,
    analysis: AnalysisSummary,
): ProjectAnalysisSnapshot {
    val componentRecords = componentSnapshots(scope, analysis)
    val componentIds = componentRecords.associate { it.source.id to it.snapshot.id }
    val symbolIdsByComponent =
        componentRecords.mapNotNull { record -> record.snapshot.symbolId?.let { record.source.id to it } }.toMap()
    return ProjectAnalysisSnapshot(
        id = stableSnapshotId("project-analysis", scope.id),
        projectId = scope.id,
        findings = findingSnapshots(scope, analysis.findings, componentIds, symbolIdsByComponent),
        hubs = hubSnapshots(scope, analysis.hubs),
        components = componentRecords.map(ComponentRecord::snapshot).sortedBy(ArchitectureComponentSnapshot::id),
        dependencies = architectureDependencies(scope, analysis, componentIds),
        entryPoints = architectureEntryPoints(scope, analysis, componentIds),
        cycles = architectureCycles(scope, analysis, componentIds),
        legacyNameCycles = namedCycles(scope, analysis),
    )
}

private fun SnapshotMappingContext.componentSnapshots(
    scope: SnapshotProjectScope,
    analysis: AnalysisSummary,
): List<ComponentRecord> =
    analysis.architecture.components
        .sortedBy { component -> componentCanonicalKey(component) }
        .map { component ->
            val fileId = fileIdFor(scope, component.filePath, component.isTest)
            val symbol = symbolForComponent(scope, component, fileId)
            ComponentRecord(
                source = component,
                snapshot =
                    ArchitectureComponentSnapshot(
                        id = stableSnapshotId("architecture-component", scope.id, component.id),
                        componentId = component.id,
                        name = component.name,
                        packageName = component.packageName,
                        packageGroup = component.packageGroup,
                        role = component.role,
                        layer = ArchitectureLayer.valueOf(component.layer.name),
                        filePath = component.filePath.normalizedAnalysisPath(),
                        line = component.line,
                        isTest = component.isTest,
                        sourceFileId = fileId,
                        symbolId = symbol?.let(::symbolId),
                    ),
            )
        }

private fun SnapshotMappingContext.architectureDependencies(
    scope: SnapshotProjectScope,
    analysis: AnalysisSummary,
    componentIds: Map<String, String>,
): List<ArchitectureDependencySnapshot> {
    val counts = mutableMapOf<String, Int>()
    return analysis.architecture.dependencies
        .sortedWith(compareBy({ it.from }, { it.to }))
        .map { dependency ->
            val source = componentIds.getValue(dependency.from)
            val target = componentIds.getValue(dependency.to)
            val key = "$source\u0000$target"
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            ArchitectureDependencySnapshot(
                id = stableSnapshotId("architecture-dependency", scope.id, key, ordinal.toString()),
                sourceComponentId = source,
                targetComponentId = target,
            )
        }.sortedBy(ArchitectureDependencySnapshot::id)
}

private fun SnapshotMappingContext.architectureEntryPoints(
    scope: SnapshotProjectScope,
    analysis: AnalysisSummary,
    componentIds: Map<String, String>,
): List<ArchitectureEntryPointSnapshot> {
    val counts = mutableMapOf<String, Int>()
    return analysis.architecture.entryPoints
        .sortedBy(::entryPointCanonicalKey)
        .map { entryPoint ->
            val key = entryPointCanonicalKey(entryPoint)
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            ArchitectureEntryPointSnapshot(
                id = stableSnapshotId("architecture-entry-point", scope.id, key, ordinal.toString()),
                componentId = componentIds.getValue(entryPoint.componentId),
                reason = entryPoint.reason,
                kind = ArchitectureEntryPointKind.valueOf(entryPoint.kind.name),
            )
        }.sortedBy(ArchitectureEntryPointSnapshot::id)
}

private fun SnapshotMappingContext.architectureCycles(
    scope: SnapshotProjectScope,
    analysis: AnalysisSummary,
    componentIds: Map<String, String>,
): List<ArchitectureCycleSnapshot> {
    val counts = mutableMapOf<String, Int>()
    return analysis.architecture.cycles
        .sortedBy { cycle -> cycle.componentIds.joinToString("\u0000") }
        .map { cycle ->
            val route = cycle.componentIds.map(componentIds::getValue)
            val key = route.joinToString("\u0000")
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            ArchitectureCycleSnapshot(
                id = stableSnapshotId("architecture-cycle", scope.id, key, ordinal.toString()),
                componentIds = route,
            )
        }.sortedBy(ArchitectureCycleSnapshot::id)
}

private fun namedCycles(
    scope: SnapshotProjectScope,
    analysis: AnalysisSummary,
): List<NamedCycleSnapshot> {
    val counts = mutableMapOf<String, Int>()
    return analysis.cycles
        .sortedBy { it.joinToString("\u0000") }
        .map { route ->
            val key = route.joinToString("\u0000")
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            NamedCycleSnapshot(
                id = stableSnapshotId("named-cycle", scope.id, key, ordinal.toString()),
                names = route,
            )
        }.sortedBy(NamedCycleSnapshot::id)
}

private fun SnapshotMappingContext.findingSnapshots(
    scope: SnapshotProjectScope,
    findings: List<Finding>,
    componentIds: Map<String, String>,
    symbolIdsByComponent: Map<String, String>,
): List<FindingSnapshot> {
    val counts = mutableMapOf<String, Int>()
    return findings
        .sortedBy(::findingCanonicalKey)
        .map { finding ->
            val key = findingCanonicalKey(finding)
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            val filePath = finding.filePath?.normalizedAnalysisPath()
            FindingSnapshot(
                id = stableSnapshotId("finding", scope.id, key, ordinal.toString()),
                severity = FindingSeverity.valueOf(finding.severity.name),
                message = finding.message,
                suggestion = finding.suggestion,
                fileId = filePath?.let { fileIdFor(scope, it, null) },
                filePath = filePath,
                line = finding.line,
                componentIds = finding.componentIds.distinct().sorted(),
                resolvedComponentIds =
                    finding.componentIds
                        .mapNotNull(componentIds::get)
                        .distinct()
                        .sorted(),
                symbolIds =
                    finding.componentIds
                        .mapNotNull(symbolIdsByComponent::get)
                        .distinct()
                        .sorted(),
                componentCycle = finding.componentCycle?.componentIds,
            )
        }.sortedBy(FindingSnapshot::id)
}

private fun SnapshotMappingContext.hubSnapshot(
    scope: SnapshotProjectScope?,
    hub: HubClass,
    ordinal: Int,
): HubSnapshot {
    val path = hub.filePath.takeIf(String::isNotBlank)?.normalizedAnalysisPath()
    val sourceFileId =
        if (scope == null) {
            path?.let { fileIdForAnyProject(it, hub.line.takeIf { line -> line > 0 }) }
        } else {
            path?.let { fileIdFor(scope, it, hub.isTest) }
        }
    val scopeId = scope?.id ?: "aggregate"
    return HubSnapshot(
        id = stableSnapshotId("hub", scopeId, hub.name, path.orEmpty(), hub.line.toString(), ordinal.toString()),
        name = hub.name,
        dependentCount = hub.dependentCount,
        role = hub.role,
        filePath = path,
        line = hub.line.takeIf { it > 0 },
        sourceFileId = sourceFileId,
        dependents =
            hub.dependents
                .map { dependent ->
                    HubDependentSnapshot(
                        name = dependent.name,
                        filePath = dependent.filePath.normalizedAnalysisPath(),
                        line = dependent.line.takeIf { it > 0 },
                    )
                }.distinct()
                .sortedWith(HUB_DEPENDENT_ORDER),
        isTest = hub.isTest,
    )
}

internal fun SnapshotMappingContext.aggregateHubSnapshots(): List<HubSnapshot> =
    hubSnapshots(null, report.aggregateAnalysis?.hubs.orEmpty())

internal fun SnapshotMappingContext.aggregateFindingSnapshots(): List<FindingSnapshot> {
    val counts = mutableMapOf<String, Int>()
    return report.aggregateAnalysis
        ?.findings
        .orEmpty()
        .sortedBy(::findingCanonicalKey)
        .map { finding ->
            val key = findingCanonicalKey(finding)
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            FindingSnapshot(
                id = stableSnapshotId("aggregate-finding", key, ordinal.toString()),
                severity = FindingSeverity.valueOf(finding.severity.name),
                message = finding.message,
                suggestion = finding.suggestion,
                filePath = finding.filePath?.normalizedAnalysisPath(),
                line = finding.line,
                componentIds = finding.componentIds.distinct().sorted(),
                componentCycle = finding.componentCycle?.componentIds,
            )
        }.sortedBy(FindingSnapshot::id)
}

internal fun SnapshotMappingContext.aggregateNamedCycleSnapshots(): List<NamedCycleSnapshot> {
    val counts = mutableMapOf<String, Int>()
    return report.aggregateAnalysis
        ?.cycles
        .orEmpty()
        .sortedBy { it.joinToString("\u0000") }
        .map { route ->
            val key = route.joinToString("\u0000")
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            NamedCycleSnapshot(
                id = stableSnapshotId("aggregate-named-cycle", key, ordinal.toString()),
                names = route,
            )
        }.sortedBy(NamedCycleSnapshot::id)
}

private fun SnapshotMappingContext.hubSnapshots(
    scope: SnapshotProjectScope?,
    hubs: List<HubClass>,
): List<HubSnapshot> {
    val counts = mutableMapOf<String, Int>()
    return hubs
        .sortedBy(::hubCanonicalKey)
        .map { hub ->
            val key = hubCanonicalKey(hub)
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            hubSnapshot(scope, hub, ordinal)
        }.sortedBy(HubSnapshot::id)
}

internal fun SnapshotMappingContext.entryPointSnapshots(): List<WorkspaceEntryPointSnapshot> {
    val counts = mutableMapOf<String, Int>()
    return report.entryPoints
        .sortedWith(compareBy({ it.packageName }, { it.name }, { it.kind.name }))
        .map { entryPoint ->
            val qualifiedName =
                entryPoint.packageName
                    .takeUnless { it == "_root_" }
                    ?.let { "$it.${entryPoint.name}" }
                    ?: entryPoint.name
            val candidates = report.workspaceIndex.symbols.filter { it.qualifiedName == qualifiedName }
            val symbol = candidates.singleOrNull()
            val projectId = symbol?.let { projectIds[ProjectScopeKey(it.build, it.project)] }
            val key = listOf(entryPoint.packageName, entryPoint.name, entryPoint.kind.name).joinToString("\u0000")
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            WorkspaceEntryPointSnapshot(
                id = stableSnapshotId("workspace-entry-point", key, ordinal.toString()),
                name = entryPoint.name,
                packageName = entryPoint.packageName,
                kind = WorkspaceEntryPointKind.valueOf(entryPoint.kind.name),
                projectId = projectId,
                symbolId = symbol?.identity?.let(symbolIds::getValue),
            )
        }.sortedBy(WorkspaceEntryPointSnapshot::id)
}

internal fun SnapshotMappingContext.interfaceSnapshots(): List<InterfaceSnapshot> {
    val counts = mutableMapOf<String, Int>()
    return report.interfaces
        .sortedBy(::interfaceCanonicalKey)
        .map { contract ->
            val key = interfaceCanonicalKey(contract)
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            interfaceSnapshot(contract, ordinal)
        }.sortedBy(InterfaceSnapshot::id)
}

private fun SnapshotMappingContext.interfaceSnapshot(
    contract: InterfaceSummary,
    ordinal: Int,
): InterfaceSnapshot {
    val buildId = contract.build?.let(buildIds::getValue)
    val projectId =
        contract.build?.let { build ->
            contract.project?.let { project -> projectIds.getValue(ProjectScopeKey(build, project)) }
        }
    return InterfaceSnapshot(
        id =
            stableSnapshotId(
                "interface",
                contract.build.orEmpty(),
                contract.project.orEmpty(),
                contract.sourceSet,
                contract.qualifiedName,
                ordinal.toString(),
            ),
        name = contract.name,
        packageName = contract.packageName,
        implementationCount = contract.implementationCount,
        hasMock = contract.hasMock,
        sourceSet = contract.sourceSet,
        qualifiedName = contract.qualifiedName,
        buildId = buildId,
        projectId = projectId,
        symbolId = contract.identity?.let(symbolIds::get),
    )
}

internal fun SnapshotMappingContext.importantSymbolSnapshots(): List<ImportantSymbolSnapshot> =
    report.importantSymbols
        .map(::importantSymbolSnapshot)
        .sortedBy(ImportantSymbolSnapshot::id)

private fun SnapshotMappingContext.importantSymbolSnapshot(important: ImportantSymbol): ImportantSymbolSnapshot =
    ImportantSymbolSnapshot(
        id = stableSnapshotId("important-symbol", important.symbol.identity.value),
        symbolId = symbolIds.getValue(important.symbol.identity),
        reasons =
            important.reasons
                .map { reason ->
                    ImportantReasonSnapshot(
                        reason = ImportantSymbolReason.valueOf(reason.name),
                        score = reason.score,
                        label = reason.label,
                        description = reason.description,
                    )
                }.sortedBy { it.reason.name },
        score = important.score,
        usage = importantSymbolUsageSnapshot(important),
    )

private fun SnapshotMappingContext.importantSymbolUsageSnapshot(
    important: ImportantSymbol,
): ImportantSymbolUsageSnapshot {
    val incoming = retainedRelationshipRecords(important.usage.incoming)
    val outgoing = retainedRelationshipRecords(important.usage.outgoing)
    val countedIncoming = incoming.filter { record -> record.snapshot.kind != RelationshipKind.IMPORT }
    val workspaceInboundCount = countedIncoming.size
    return ImportantSymbolUsageSnapshot(
        incomingRelationshipIds = incoming.map { record -> record.snapshot.id }.sorted(),
        outgoingRelationshipIds = outgoing.map { record -> record.snapshot.id }.sorted(),
        localInboundCount =
            countedIncoming.count { record ->
                record.source.source?.let { source ->
                    source.build == important.symbol.build && source.project == important.symbol.project
                } == true
            },
        workspaceInboundCount = workspaceInboundCount,
        crossBuildInboundCount =
            countedIncoming.count { record ->
                record.source.source?.let { source -> source.build != important.symbol.build } == true
            },
        isWorkspaceUsed = workspaceInboundCount > 0,
    )
}

private fun SnapshotMappingContext.retainedRelationshipRecords(
    relationships: List<WorkspaceRelationship>,
): List<RelationshipRecord> {
    val recordsByRelationship = relationshipRecords.groupBy(RelationshipRecord::source)
    val counts = mutableMapOf<WorkspaceRelationship, Int>()
    return relationships
        .sortedBy(::relationshipUsageKey)
        .mapNotNull { relationship ->
            val ordinal = counts.getOrDefault(relationship, 0)
            counts[relationship] = ordinal + 1
            recordsByRelationship[relationship]?.getOrNull(ordinal)
        }
}

private fun relationshipUsageKey(relationship: WorkspaceRelationship): String =
    listOf(
        relationship.sourceIdentity?.value.orEmpty(),
        relationship.targetIdentity.value,
        relationship.kind.name,
        relationship.evidence.name,
        relationship.sourceEvidence.projectRelativeFile,
        relationship.sourceEvidence.line.toString(),
        relationship.sourceEvidence.context,
    ).joinToString("\u0000")

private fun SnapshotMappingContext.fileIdFor(
    scope: SnapshotProjectScope,
    rawPath: String,
    isTest: Boolean?,
): String? {
    val path = rawPath.normalizedAnalysisPath()
    val candidates =
        fileIds
            .filterKeys { key ->
                key.build == scope.buildName &&
                    key.project == scope.projectPath &&
                    (key.path == path || key.path.endsWith("/$path")) &&
                    (isTest == null || key.sourceSet.isTestSourceSet() == isTest)
            }.values
            .distinct()
    return candidates.singleOrNull()
}

private fun SnapshotMappingContext.fileIdForAnyProject(
    rawPath: String,
    line: Int?,
): String? {
    val path = rawPath.normalizedAnalysisPath()
    val candidates =
        fileIds
            .filterKeys { key -> key.path == path || key.path.endsWith("/$path") }
            .values
            .distinct()
            .filter { fileId ->
                line == null ||
                    filesById
                        .getValue(fileId)
                        .content
                        ?.sourceLineCount()
                        ?.let { line <= it } != false
            }
    return candidates.singleOrNull()
}

private fun String.sourceLineCount(): Int =
    if (isEmpty()) {
        0
    } else {
        lineSequence().count() - if (endsWith('\n') || endsWith('\r')) 1 else 0
    }

private fun SnapshotMappingContext.symbolForComponent(
    scope: SnapshotProjectScope,
    component: ArchitectureComponent,
    fileId: String?,
): WorkspaceSymbol? {
    val candidates =
        report.workspaceIndex.symbols.filter { symbol ->
            symbol.build == scope.buildName &&
                symbol.project == scope.projectPath &&
                symbol.qualifiedName == component.id
        }
    val exactFile =
        fileId
            ?.let { expectedFile ->
                candidates.filter { symbol ->
                    symbolIds[symbol.identity]?.let(symbolsById::get)?.fileId == expectedFile
                }
            }.orEmpty()
    return exactFile.singleOrNull() ?: candidates.singleOrNull()
}

private fun componentCanonicalKey(component: ArchitectureComponent): String =
    listOf(
        component.id,
        component.name,
        component.packageName,
        component.packageGroup,
        component.role,
        component.layer.name,
        component.filePath.normalizedAnalysisPath(),
        component.line.toString(),
        component.isTest.toString(),
    ).joinToString("\u0000")

private fun entryPointCanonicalKey(entryPoint: ArchitectureEntryPoint): String =
    listOf(entryPoint.componentId, entryPoint.reason, entryPoint.kind.name).joinToString("\u0000")

private fun findingCanonicalKey(finding: Finding): String =
    listOf(
        finding.severity.name,
        finding.message,
        finding.suggestion,
        finding.filePath.orEmpty().normalizedAnalysisPath(),
        finding.line?.toString().orEmpty(),
        finding.componentIds.sorted().joinToString("\u0001"),
        finding.componentCycle
            ?.componentIds
            ?.joinToString("\u0001")
            .orEmpty(),
    ).joinToString("\u0000")

private fun hubCanonicalKey(hub: HubClass): String =
    listOf(
        hub.name,
        hub.dependentCount.toString(),
        hub.role,
        hub.filePath.normalizedAnalysisPath(),
        hub.line.toString(),
        hub.isTest.toString(),
        hub.dependents
            .sortedWith(compareBy({ it.name }, { it.filePath }, { it.line }))
            .joinToString("\u0001") { "${it.name}\u0002${it.filePath.normalizedAnalysisPath()}\u0002${it.line}" },
    ).joinToString("\u0000")

private fun interfaceCanonicalKey(contract: InterfaceSummary): String =
    listOf(
        contract.build.orEmpty(),
        contract.project.orEmpty(),
        contract.sourceSet,
        contract.qualifiedName,
        contract.name,
        contract.packageName.orEmpty(),
        contract.implementationCount.toString(),
        contract.hasMock.toString(),
        contract.identity?.value.orEmpty(),
    ).joinToString("\u0000")

private fun String.normalizedAnalysisPath(): String = replace('\\', '/').removePrefix("./")

private fun String.isTestSourceSet(): Boolean =
    equals("test", ignoreCase = true) || startsWith("test", ignoreCase = true) || endsWith("Test")

private data class ComponentRecord(
    val source: ArchitectureComponent,
    val snapshot: ArchitectureComponentSnapshot,
)

private val HUB_DEPENDENT_ORDER: Comparator<HubDependentSnapshot> =
    compareBy(HubDependentSnapshot::name, HubDependentSnapshot::filePath, HubDependentSnapshot::line)
