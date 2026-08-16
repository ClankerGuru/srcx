@file:Suppress("LongParameterList")

package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.DeclarationSemantic
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.ReferenceKind
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceLanguage
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.WorkspaceSummaryShard

internal fun sourceDetailSummary(): WorkspaceSummaryShard =
    detailSummary(
        workspaceId = DETAIL_WORKSPACE_ID,
        workspaceName = "Source detail workspace",
        buildId = DETAIL_BUILD_ID,
        projectId = DETAIL_PROJECT_ID,
        sourceSetId = DETAIL_SOURCE_SET_ID,
        fileCount = DETAIL_FILE_COUNT,
    )

internal fun sourceDetailShard(): ProjectGraphShard =
    ProjectGraphShard(
        projectId = DETAIL_PROJECT_ID,
        sourceSets = listOf(SourceSetSnapshot(DETAIL_SOURCE_SET_ID, DETAIL_PROJECT_ID, "main")),
        files =
            listOf(
                detailFile(DETAIL_CONFIG_FILE_ID, "src/main/resources/config.json", SourceLanguage.JSON),
                detailFile(DETAIL_SERVICE_FILE_ID, "src/main/kotlin/com/acme/feature/Service.kt"),
                detailFile(DETAIL_UTIL_FILE_ID, "src/main/kotlin/com/acme/shared/Util.kt"),
            ),
        symbols = detailSymbols(),
        references = listOf(detailReference()),
        relationships = listOf(detailRelationship()),
    )

internal fun sourceDetailImportShard(): ProjectGraphShard {
    val base = sourceDetailShard()
    val reference =
        ReferenceSnapshot(
            id = "reference:detail-import",
            sourceFileId = DETAIL_SERVICE_FILE_ID,
            line = 1,
            context = "import com.acme.shared.helper",
            targetName = "helper",
            targetQualifiedName = "com.acme.shared.helper",
            kind = ReferenceKind.IMPORT,
            evidence = RelationshipEvidence.DIRECT,
        )
    val relationship =
        RelationshipSnapshot(
            id = "relationship:detail-import",
            referenceId = reference.id,
            targetSymbolId = DETAIL_HELPER_MEMBER_ID,
            kind = RelationshipKind.IMPORT,
            resolutionEvidence = RelationshipEvidence.DIRECT,
        )
    return base.copy(references = listOf(reference), relationships = listOf(relationship))
}

internal fun relationshipFilterShard(): ProjectGraphShard {
    val base = sourceDetailShard()
    val extraEndpoints =
        listOf(
            DETAIL_RUN_MEMBER_ID to DETAIL_HELPER_MEMBER_ID,
            DETAIL_RUN_MEMBER_ID to DETAIL_STATE_MEMBER_ID,
            DETAIL_STATE_MEMBER_ID to DETAIL_RUN_MEMBER_ID,
        )
    val extraReferences = extraEndpoints.mapIndexed(::relationshipFilterReference)
    val extraRelationships =
        extraEndpoints.mapIndexed { index, endpoints -> relationshipFilterRelationship(index, endpoints) }
    return base.copy(
        references = (base.references + extraReferences).sortedBy(ReferenceSnapshot::id),
        relationships = (base.relationships + extraRelationships).sortedBy(RelationshipSnapshot::id),
    )
}

internal fun problemCycleShard(): ProjectGraphShard {
    val base = relationshipFilterShard()
    return base.copy(
        findings =
            listOf(
                detailFinding(
                    id = DETAIL_CLASS_FINDING_ID,
                    message = "Class boundary problem",
                    fileId = DETAIL_SERVICE_FILE_ID,
                    filePath = DETAIL_SERVICE_FILE_PATH,
                    symbolIds = listOf(DETAIL_SERVICE_TYPE_ID),
                ),
                detailFinding(
                    id = DETAIL_FILE_FINDING_ID,
                    message = "Configuration file problem",
                    fileId = DETAIL_CONFIG_FILE_ID,
                    filePath = DETAIL_CONFIG_FILE_PATH,
                ),
                detailFinding(
                    id = DETAIL_METHOD_FINDING_ID,
                    message = "Method complexity problem",
                    fileId = DETAIL_SERVICE_FILE_ID,
                    filePath = DETAIL_SERVICE_FILE_PATH,
                    symbolIds = listOf(DETAIL_RUN_MEMBER_ID),
                ),
                detailFinding(
                    id = DETAIL_PROJECT_FINDING_ID,
                    message = "Project configuration problem",
                ),
                detailFinding(
                    id = DETAIL_SYMBOL_FINDING_ID,
                    message = "Symbol-only method problem",
                    symbolIds = listOf(DETAIL_RUN_MEMBER_ID),
                ),
            ),
        cycles =
            listOf(
                CycleSnapshot(
                    id = DETAIL_CYCLE_ID,
                    symbolIds =
                        listOf(
                            DETAIL_RUN_MEMBER_ID,
                            DETAIL_STATE_MEMBER_ID,
                            DETAIL_RUN_MEMBER_ID,
                        ),
                    relationshipIds = listOf("relationship:filter:1", "relationship:filter:2"),
                ),
            ),
    )
}

