package zone.clanker.gradle.docx.site

import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceDeclarationEvidence
import zone.clanker.report.model.WorkspaceDeclarationEvidencePage
import zone.clanker.report.model.WorkspaceEvidenceLocation
import zone.clanker.report.model.WorkspaceEvidenceTarget
import zone.clanker.report.model.WorkspaceRelationshipOccurrence
import zone.clanker.report.model.WorkspaceRelationshipOccurrencePage
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceSelector
import zone.clanker.report.model.WorkspaceReverseUsagePage
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.WorkspaceStaticEvidenceEntry
import zone.clanker.report.model.usageCategory
import zone.clanker.report.model.workspaceDeclarationEvidenceRouteKey
import zone.clanker.report.model.workspaceEvidenceOccurrenceComparator
import zone.clanker.report.model.workspaceRelationshipOccurrenceRouteKey
import zone.clanker.report.model.workspaceReverseUsageRouteKey
import zone.clanker.report.model.workspaceStaticEvidenceEntryComparator

internal data class DocxStaticEvidenceProjection(
    val entries: List<WorkspaceStaticEvidenceEntry>,
) {
    init {
        require(entries == entries.sortedWith(workspaceStaticEvidenceEntryComparator())) {
            "Static evidence entries must be deterministic"
        }
        require(entries.map { entry -> entry.routeKey to entry.pageIndex }.distinct().size == entries.size) {
            "Static evidence route pages must be unique"
        }
    }

    companion object {
        fun apply(
            site: ProjectedDocxSite,
            generationId: String,
        ): DocxStaticEvidenceProjection {
            val catalog = StaticEvidenceCatalog(site, generationId)
            return DocxStaticEvidenceProjection(
                (catalog.declarations() + catalog.reverseUsages() + catalog.relationshipOccurrences())
                    .sortedWith(workspaceStaticEvidenceEntryComparator()),
            )
        }
    }
}

