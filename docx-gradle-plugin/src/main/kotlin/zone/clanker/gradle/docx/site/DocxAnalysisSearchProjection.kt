package zone.clanker.gradle.docx.site

import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceSearchBadge
import zone.clanker.report.model.WorkspaceSearchBadgeKind
import zone.clanker.report.model.WorkspaceSearchEntry
import zone.clanker.report.model.WorkspaceSearchKind
import zone.clanker.report.model.WorkspaceSearchLocation
import zone.clanker.report.model.WorkspaceSearchTarget
import zone.clanker.report.model.workspaceSearchTerms

internal fun StaticSearchProjectionContext.findingEntries(): List<WorkspaceSearchEntry> =
    graphs
        .flatMap { graph ->
            val project = projectsById.getValue(graph.projectId)
            val filesById = graph.files.associateBy(SourceFileSnapshot::id)
            val symbolsById = graph.symbols.associateBy(SymbolSnapshot::id)
            graph.findings.map { finding ->
                val file = finding.fileId?.let(filesById::get)
                val sourceSetId = file?.sourceSetId
                val symbolNames = finding.symbolIds.mapNotNull(symbolsById::get).map(SymbolSnapshot::qualifiedName)
                WorkspaceSearchEntry(
                    id = finding.id,
                    kind = WorkspaceSearchKind.FINDING,
                    label = finding.message,
                    detail = "${finding.severity.label} / ${scopeDetail(graph.projectId)} / ${finding.suggestion}",
                    terms =
                        workspaceSearchTerms(
                            finding.message,
                            finding.suggestion,
                            finding.severity.label,
                            finding.filePath.orEmpty(),
                            symbolNames.joinToString(" "),
                        ),
                    target =
                        WorkspaceSearchTarget(
                            WorkspaceSearchLocation(
                                buildId = project.buildId,
                                projectId = project.id,
                                sourceSetId = sourceSetId,
                                fileId = file?.id,
                                line = finding.line,
                            ),
                        ),
                    badges = listOf(WorkspaceSearchBadge(WorkspaceSearchBadgeKind.PROBLEMS, 1)),
                )
            }
        }.distinctBy(WorkspaceSearchEntry::key)

internal fun StaticSearchProjectionContext.cycleEntries(): List<WorkspaceSearchEntry> =
    graphs
        .flatMap { graph -> graph.cycles.map { cycle -> graph.projectId to cycle } }
        .distinctBy { (_, cycle) -> cycle.id }
        .map { (projectId, cycle) -> cycleEntry(projectId, cycle) }

private fun StaticSearchProjectionContext.cycleEntry(
    projectId: String,
    cycle: CycleSnapshot,
): WorkspaceSearchEntry =
    run {
        val graph = graphs.single { candidate -> candidate.projectId == projectId }
        val symbolsById = graph.symbols.associateBy(SymbolSnapshot::id)
        val names = cycle.symbolIds.map { symbolId -> symbolsById.getValue(symbolId).qualifiedName }
        val project = projectsById.getValue(projectId)
        WorkspaceSearchEntry(
            id = cycle.id,
            kind = WorkspaceSearchKind.CYCLE,
            label = names.joinToString(" → "),
            detail = "${scopeDetail(projectId)} / ${cycle.relationshipIds.size} relationship records",
            terms = workspaceSearchTerms("cycle", names.joinToString(" "), project.path),
            target =
                WorkspaceSearchTarget(
                    WorkspaceSearchLocation(buildId = project.buildId, projectId = project.id),
                ),
            badges = listOf(WorkspaceSearchBadge(WorkspaceSearchBadgeKind.CYCLES, 1)),
        )
    }