internal fun boundedProblemShard(): ProjectGraphShard {
    val base = sourceDetailShard()
    return base.copy(
        references = emptyList(),
        relationships = emptyList(),
        findings = (0 until BOUNDED_PROBLEM_COUNT).map(::boundedProblem),
    )
}

internal fun boundedMemberSummary(): WorkspaceSummaryShard =
    detailSummary(
        workspaceId = BOUNDED_WORKSPACE_ID,
        workspaceName = "Bounded member workspace",
        buildId = BOUNDED_BUILD_ID,
        projectId = BOUNDED_PROJECT_ID,
        sourceSetId = BOUNDED_SOURCE_SET_ID,
        fileCount = 1,
    )

internal fun boundedMemberShard(): ProjectGraphShard =
    ProjectGraphShard(
        projectId = BOUNDED_PROJECT_ID,
        sourceSets = listOf(SourceSetSnapshot(BOUNDED_SOURCE_SET_ID, BOUNDED_PROJECT_ID, "main")),
        files =
            listOf(
                SourceFileSnapshot(
                    id = BOUNDED_FILE_ID,
                    sourceSetId = BOUNDED_SOURCE_SET_ID,
                    projectRelativePath = "src/main/kotlin/fixture/large/Members.kt",
                    language = SourceLanguage.KOTLIN,
                ),
            ),
        symbols = (0 until BOUNDED_MEMBER_COUNT).map(::boundedMember),
        relationships = emptyList(),
    )

private fun detailSummary(
    workspaceId: String,
    workspaceName: String,
    buildId: String,
    projectId: String,
    sourceSetId: String,
    fileCount: Int,
): WorkspaceSummaryShard =
    WorkspaceSummaryShard(
        workspace = WorkspaceIdentity(workspaceId, workspaceName),
        builds = listOf(BuildSnapshot(buildId, "source-build", BuildKind.ROOT, ".")),
        projects = listOf(ProjectSnapshot(projectId, buildId, ":source", "build.gradle.kts")),
        sourceSets = listOf(WorkspaceSourceSetSummary(sourceSetId, projectId, "main", fileCount)),
        projectShards = listOf(ProjectShardReference(projectId, "projects/source.cbor")),
    )

private fun detailFile(
    id: String,
    path: String,
    language: SourceLanguage = SourceLanguage.KOTLIN,
): SourceFileSnapshot =
    SourceFileSnapshot(
        id = id,
        sourceSetId = DETAIL_SOURCE_SET_ID,
        projectRelativePath = path,
        language = language,
    )

private fun detailFinding(
    id: String,
    message: String,
    fileId: String? = null,
    filePath: String? = null,
    symbolIds: List<String> = emptyList(),
): FindingSnapshot =
    FindingSnapshot(
        id = id,
        severity = FindingSeverity.WARNING,
        message = message,
        suggestion = "Review the captured source evidence",
        fileId = fileId,
        filePath = filePath,
        symbolIds = symbolIds,
    )

private fun detailSymbol(
    id: String,
    fileId: String,
    name: String,
    qualifiedName: String,
    packageName: String,
    kind: SymbolKind,
): SymbolSnapshot =
    SymbolSnapshot(
        id = id,
        fileId = fileId,
        name = name,
        qualifiedName = qualifiedName,
        packageName = packageName,
        kind = kind,
        declarationSemantic = DeclarationSemantic.OTHER,
        declarationLine = 1,
    )

