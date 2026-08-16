package zone.clanker.docx.index.importing

import zone.clanker.docx.index.database.bind
import zone.clanker.docx.index.database.executeBatches
import zone.clanker.docx.index.generation.IndexGenerationKey
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot
import java.sql.Connection

internal fun insertProjectShard(
    connection: Connection,
    key: IndexGenerationKey,
    ownership: WorkspaceOwnership,
    shard: ProjectGraphShard,
) {
    ownership.project(shard.projectId)
    connection.insertSourceSets(key, shard)
    connection.insertFiles(key, ownership, shard)
    connection.insertSymbols(key, ownership, shard)
    connection.insertSymbolEvidence(key, shard)
    connection.insertRelationships(key, ownership, shard)
    connection.insertRelationshipOccurrences(key, shard)
    connection.insertRelationshipOccurrenceRanges(key, shard)
    connection.insertFindings(key, ownership, shard)
    connection.insertGraphFindings(key, shard)
    connection.insertGraphCycles(key, shard)
}

private fun Connection.insertSourceSets(
    key: IndexGenerationKey,
    shard: ProjectGraphShard,
) {
    val ownedIds = shard.ownedSourceSetIds.toSet()
    val sourceSets = shard.sourceSets.filter { sourceSet -> sourceSet.id in ownedIds }
    prepareStatement(INSERT_SOURCE_SET_SQL).use { statement ->
        statement.executeBatches(sourceSets) { sourceSet ->
            bind(key.values + listOf(sourceSet.id, sourceSet.projectId, sourceSet.name))
        }
    }
}

private fun Connection.insertFiles(
    key: IndexGenerationKey,
    ownership: WorkspaceOwnership,
    shard: ProjectGraphShard,
) {
    val ownedIds = shard.ownedFileIds.toSet()
    val sourceSetsById = shard.sourceSets.associateBy(SourceSetSnapshot::id)
    val files = shard.files.filter { file -> file.id in ownedIds }
    prepareStatement(INSERT_FILE_SQL).use { statement ->
        statement.executeBatches(files) { file ->
            val sourceSet = sourceSetsById.getValue(file.sourceSetId)
            bind(
                key.values +
                    listOf(
                        file.id,
                        ownership.buildId(sourceSet.projectId),
                        sourceSet.projectId,
                        sourceSet.id,
                        file.projectRelativePath,
                        file.language.name,
                    ),
            )
        }
    }
}

private fun Connection.insertSymbols(
    key: IndexGenerationKey,
    ownership: WorkspaceOwnership,
    shard: ProjectGraphShard,
) {
    val ownedIds = shard.ownedSymbolIds.toSet()
    val sourceSetsById = shard.sourceSets.associateBy(SourceSetSnapshot::id)
    val filesById = shard.files.associateBy(SourceFileSnapshot::id)
    val symbols = shard.symbols.filter { symbol -> symbol.id in ownedIds }
    prepareStatement(INSERT_SYMBOL_SQL).use { statement ->
        statement.executeBatches(symbols) { symbol ->
            val file = filesById.getValue(symbol.fileId)
            val sourceSet = sourceSetsById.getValue(file.sourceSetId)
            statement.bindSymbol(key, ownership, sourceSet, file, symbol)
        }
    }
    prepareStatement(INSERT_SYMBOL_FTS_SQL).use { statement ->
        statement.executeBatches(symbols) { symbol ->
            val file = filesById.getValue(symbol.fileId)
            bind(
                key.values +
                    listOf(
                        symbol.id,
                        symbol.name,
                        symbol.qualifiedName,
                        symbol.packageName,
                        file.projectRelativePath,
                    ),
            )
        }
    }
}

private fun java.sql.PreparedStatement.bindSymbol(
    key: IndexGenerationKey,
    ownership: WorkspaceOwnership,
    sourceSet: SourceSetSnapshot,
    file: SourceFileSnapshot,
    symbol: SymbolSnapshot,
) {
    bind(
        key.values +
            listOf(
                symbol.id,
                ownership.buildId(sourceSet.projectId),
                sourceSet.projectId,
                sourceSet.id,
                sourceSet.name,
                file.id,
                file.projectRelativePath,
                symbol.name,
                symbol.qualifiedName,
                symbol.packageName,
                symbol.kind.name,
                symbol.declarationSemantic.name,
                symbol.declarationLine,
            ),
    )
}

