@file:Suppress("LongParameterList")

package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** Small generation catalog loaded before any workspace or project shard. */
@Serializable
data class WorkspaceSiteManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val generationId: String,
    val snapshotSchemaVersion: Int,
    val workspaceFile: String,
    val dashboardFile: String,
    val atlasOverviewFile: String? = null,
    val atlasOverviewsFile: String? = null,
    val projectShards: List<ProjectShardReference>,
    val assetFiles: List<String>,
    val searchCatalog: WorkspaceSearchCatalogReference? = null,
    val evidenceCatalog: WorkspaceStaticEvidenceCatalogReference? = null,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported workspace-site manifest schema: $schemaVersion"
        }
        require(snapshotSchemaVersion == WorkspaceSnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported workspace snapshot schema: $snapshotSchemaVersion"
        }
        require(generationId.isNotBlank()) { "Site generation ID must not be blank" }
        requireNormalizedRelativePath(workspaceFile, "Workspace summary file")
        requireNormalizedRelativePath(dashboardFile, "Workspace dashboard file")
        atlasOverviewFile?.let { file -> requireNormalizedRelativePath(file, "Workspace Atlas overview file") }
        atlasOverviewsFile?.let { file -> requireNormalizedRelativePath(file, "Workspace Atlas overviews file") }
        requireUniqueAndSorted(projectShards.map(ProjectShardReference::projectId), "manifest project shards")
        require(projectShards.map(ProjectShardReference::file).distinct().size == projectShards.size) {
            "Manifest project-shard files must be unique"
        }
        assetFiles.forEach { requireNormalizedRelativePath(it, "Site asset file") }
        requireDistinctAndSorted(assetFiles, "Manifest asset files")
        searchCatalog?.let { reference ->
            requireNormalizedRelativePath(reference.file, "Workspace search catalog")
        }
        evidenceCatalog?.let { reference ->
            requireNormalizedRelativePath(reference.file, "Workspace evidence catalog")
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
    }
}

/** Constant-size workspace frames used before any lazy project shard is requested. */
@Serializable
data class WorkspaceAtlasOverviewShard(
    val frames: List<AtlasFrame>,
) {
    init {
        require(frames.map(AtlasFrame::lens) == AtlasLens.entries) {
            "Workspace Atlas overviews must contain one deterministic frame for every lens"
        }
        require(frames.all { frame -> frame.scope.kind == AtlasScopeKind.WORKSPACE }) {
            "Workspace Atlas overviews must use workspace scope"
        }
        require(frames.map { frame -> frame.scope.workspaceId }.distinct().size == 1) {
            "Workspace Atlas overviews must belong to one workspace"
        }
        require(frames.all { frame -> frame.nodes.size <= AtlasFrame.MAX_VISIBLE_NODES }) {
            "Workspace Atlas overviews must remain within the bounded node limit"
        }
    }

    fun frame(lens: AtlasLens): AtlasFrame = frames.single { frame -> frame.lens == lens }
}

@Serializable
data class ProjectShardReference(
    val projectId: String,
    val file: String,
    val sourceContents: List<SourceContentReference> = emptyList(),
) {
    init {
        requireValidId(projectId, "Project-shard project")
        requireNormalizedRelativePath(file, "Project-shard file")
        requireUniqueAndSorted(sourceContents.map(SourceContentReference::fileId), "project source contents")
    }
}