private class StaticEvidenceCatalog(
    site: ProjectedDocxSite,
    generationId: String,
) {
    private val target = WorkspaceEvidenceTarget(site.workspace.id, generationId)
    private val buildsById = site.builds.associateBy { build -> build.id }
    private val projectsById = site.projects.associateBy(ProjectSnapshot::id)
    private val sourceSetsById = site.sourceSets.associateBy(WorkspaceSourceSetSummary::id)
    private val filesById = site.projectGraphs.uniqueFacts(ProjectGraphShard::files, SourceFileSnapshot::id)
    private val symbolsById = site.projectGraphs.uniqueFacts(ProjectGraphShard::symbols, SymbolSnapshot::id)
    private val referencesById = site.projectGraphs.uniqueFacts(ProjectGraphShard::references, ReferenceSnapshot::id)
    private val relationships =
        site.projectGraphs
            .uniqueFacts(ProjectGraphShard::relationships, RelationshipSnapshot::id)
            .values
            .sortedBy(RelationshipSnapshot::id)
    private val occurrences = relationships.map(::occurrence)
    private val paths = StaticEvidencePaths(site, filesById, symbolsById)

    fun declarations(): List<WorkspaceStaticEvidenceEntry> =
        symbolsById.values.sortedBy(SymbolSnapshot::id).map { symbol ->
            val page =
                WorkspaceDeclarationEvidencePage(
                    target = target,
                    symbolId = symbol.id,
                    declaration =
                        WorkspaceDeclarationEvidence(
                            symbolId = symbol.id,
                            ownerSymbolId = symbol.ownerSymbolId,
                            signature = symbol.signature,
                            name = symbol.name,
                            qualifiedName = symbol.qualifiedName,
                            kind = symbol.kind,
                            semantic = symbol.declarationSemantic,
                            location =
                                location(
                                    filesById.getValue(symbol.fileId),
                                    symbol.declarationLine,
                                    symbol.declarationRange,
                                ),
                        ),
                )
            WorkspaceStaticEvidenceEntry(
                routeKey = workspaceDeclarationEvidenceRouteKey(symbol.id),
                pageIndex = 0,
                declaration = page,
            )
        }

    fun reverseUsages(): List<WorkspaceStaticEvidenceEntry> =
        occurrences
            .groupBy(WorkspaceRelationshipOccurrence::targetSymbolId)
            .toSortedMap()
            .flatMap { (symbolId, records) ->
                records.sortedWith(workspaceEvidenceOccurrenceComparator()).reverseUsagePages(symbolId)
            }

    fun relationshipOccurrences(): List<WorkspaceStaticEvidenceEntry> {
        val selectors =
            mutableMapOf<WorkspaceRelationshipOccurrenceSelector, MutableList<WorkspaceRelationshipOccurrence>>()
        relationships.zip(occurrences).forEach { (relationship, occurrence) ->
            paths.selectors(relationship, referencesById.getValue(relationship.referenceId)).forEach { selector ->
                selectors.getOrPut(selector) { mutableListOf() }.add(occurrence)
            }
        }
        return selectors
            .toSortedMap(compareBy({ selector -> selector.kind.name }, { it.sourceNodeId }, { it.targetNodeId }))
            .flatMap { (selector, records) ->
                records.sortedWith(workspaceEvidenceOccurrenceComparator()).occurrencePages(selector)
            }
    }

    private fun occurrence(relationship: RelationshipSnapshot): WorkspaceRelationshipOccurrence {
        val reference = referencesById.getValue(relationship.referenceId)
        return WorkspaceRelationshipOccurrence(
            relationshipId = relationship.id,
            referenceId = reference.id,
            sourceSymbolId = relationship.sourceSymbolId,
            targetSymbolId = relationship.targetSymbolId,
            kind = relationship.kind,
            evidence = relationship.resolutionEvidence,
            category = relationship.kind.usageCategory(),
            location = location(filesById.getValue(reference.sourceFileId), reference.line, reference.occurrenceRange),
            context = reference.context,
        )
    }

    private fun location(
        file: SourceFileSnapshot,
        line: Int,
        range: zone.clanker.report.model.SourceRangeSnapshot?,
    ): WorkspaceEvidenceLocation {
        val sourceSet = sourceSetsById.getValue(file.sourceSetId)
        val project = projectsById.getValue(sourceSet.projectId)
        require(project.buildId in buildsById) { "Static evidence project belongs to an unknown build" }
        return WorkspaceEvidenceLocation(
            buildId = project.buildId,
            projectId = project.id,
            sourceSetId = sourceSet.id,
            sourceSetName = sourceSet.name,
            fileId = file.id,
            filePath = file.projectRelativePath,
            line = line,
            range = range,
        )
    }

    private fun List<WorkspaceRelationshipOccurrence>.reverseUsagePages(
        symbolId: String,
    ): List<WorkspaceStaticEvidenceEntry> =
        chunked(STATIC_EVIDENCE_PAGE_SIZE).mapIndexed { pageIndex, pageRecords ->
            val hasMore = (pageIndex + 1) * STATIC_EVIDENCE_PAGE_SIZE < size
            WorkspaceStaticEvidenceEntry(
                routeKey = workspaceReverseUsageRouteKey(symbolId),
                pageIndex = pageIndex,
                reverseUsages =
                    WorkspaceReverseUsagePage(
                        target = target,
                        symbolId = symbolId,
                        totalCount = size.toLong(),
                        occurrences = pageRecords,
                        previousCursor = pageRecords.first().cursor.takeIf { pageIndex > 0 },
                        nextCursor = pageRecords.last().cursor.takeIf { hasMore },
                    ),
            )
        }

    private fun List<WorkspaceRelationshipOccurrence>.occurrencePages(
        selector: WorkspaceRelationshipOccurrenceSelector,
    ): List<WorkspaceStaticEvidenceEntry> =
        chunked(STATIC_EVIDENCE_PAGE_SIZE).mapIndexed { pageIndex, pageRecords ->
            val hasMore = (pageIndex + 1) * STATIC_EVIDENCE_PAGE_SIZE < size
            WorkspaceStaticEvidenceEntry(
                routeKey = workspaceRelationshipOccurrenceRouteKey(selector),
                pageIndex = pageIndex,
                relationshipOccurrences =
                    WorkspaceRelationshipOccurrencePage(
                        target = target,
                        selector = selector,
                        totalCount = size.toLong(),
                        occurrences = pageRecords,
                        previousCursor = pageRecords.first().cursor.takeIf { pageIndex > 0 },
                        nextCursor = pageRecords.last().cursor.takeIf { hasMore },
                    ),
            )
        }
}