private fun Connection.insertRelationships(
    key: IndexGenerationKey,
    ownership: WorkspaceOwnership,
    shard: ProjectGraphShard,
) {
    val referencesById = shard.references.associateBy(ReferenceSnapshot::id)
    val filesById = shard.files.associateBy(SourceFileSnapshot::id)
    val sourceSetsById = shard.sourceSets.associateBy(SourceSetSnapshot::id)
    prepareStatement(INSERT_RELATIONSHIP_SQL).use { statement ->
        statement.executeBatches(shard.relationships) { relationship ->
            val reference = referencesById.getValue(relationship.referenceId)
            val sourceFile = filesById.getValue(reference.sourceFileId)
            val sourceSet = sourceSetsById.getValue(sourceFile.sourceSetId)
            bind(
                key.values +
                    listOf(
                        relationship.id,
                        ownership.buildId(sourceSet.projectId),
                        sourceSet.projectId,
                        sourceSet.id,
                        sourceFile.id,
                        reference.id,
                        relationship.sourceSymbolId,
                        relationship.targetSymbolId,
                        relationship.kind.name,
                        relationship.resolutionEvidence.name,
                    ),
            )
        }
    }
}

private fun Connection.insertSymbolEvidence(
    key: IndexGenerationKey,
    shard: ProjectGraphShard,
) {
    val ownedIds = shard.ownedSymbolIds.toSet()
    val symbols = shard.symbols.filter { symbol -> symbol.id in ownedIds }
    prepareStatement(INSERT_SYMBOL_EVIDENCE_SQL).use { statement ->
        statement.executeBatches(symbols) { symbol ->
            bind(
                key.values +
                    listOf(
                        symbol.id,
                        symbol.ownerSymbolId,
                        symbol.signature,
                        symbol.declarationRange?.startOffset,
                        symbol.declarationRange?.endOffsetExclusive,
                    ),
            )
        }
    }
}

private fun Connection.insertRelationshipOccurrences(
    key: IndexGenerationKey,
    shard: ProjectGraphShard,
) {
    val referencesById = shard.references.associateBy(ReferenceSnapshot::id)
    val filesById = shard.files.associateBy(SourceFileSnapshot::id)
    prepareStatement(INSERT_RELATIONSHIP_OCCURRENCE_SQL).use { statement ->
        statement.executeBatches(shard.relationships) { relationship ->
            val reference = referencesById.getValue(relationship.referenceId)
            val sourceFile = filesById.getValue(reference.sourceFileId)
            bind(
                key.values +
                    listOf(
                        relationship.id,
                        reference.id,
                        sourceFile.id,
                        sourceFile.projectRelativePath,
                        reference.line,
                        reference.context,
                    ),
            )
        }
    }
}

private fun Connection.insertRelationshipOccurrenceRanges(
    key: IndexGenerationKey,
    shard: ProjectGraphShard,
) {
    val referencesById = shard.references.associateBy(ReferenceSnapshot::id)
    val rangedRelationships =
        shard.relationships.mapNotNull { relationship ->
            referencesById.getValue(relationship.referenceId).occurrenceRange?.let { range ->
                RelationshipOccurrenceRange(
                    relationshipId = relationship.id,
                    startOffset = range.startOffset,
                    endOffsetExclusive = range.endOffsetExclusive,
                )
            }
        }
    prepareStatement(INSERT_RELATIONSHIP_OCCURRENCE_RANGE_SQL).use { statement ->
        statement.executeBatches(rangedRelationships) { occurrence ->
            bind(
                key.values +
                    listOf(
                        occurrence.relationshipId,
                        occurrence.startOffset,
                        occurrence.endOffsetExclusive,
                    ),
            )
        }
    }
}

private fun Connection.insertFindings(
    key: IndexGenerationKey,
    ownership: WorkspaceOwnership,
    shard: ProjectGraphShard,
) {
    val filesById = shard.files.associateBy(SourceFileSnapshot::id)
    val findings =
        (shard.findings + shard.analysis?.findings.orEmpty())
            .distinctBy(FindingSnapshot::id)
            .sortedBy(FindingSnapshot::id)
    prepareStatement(INSERT_FINDING_SQL).use { statement ->
        statement.executeBatches(findings) { finding ->
            val sourceSetId = finding.fileId?.let(filesById::get)?.sourceSetId
            bind(
                key.values +
                    listOf(
                        finding.id,
                        ownership.buildId(shard.projectId),
                        shard.projectId,
                        sourceSetId,
                        finding.fileId,
                        finding.filePath,
                        finding.line,
                        finding.severity.name,
                        finding.message,
                        finding.suggestion,
                    ),
            )
        }
    }
}

private fun Connection.insertGraphFindings(
    key: IndexGenerationKey,
    shard: ProjectGraphShard,
) {
    prepareStatement(INSERT_GRAPH_FINDING_SQL).use { statement ->
        statement.executeBatches(shard.findings) { finding ->
            bind(
                key.values +
                    listOf(
                        shard.projectId,
                        finding.id,
                        finding.fileId,
                        finding.severity.name,
                        finding.message,
                    ),
            )
        }
    }
    val symbolEvidence =
        shard.findings.flatMap { finding ->
            finding.symbolIds.map { symbolId -> GraphFindingSymbol(finding.id, symbolId) }
        }
    prepareStatement(INSERT_GRAPH_FINDING_SYMBOL_SQL).use { statement ->
        statement.executeBatches(symbolEvidence) { evidence ->
            bind(key.values + listOf(shard.projectId, evidence.findingId, evidence.symbolId))
        }
    }
}