/** Navigation-sized workspace/build/project/source-set catalog. */
@Serializable
data class WorkspaceSummaryShard(
    val schemaVersion: Int = WorkspaceSnapshot.CURRENT_SCHEMA_VERSION,
    val workspace: WorkspaceIdentity,
    val builds: List<BuildSnapshot>,
    val projects: List<ProjectSnapshot>,
    val sourceSets: List<WorkspaceSourceSetSummary> = emptyList(),
    val projectShards: List<ProjectShardReference>,
    val buildEdges: List<BuildEdgeSnapshot> = emptyList(),
) {
    init {
        require(schemaVersion == WorkspaceSnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported workspace-summary schema: $schemaVersion"
        }
        requireUniqueAndSorted(builds.map(BuildSnapshot::id), "summary builds")
        requireUniqueAndSorted(buildEdges.map(BuildEdgeSnapshot::id), "summary build edges")
        requireUniqueAndSorted(projects.map(ProjectSnapshot::id), "summary projects")
        requireUniqueAndSorted(sourceSets.map(WorkspaceSourceSetSummary::id), "summary source sets")
        requireUniqueAndSorted(projectShards.map(ProjectShardReference::projectId), "summary project shards")
        val buildIds = builds.mapTo(mutableSetOf(), BuildSnapshot::id)
        require(builds.count { it.kind == BuildKind.ROOT } == 1) { "A workspace summary needs one root build" }
        require(projects.all { it.buildId in buildIds }) { "Summary projects must reference known builds" }
        require(buildEdges.all { it.sourceBuildId in buildIds && it.targetBuildId in buildIds }) {
            "Summary build edges must reference known builds"
        }
        val projectIds = projects.mapTo(mutableSetOf(), ProjectSnapshot::id)
        require(sourceSets.all { sourceSet -> sourceSet.projectId in projectIds }) {
            "Summary source sets must reference known projects"
        }
        require(projectShards.map(ProjectShardReference::projectId).toSet() == projectIds) {
            "Workspace summary must reference exactly one shard for every project"
        }
    }
}