private fun detailSymbols(): List<SymbolSnapshot> =
    listOf(
        detailSymbol(
            id = DETAIL_SERVICE_TYPE_ID,
            fileId = DETAIL_SERVICE_FILE_ID,
            name = "Service",
            qualifiedName = "com.acme.feature.Service",
            packageName = DETAIL_FEATURE_PACKAGE,
            kind = SymbolKind.CLASS,
        ),
        detailSymbol(
            id = DETAIL_RUN_MEMBER_ID,
            fileId = DETAIL_SERVICE_FILE_ID,
            name = "run",
            qualifiedName = "com.acme.feature.run",
            packageName = DETAIL_FEATURE_PACKAGE,
            kind = SymbolKind.FUNCTION,
        ),
        detailSymbol(
            id = DETAIL_STATE_MEMBER_ID,
            fileId = DETAIL_SERVICE_FILE_ID,
            name = "state",
            qualifiedName = "com.acme.feature.state",
            packageName = DETAIL_FEATURE_PACKAGE,
            kind = SymbolKind.PROPERTY,
        ),
        detailSymbol(
            id = DETAIL_UTIL_TYPE_ID,
            fileId = DETAIL_UTIL_FILE_ID,
            name = "Util",
            qualifiedName = "com.acme.shared.Util",
            packageName = DETAIL_SHARED_PACKAGE,
            kind = SymbolKind.OBJECT,
        ),
        detailSymbol(
            id = DETAIL_HELPER_MEMBER_ID,
            fileId = DETAIL_UTIL_FILE_ID,
            name = "helper",
            qualifiedName = "com.acme.shared.helper",
            packageName = DETAIL_SHARED_PACKAGE,
            kind = SymbolKind.FUNCTION,
        ),
    )

private fun detailReference(): ReferenceSnapshot =
    ReferenceSnapshot(
        id = DETAIL_REFERENCE_ID,
        sourceFileId = DETAIL_SERVICE_FILE_ID,
        sourceSymbolId = DETAIL_RUN_MEMBER_ID,
        line = DETAIL_REFERENCE_LINE,
        context = "helper()",
        targetName = "helper",
        targetQualifiedName = "com.acme.shared.helper",
        kind = ReferenceKind.CALL,
        evidence = RelationshipEvidence.DIRECT,
    )

private fun detailRelationship(): RelationshipSnapshot =
    RelationshipSnapshot(
        id = DETAIL_RELATIONSHIP_ID,
        referenceId = DETAIL_REFERENCE_ID,
        sourceSymbolId = DETAIL_RUN_MEMBER_ID,
        targetSymbolId = DETAIL_HELPER_MEMBER_ID,
        kind = RelationshipKind.CALL,
        resolutionEvidence = RelationshipEvidence.DIRECT,
    )

private fun relationshipFilterReference(
    index: Int,
    endpoints: Pair<String, String>,
): ReferenceSnapshot =
    ReferenceSnapshot(
        id = relationshipFilterReferenceId(index),
        sourceFileId = sourceFileId(endpoints.first),
        sourceSymbolId = endpoints.first,
        line = RELATIONSHIP_FILTER_LINE_BASE + index,
        context = "fixture relationship $index",
        targetName = targetSymbolName(endpoints.second),
        targetQualifiedName = targetSymbolQualifiedName(endpoints.second),
        kind = ReferenceKind.CALL,
        evidence = RelationshipEvidence.DIRECT,
    )

private fun relationshipFilterRelationship(
    index: Int,
    endpoints: Pair<String, String>,
): RelationshipSnapshot =
    RelationshipSnapshot(
        id = "relationship:filter:$index",
        referenceId = relationshipFilterReferenceId(index),
        sourceSymbolId = endpoints.first,
        targetSymbolId = endpoints.second,
        kind = RelationshipKind.CALL,
        resolutionEvidence = RelationshipEvidence.DIRECT,
    )

private fun relationshipFilterReferenceId(index: Int): String = "reference:filter:$index"

private fun sourceFileId(symbolId: String): String =
    when (symbolId) {
        DETAIL_RUN_MEMBER_ID,
        DETAIL_STATE_MEMBER_ID,
        -> DETAIL_SERVICE_FILE_ID

        DETAIL_HELPER_MEMBER_ID -> DETAIL_UTIL_FILE_ID
        else -> error("Unknown relationship-filter symbol: $symbolId")
    }

private fun targetSymbolName(symbolId: String): String = symbolId.substringAfterLast(':')

