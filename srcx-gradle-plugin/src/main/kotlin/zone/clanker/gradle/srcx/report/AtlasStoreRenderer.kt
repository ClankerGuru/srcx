package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.srcx.atlas.AtlasCborRenderer
import zone.clanker.srcx.atlas.AtlasFileSymbolPayload
import zone.clanker.srcx.atlas.AtlasImportRecord
import zone.clanker.srcx.atlas.AtlasNodePayload
import zone.clanker.srcx.atlas.AtlasNodeRecord
import zone.clanker.srcx.atlas.AtlasOccurrencePayload
import zone.clanker.srcx.atlas.AtlasRelationshipPayload
import zone.clanker.srcx.atlas.AtlasRelationshipRecord
import zone.clanker.srcx.atlas.AtlasStoreContents
import zone.clanker.srcx.atlas.AtlasStoreMeta
import zone.clanker.srcx.atlas.AtlasStoreSchema
import java.time.Instant

/** Maps the 42-seed architecture graph into schema-2 Atlas store rows. */
class AtlasStoreRenderer {
    fun contents(
        report: WorkspaceReport,
        generatedAt: String = Instant.now().toString(),
    ): AtlasStoreContents {
        val graph = buildWorkspaceArchitectureGraph(report)
        val sourceById =
            report.sourceFiles.associateBy { file ->
                AtlasStoreSchema.fileId(file.build, file.project, file.sourceSet, file.projectRelativeFile)
            }
        val fileNodes =
            graph.fileNodes.map { node ->
                AtlasNodeRecord(
                    id = node.id,
                    entity = AtlasStoreSchema.ENTITY_FILE,
                    seed = 1,
                    build = node.build,
                    project = node.project,
                    sourceSet = node.sourceSet,
                    path = node.path,
                    name = node.name,
                    line = 0,
                    kind = "FILE",
                    semantic = "FILE",
                    fileId = node.id,
                    payload =
                        AtlasCborRenderer.encodeNode(
                            AtlasNodePayload(
                                importance = node.importance,
                                important = node.important,
                                symbolCount = node.symbols.size,
                                incomingRecordCount = node.totalIncomingRecordCount,
                                outgoingRecordCount = node.totalOutgoingRecordCount,
                                internalRecordCount = node.totalInternalRecordCount,
                                content = sourceById[node.id]?.content,
                                symbols =
                                    node.symbols.map { symbol ->
                                        AtlasFileSymbolPayload(
                                            id = symbol.id,
                                            name = symbol.name,
                                            kind = symbol.kind,
                                            semantic = symbol.declarationSemantic,
                                            line = symbol.line,
                                        )
                                    },
                            ),
                        ),
                )
            }
        val symbolNodes =
            graph.nodes.map { node ->
                val fileId =
                    AtlasStoreSchema.fileId(node.build, node.project, node.sourceSet, node.file)
                AtlasNodeRecord(
                    id = node.id,
                    entity = AtlasStoreSchema.ENTITY_SYMBOL,
                    seed = 1,
                    build = node.build,
                    project = node.project,
                    sourceSet = node.sourceSet,
                    path = node.file,
                    name = node.name,
                    line = node.line,
                    kind = node.kind,
                    semantic = node.declarationSemantic,
                    fileId = fileId,
                    payload =
                        AtlasCborRenderer.encodeNode(
                            AtlasNodePayload(
                                importance = node.importanceScore,
                                important = node.isImportant,
                            ),
                        ),
                )
            }
        val fileRelationships =
            graph.fileEdges.flatMap { edge ->
                val kinds = edge.kindCounts.ifEmpty { listOf(null) }
                kinds.map { kindCount ->
                    val kind = kindCount?.kind ?: AtlasStoreSchema.FAMILY_NON_IMPORT
                    val occurrences =
                        edge.occurrences
                            .filter { occurrence -> kindCount == null || occurrence.kind == kind }
                            .map { occurrence ->
                                AtlasOccurrencePayload(
                                    file = occurrence.file,
                                    line = occurrence.line,
                                    evidence = occurrence.evidence,
                                    context = occurrence.context,
                                )
                            }
                    AtlasRelationshipRecord(
                        id = AtlasStoreSchema.relationshipId(edge.source, kind, edge.target),
                        sourceId = edge.source,
                        targetId = edge.target,
                        kind = kind,
                        family = AtlasStoreSchema.FAMILY_NON_IMPORT,
                        recordCount = kindCount?.count ?: edge.recordCount,
                        payload =
                            AtlasCborRenderer.encodeRelationship(
                                AtlasRelationshipPayload(
                                    occurrences = occurrences,
                                    crossBuild = edge.crossBuild,
                                ),
                            ),
                    )
                }
            }
        val symbolRelationships =
            graph.edges.map { edge ->
                AtlasRelationshipRecord(
                    id = AtlasStoreSchema.relationshipId(edge.source, edge.kind, edge.target),
                    sourceId = edge.source,
                    targetId = edge.target,
                    kind = edge.kind,
                    family = AtlasStoreSchema.FAMILY_NON_IMPORT,
                    recordCount = edge.recordCount,
                    payload =
                        AtlasCborRenderer.encodeRelationship(
                            AtlasRelationshipPayload(
                                occurrences =
                                    edge.occurrences.map { occurrence ->
                                        AtlasOccurrencePayload(
                                            file = occurrence.file,
                                            line = occurrence.line,
                                            evidence = edge.evidence,
                                            context = occurrence.context,
                                        )
                                    },
                                crossBuild = edge.crossBuild,
                            ),
                        ),
                )
            }
        return AtlasStoreContents(
            meta =
                AtlasStoreMeta(
                    schemaVersion = AtlasStoreSchema.SCHEMA_VERSION,
                    generatedAt = generatedAt,
                    workspace = report.name,
                    seedLimit = AtlasStoreSchema.SEED_LIMIT,
                ),
            nodes = fileNodes + symbolNodes,
            relationships = fileRelationships + symbolRelationships,
            imports = importRows(report.workspaceIndex.relationships),
        )
    }

    private fun importRows(relationships: List<WorkspaceRelationship>): List<AtlasImportRecord> {
        val grouped =
            relationships
                .filter { relationship -> relationship.kind == WorkspaceRelationshipKind.IMPORT }
                .groupBy { relationship ->
                    val evidence = relationship.sourceEvidence
                    ImportKey(
                        sourceFileId =
                            AtlasStoreSchema.fileId(
                                evidence.build,
                                evidence.project,
                                evidence.sourceSet,
                                evidence.projectRelativeFile,
                            ),
                        targetId = relationship.target.identity.value,
                        targetFileId =
                            AtlasStoreSchema.fileId(
                                relationship.target.build,
                                relationship.target.project,
                                relationship.target.sourceSet,
                                relationship.target.projectRelativeFile,
                            ),
                    )
                }
        return grouped.map { (key, group) ->
            AtlasImportRecord(
                sourceFileId = key.sourceFileId,
                targetId = key.targetId,
                targetFileId = key.targetFileId,
                recordCount = group.size,
                payload =
                    AtlasCborRenderer.encodeOccurrences(
                        group.map { relationship ->
                            AtlasOccurrencePayload(
                                file = relationship.sourceEvidence.projectRelativeFile,
                                line = relationship.sourceEvidence.line,
                                evidence = relationship.evidence.name,
                                context = relationship.sourceEvidence.context,
                            )
                        },
                    ),
            )
        }
    }

    private data class ImportKey(
        val sourceFileId: String,
        val targetId: String,
        val targetFileId: String,
    )
}