/** Lazy project-owned graph and analysis data loaded for one active project. */
@Serializable
data class ProjectGraphShard(
    val schemaVersion: Int = WorkspaceSnapshot.CURRENT_SCHEMA_VERSION,
    val projectId: String,
    val sourceSets: List<SourceSetSnapshot>,
    val files: List<SourceFileSnapshot>,
    val symbols: List<SymbolSnapshot>,
    val ownedSourceSetIds: List<String> =
        sourceSets.filter { it.projectId == projectId }.map(SourceSetSnapshot::id),
    val ownedFileIds: List<String> =
        files.filter { it.sourceSetId in ownedSourceSetIds }.map(SourceFileSnapshot::id),
    val ownedSymbolIds: List<String> =
        symbols.filter { it.fileId in ownedFileIds }.map(SymbolSnapshot::id),
    val references: List<ReferenceSnapshot> = emptyList(),
    val relationships: List<RelationshipSnapshot>,
    val findings: List<FindingSnapshot> = emptyList(),
    val cycles: List<CycleSnapshot> = emptyList(),
    val analysis: ProjectAnalysisSnapshot? = null,
    val sourceContents: List<SourceContentReference> = emptyList(),
) {
    init {
        require(schemaVersion == WorkspaceSnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported project-graph schema: $schemaVersion"
        }
        requireValidId(projectId, "Project-shard project")
        requireUniqueAndSorted(sourceSets.map(SourceSetSnapshot::id), "project-shard source sets")
        requireUniqueAndSorted(files.map(SourceFileSnapshot::id), "project-shard files")
        requireUniqueAndSorted(symbols.map(SymbolSnapshot::id), "project-shard symbols")
        requireDistinctAndSorted(ownedSourceSetIds, "project-shard owned source sets")
        requireDistinctAndSorted(ownedFileIds, "project-shard owned files")
        requireDistinctAndSorted(ownedSymbolIds, "project-shard owned symbols")
        requireUniqueAndSorted(references.map(ReferenceSnapshot::id), "project-shard references")
        requireUniqueAndSorted(relationships.map(RelationshipSnapshot::id), "project-shard relationships")
        requireUniqueAndSorted(findings.map(FindingSnapshot::id), "project-shard findings")
        requireUniqueAndSorted(cycles.map(CycleSnapshot::id), "project-shard cycles")
        requireUniqueAndSorted(sourceContents.map(SourceContentReference::fileId), "project source contents")
        val sourceSetIds = sourceSets.mapTo(mutableSetOf(), SourceSetSnapshot::id)
        require(ownedSourceSetIds == sourceSets.filter { it.projectId == projectId }.map(SourceSetSnapshot::id)) {
            "Owned source sets must exactly identify the shard project's serialized source sets"
        }
        require(files.all { it.sourceSetId in sourceSetIds }) { "Shard files must reference serialized source sets" }
        val filesById = files.associateBy(SourceFileSnapshot::id)
        require(ownedFileIds == files.filter { it.sourceSetId in ownedSourceSetIds }.map(SourceFileSnapshot::id)) {
            "Owned files must exactly identify files from owned source sets"
        }
        require(sourceContents.all { source -> source.fileId in ownedFileIds }) {
            "Project source contents must reference project-owned files"
        }
        require(symbols.all { it.fileId in filesById }) { "Shard symbols must reference its files" }
        val symbolsById = symbols.associateBy(SymbolSnapshot::id)
        val symbolIds = symbolsById.keys
        require(ownedSymbolIds == symbols.filter { it.fileId in ownedFileIds }.map(SymbolSnapshot::id)) {
            "Owned symbols must exactly identify declarations from owned files"
        }
        val relationshipsById = relationships.associateBy(RelationshipSnapshot::id)
        val cycleRelationshipIds = cycles.flatMapTo(mutableSetOf(), CycleSnapshot::relationshipIds)
        require(cycleRelationshipIds.all(relationshipsById::containsKey)) {
            "Shard cycles must reference serialized relationships"
        }
        val cycleReferenceIds =
            cycleRelationshipIds.mapTo(mutableSetOf()) { relationshipId ->
                relationshipsById.getValue(relationshipId).referenceId
            }
        require(
            references.all { reference ->
                reference.sourceFileId in filesById &&
                    reference.sourceSymbolId?.let(symbolIds::contains) != false &&
                    (reference.sourceFileId in ownedFileIds || reference.id in cycleReferenceIds)
            },
        ) { "External shard references must provide serialized evidence for a retained cycle" }
        references.forEach { reference ->
            reference.sourceSymbolId?.let { symbolId ->
                require(symbolsById.getValue(symbolId).fileId == reference.sourceFileId) {
                    "Shard reference source symbols must belong to their source file"
                }
            }
        }
        val referencesById = references.associateBy(ReferenceSnapshot::id)
        val referenceIds = referencesById.keys
        require(
            relationships.all { relationship ->
                relationship.referenceId in referenceIds &&
                    relationship.targetSymbolId in symbolIds &&
                    relationship.sourceSymbolId?.let(symbolIds::contains) != false &&
                    (
                        referencesById.getValue(relationship.referenceId).sourceFileId in ownedFileIds ||
                            relationship.id in cycleRelationshipIds
                    )
            },
        ) { "External shard relationships must be serialized cycle evidence with complete endpoint closure" }
        relationships.forEach { relationship ->
            val reference = referencesById.getValue(relationship.referenceId)
            val target = symbolsById.getValue(relationship.targetSymbolId)
            require(
                target.name.substringAfterLast('.') == reference.targetName &&
                    (reference.targetQualifiedName == null || target.qualifiedName == reference.targetQualifiedName),
            ) { "Shard relationship targets must match their resolved source evidence" }
            require(relationship.kind.accepts(reference.kind)) {
                "Shard relationship kinds must agree with their source-reference kind"
            }
            require(
                if (relationship.kind == RelationshipKind.IMPORT) {
                    relationship.sourceSymbolId == null
                } else {
                    relationship.sourceSymbolId != null && relationship.sourceSymbolId == reference.sourceSymbolId
                },
            ) { "Shard relationship source symbols must match their source evidence" }
        }
        require(cycles.flatMap(CycleSnapshot::symbolIds).all { it in symbolIds }) {
            "Shard cycles must reference serialized symbols"
        }
        val directedSteps =
            relationships
                .mapNotNull { relationship ->
                    relationship.sourceSymbolId?.let { it to relationship.targetSymbolId }
                }.toSet()
        require(
            cycles.all { cycle ->
                cycle.symbolIds.zipWithNext().all(directedSteps::contains) &&
                    cycle.relationshipIds.zip(cycle.symbolIds.zipWithNext()).all { (relationshipId, step) ->
                        relationshipsById[relationshipId]?.let { relationship ->
                            relationship.kind != RelationshipKind.IMPORT &&
                                relationship.sourceSymbolId == step.first &&
                                relationship.targetSymbolId == step.second
                        } == true
                    }
            },
        ) { "Shard cycles must follow serialized directed relationship evidence" }
        requireFindingClosure(findings, filesById, symbolIds, ownedFileIds)
        require(analysis == null || analysis.projectId == projectId) {
            "Shard analysis must belong to its project"
        }
        analysis?.let { projectAnalysis ->
            val componentIds = projectAnalysis.components.mapTo(mutableSetOf(), ArchitectureComponentSnapshot::id)
            require(
                projectAnalysis.components.all { component ->
                    component.sourceFileId?.let(ownedFileIds::contains) != false &&
                        component.symbolId?.let(ownedSymbolIds::contains) != false
                },
            ) { "Shard analysis components must reference project-owned source evidence" }
            projectAnalysis.components.forEach { component ->
                if (component.sourceFileId != null && component.symbolId != null) {
                    require(symbolsById.getValue(component.symbolId).fileId == component.sourceFileId) {
                        "Shard analysis component symbols must belong to their source file"
                    }
                }
            }
            require(
                projectAnalysis.hubs.all { hub -> hub.sourceFileId?.let(ownedFileIds::contains) != false },
            ) { "Shard analysis hubs must reference project-owned files" }
            requireFindingClosure(projectAnalysis.findings, filesById, symbolIds, ownedFileIds)
            require(
                projectAnalysis.findings
                    .flatMap(FindingSnapshot::resolvedComponentIds)
                    .all(componentIds::contains),
            ) { "Shard analysis findings must reference serialized components" }
        }
    }
}