private class StaticEvidencePaths(
    site: ProjectedDocxSite,
    private val filesById: Map<String, SourceFileSnapshot>,
    private val symbolsById: Map<String, SymbolSnapshot>,
) {
    private val workspaceId = site.workspace.id
    private val projectsById = site.projects.associateBy(ProjectSnapshot::id)
    private val sourceSetsById = site.sourceSets.associateBy(WorkspaceSourceSetSummary::id)
    private val packageNamesByFileId =
        symbolsById.values
            .groupBy(SymbolSnapshot::fileId)
            .mapValues { (_, symbols) -> symbols.map(SymbolSnapshot::packageName).distinct() }
    private val packageIdsByFileId: Map<String, String> =
        filesById.values.associate { file ->
            val packages = packageNamesByFileId[file.id].orEmpty()
            val packageId =
                packages
                    .singleOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?.let { name -> sourcePackageId(file.sourceSetId, name) }
                    ?: unknownSourcePackageId(file.sourceSetId)
            file.id to packageId
        }

    fun selectors(
        relationship: RelationshipSnapshot,
        reference: ReferenceSnapshot,
    ): List<WorkspaceRelationshipOccurrenceSelector> {
        val sourcePath = relationship.sourceSymbolId?.let(::symbolPath) ?: filePath(reference.sourceFileId)
        val targetPath = symbolPath(relationship.targetSymbolId)
        return (BUILD_LEVEL..SYMBOL_LEVEL)
            .mapNotNull { level ->
                val sourceId = sourcePath.last { node -> node.level <= level }.id
                val targetId = targetPath.last { node -> node.level <= level }.id
                if (sourceId == targetId) {
                    null
                } else {
                    WorkspaceRelationshipOccurrenceSelector(relationship.kind, sourceId, targetId)
                }
            }.distinct()
    }

    private fun symbolPath(symbolId: String): List<StaticEvidencePathNode> {
        val symbol = symbolsById.getValue(symbolId)
        return filePath(symbol.fileId) + StaticEvidencePathNode(SYMBOL_LEVEL, symbol.id)
    }

    private fun filePath(fileId: String): List<StaticEvidencePathNode> {
        val file = filesById.getValue(fileId)
        val sourceSet = sourceSetsById.getValue(file.sourceSetId)
        val project = projectsById.getValue(sourceSet.projectId)
        return listOf(
            StaticEvidencePathNode(WORKSPACE_LEVEL, workspaceId),
            StaticEvidencePathNode(BUILD_LEVEL, project.buildId),
            StaticEvidencePathNode(PROJECT_LEVEL, project.id),
            StaticEvidencePathNode(SOURCE_SET_LEVEL, sourceSet.id),
            StaticEvidencePathNode(PACKAGE_LEVEL, packageIdsByFileId.getValue(file.id)),
            StaticEvidencePathNode(FILE_LEVEL, file.id),
        )
    }
}

private data class StaticEvidencePathNode(
    val level: Int,
    val id: String,
)

private fun <T, K> List<ProjectGraphShard>.uniqueFacts(
    values: (ProjectGraphShard) -> List<T>,
    key: (T) -> K,
): Map<K, T> {
    val facts = linkedMapOf<K, T>()
    flatMap(values).forEach { value ->
        val previous = facts.putIfAbsent(key(value), value)
        require(previous == null || previous == value) { "Static evidence contains conflicting duplicate facts" }
    }
    return facts
}

private fun sourcePackageId(
    sourceSetId: String,
    packageName: String,
): String = "source-package:${sourceSetId.length}:$sourceSetId:${packageName.length}:$packageName"

private fun unknownSourcePackageId(sourceSetId: String): String =
    "source-unknown-package:${sourceSetId.length}:$sourceSetId"

private const val STATIC_EVIDENCE_PAGE_SIZE: Int = 50
private const val WORKSPACE_LEVEL: Int = 0
private const val BUILD_LEVEL: Int = 1
private const val PROJECT_LEVEL: Int = 2
private const val SOURCE_SET_LEVEL: Int = 3
private const val PACKAGE_LEVEL: Int = 4
private const val FILE_LEVEL: Int = 5
private const val SYMBOL_LEVEL: Int = 6