private fun targetSymbolQualifiedName(symbolId: String): String =
    when (symbolId) {
        DETAIL_RUN_MEMBER_ID -> "com.acme.feature.run"
        DETAIL_STATE_MEMBER_ID -> "com.acme.feature.state"
        DETAIL_HELPER_MEMBER_ID -> "com.acme.shared.helper"
        else -> error("Unknown relationship-filter target: $symbolId")
    }

private fun boundedMember(index: Int): SymbolSnapshot {
    val suffix = index.toString().padStart(BOUNDED_MEMBER_ID_WIDTH, '0')
    return SymbolSnapshot(
        id = "symbol:bounded:$suffix",
        fileId = BOUNDED_FILE_ID,
        name = "member$suffix",
        qualifiedName = "fixture.large.member$suffix",
        packageName = BOUNDED_PACKAGE,
        kind = SymbolKind.FUNCTION,
        declarationSemantic = DeclarationSemantic.OTHER,
        declarationLine = index + 1,
    )
}

private fun boundedProblem(index: Int): FindingSnapshot {
    val suffix = index.toString().padStart(BOUNDED_MEMBER_ID_WIDTH, '0')
    return detailFinding(
        id = "finding:bounded:$suffix",
        message = "Bounded problem $suffix",
        fileId = DETAIL_SERVICE_FILE_ID,
        filePath = DETAIL_SERVICE_FILE_PATH,
        symbolIds = listOf(DETAIL_SERVICE_TYPE_ID),
    )
}

internal const val DETAIL_WORKSPACE_ID: String = "workspace:source-detail"
internal const val DETAIL_BUILD_ID: String = "build:source-detail"
internal const val DETAIL_PROJECT_ID: String = "project:source-detail"
internal const val DETAIL_SOURCE_SET_ID: String = "source-set:source-detail:main"
internal const val DETAIL_CONFIG_FILE_ID: String = "file:config"
internal const val DETAIL_SERVICE_FILE_ID: String = "file:feature:Service"
internal const val DETAIL_UTIL_FILE_ID: String = "file:shared:Util"
internal const val DETAIL_SERVICE_TYPE_ID: String = "symbol:feature:Service"
internal const val DETAIL_RUN_MEMBER_ID: String = "symbol:feature:run"
internal const val DETAIL_STATE_MEMBER_ID: String = "symbol:feature:state"
internal const val DETAIL_UTIL_TYPE_ID: String = "symbol:shared:Util"
internal const val DETAIL_HELPER_MEMBER_ID: String = "symbol:shared:helper"
internal const val DETAIL_FEATURE_PACKAGE: String = "com.acme.feature"
internal const val DETAIL_SHARED_PACKAGE: String = "com.acme.shared"
internal const val DETAIL_RELATIONSHIP_ID: String = "relationship:detail:call"
internal const val DETAIL_CLASS_FINDING_ID: String = "finding:class"
internal const val DETAIL_FILE_FINDING_ID: String = "finding:file"
internal const val DETAIL_METHOD_FINDING_ID: String = "finding:method"
internal const val DETAIL_PROJECT_FINDING_ID: String = "finding:project"
internal const val DETAIL_SYMBOL_FINDING_ID: String = "finding:symbol"
internal const val DETAIL_CYCLE_ID: String = "cycle:filter:service"
internal const val BOUNDED_PROBLEM_COUNT: Int = 500
private const val DETAIL_REFERENCE_ID: String = "reference:detail:call"
private const val DETAIL_CONFIG_FILE_PATH: String = "src/main/resources/config.json"
private const val DETAIL_SERVICE_FILE_PATH: String = "src/main/kotlin/com/acme/feature/Service.kt"
private const val DETAIL_REFERENCE_LINE: Int = 5
private const val RELATIONSHIP_FILTER_LINE_BASE: Int = 20
internal const val DETAIL_FILE_COUNT: Int = 3
internal const val BOUNDED_WORKSPACE_ID: String = "workspace:bounded-members"
internal const val BOUNDED_BUILD_ID: String = "build:bounded-members"
internal const val BOUNDED_PROJECT_ID: String = "project:bounded-members"
internal const val BOUNDED_SOURCE_SET_ID: String = "source-set:bounded-members:main"
internal const val BOUNDED_FILE_ID: String = "file:bounded-members"
internal const val BOUNDED_PACKAGE: String = "fixture.large"
internal const val BOUNDED_MEMBER_COUNT: Int = 500
private const val BOUNDED_MEMBER_ID_WIDTH: Int = 3