private fun requireFindingClosure(
    findings: List<FindingSnapshot>,
    filesById: Map<String, SourceFileSnapshot>,
    symbolIds: Set<String>,
    ownedFileIds: List<String>,
) {
    require(
        findings.all { finding ->
            finding.fileId?.let(ownedFileIds::contains) != false && finding.symbolIds.all(symbolIds::contains)
        },
    ) { "Shard findings must reference serialized files and symbols" }
    findings.forEach { finding ->
        finding.fileId?.let { fileId ->
            finding.line?.let { line ->
                requireLineWithinContent(line, filesById.getValue(fileId).content, "Shard finding line")
            }
        }
    }
}

/** Last-known publication state read before loading a generation. */
@Serializable
data class WorkspaceSiteStatus(
    val schemaVersion: Int = WorkspaceSiteManifest.CURRENT_SCHEMA_VERSION,
    val state: WorkspaceSiteState,
    val generationId: String? = null,
    val workspaceName: String? = null,
    val message: String,
) {
    init {
        require(schemaVersion == WorkspaceSiteManifest.CURRENT_SCHEMA_VERSION) {
            "Unsupported workspace-site status schema: $schemaVersion"
        }
        require(generationId == null || generationId.isNotBlank()) { "Status generation ID must not be blank" }
        require(workspaceName == null || workspaceName.isNotBlank()) { "Status workspace name must not be blank" }
        require(message.isNotBlank()) { "Status message must not be blank" }
    }
}

@Serializable
enum class WorkspaceSiteState(
    val label: String,
) {
    CURRENT("Current"),
    UPDATING("Updating"),
    STALE("Stale"),
    FAILED("Failed"),
}