private fun Connection.insertGraphCycles(
    key: IndexGenerationKey,
    shard: ProjectGraphShard,
) {
    prepareStatement(INSERT_GRAPH_CYCLE_SQL).use { statement ->
        statement.executeBatches(shard.cycles) { cycle ->
            bind(key.values + listOf(cycle.id, shard.projectId))
        }
    }
    val participants =
        shard.cycles.flatMap { cycle ->
            cycle.symbolIds.mapIndexed { position, symbolId ->
                GraphCycleSymbol(cycle.id, position, symbolId)
            }
        }
    prepareStatement(INSERT_GRAPH_CYCLE_SYMBOL_SQL).use { statement ->
        statement.executeBatches(participants) { participant ->
            bind(key.values + listOf(participant.cycleId, participant.position, participant.symbolId))
        }
    }
}

private data class GraphFindingSymbol(
    val findingId: String,
    val symbolId: String,
)

private data class RelationshipOccurrenceRange(
    val relationshipId: String,
    val startOffset: Int,
    val endOffsetExclusive: Int,
)

private data class GraphCycleSymbol(
    val cycleId: String,
    val position: Int,
    val symbolId: String,
)

private val IndexGenerationKey.values: List<String>
    get() = listOf(workspaceId, generationId)

private const val INSERT_SOURCE_SET_SQL =
    """
    INSERT INTO source_sets (workspace_id, generation_id, source_set_id, project_id, name)
    VALUES (?, ?, ?, ?, ?)
    """

private const val INSERT_FILE_SQL =
    """
    INSERT INTO source_files (
        workspace_id, generation_id, file_id, build_id, project_id,
        source_set_id, project_relative_path, language
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """

private const val INSERT_SYMBOL_SQL =
    """
    INSERT INTO symbols (
        workspace_id, generation_id, symbol_id, build_id, project_id, source_set_id,
        source_set_name, file_id, file_path, name, qualified_name, package_name, kind,
        declaration_semantic, declaration_line
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

private const val INSERT_SYMBOL_FTS_SQL =
    """
    INSERT INTO symbol_fts (
        workspace_id, generation_id, symbol_id, name, qualified_name, package_name, file_path
    ) VALUES (?, ?, ?, ?, ?, ?, ?)
    """

private const val INSERT_SYMBOL_EVIDENCE_SQL =
    """
    INSERT INTO symbol_evidence (
        workspace_id, generation_id, symbol_id, owner_symbol_id, signature,
        declaration_start_offset, declaration_end_offset_exclusive
    ) VALUES (?, ?, ?, ?, ?, ?, ?)
    """

private const val INSERT_RELATIONSHIP_SQL =
    """
    INSERT OR IGNORE INTO relationships (
        workspace_id, generation_id, relationship_id, build_id, project_id, source_set_id,
        source_file_id, reference_id, source_symbol_id, target_symbol_id, kind, evidence
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

private const val INSERT_RELATIONSHIP_OCCURRENCE_SQL =
    """
    INSERT OR IGNORE INTO relationship_occurrences (
        workspace_id, generation_id, relationship_id, reference_id, source_file_id,
        source_file_path, source_line, source_context
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """

private const val INSERT_RELATIONSHIP_OCCURRENCE_RANGE_SQL =
    """
    INSERT OR IGNORE INTO relationship_occurrence_ranges (
        workspace_id, generation_id, relationship_id, start_offset, end_offset_exclusive
    ) VALUES (?, ?, ?, ?, ?)
    """

private const val INSERT_FINDING_SQL =
    """
    INSERT INTO findings (
        workspace_id, generation_id, finding_id, build_id, project_id, source_set_id,
        file_id, file_path, line, severity, message, suggestion
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

private const val INSERT_GRAPH_FINDING_SQL =
    """
    INSERT INTO graph_finding_evidence (
        workspace_id, generation_id, project_id, finding_id, file_id, severity, message
    ) VALUES (?, ?, ?, ?, ?, ?, ?)
    """

private const val INSERT_GRAPH_FINDING_SYMBOL_SQL =
    """
    INSERT INTO graph_finding_symbols (
        workspace_id, generation_id, project_id, finding_id, symbol_id
    ) VALUES (?, ?, ?, ?, ?)
    """

private const val INSERT_GRAPH_CYCLE_SQL =
    """
    INSERT OR IGNORE INTO graph_cycles (
        workspace_id, generation_id, cycle_id, project_id
    ) VALUES (?, ?, ?, ?)
    """

private const val INSERT_GRAPH_CYCLE_SYMBOL_SQL =
    """
    INSERT OR IGNORE INTO graph_cycle_symbols (
        workspace_id, generation_id, cycle_id, position, symbol_id
    ) VALUES (?, ?, ?, ?, ?)
    """
