@file:Suppress("TooManyFunctions")

package zone.clanker.gradle.srcx.snapshot

import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSourceFile
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolIdentity
import zone.clanker.report.model.BuildEdgeSnapshot
import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.DeclarationSemantic
import zone.clanker.report.model.ProjectDependencySnapshot
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceLanguage
import zone.clanker.report.model.SourceRangeSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceSnapshot
import zone.clanker.report.model.ReferenceKind as SnapshotReferenceKind

/** Pure conversion from the SRCX report model into its renderer-neutral public snapshot. */
data object WorkspaceSnapshotMapper {
    fun workspaceId(
        canonicalRootPath: String,
        rootBuildName: String,
    ): String = stableSnapshotId("workspace", canonicalRootPath, rootBuildName)

    fun map(
        report: WorkspaceReport,
        workspaceId: String,
    ): WorkspaceSnapshot {
        val builds = buildSnapshots(report)
        val buildIds = builds.associate { it.name to it.id }
        val projectScopes = projectScopes(report, buildIds)
        val projects = projectSnapshots(projectScopes)
        val projectIds = projectScopes.associate { it.key to it.id }
        val sourceSets = sourceSetSnapshots(report, projectScopes)
        val sourceSetIds = sourceSets.associate { sourceSetKey(it, projects) to it.id }
        val files = fileSnapshots(report.sourceFiles, sourceSetIds)
        val fileIds = report.sourceFiles.associate { it.scopeKey() to sourceFileId(it) }
        val symbolIds = report.workspaceIndex.symbols.associate { it.identity to symbolId(it) }
        val symbols = symbolSnapshots(report, projectScopes, fileIds, symbolIds)
        val references = referenceSnapshots(report.workspaceIndex.references, fileIds, symbolIds)
        val relationshipRecords = relationshipRecords(report.workspaceIndex.relationships, references, symbolIds)
        val context =
            SnapshotMappingContext(
                report = report,
                buildIds = buildIds,
                projectScopes = projectScopes,
                projectIds = projectIds,
                files = files,
                fileIds = fileIds,
                symbols = symbols,
                symbolIds = symbolIds,
                relationshipRecords = relationshipRecords,
            )
        return WorkspaceSnapshot(
            workspace = WorkspaceIdentity(workspaceId, report.name),
            builds = builds,
            buildEdges = buildEdgeSnapshots(report, buildIds),
            projects = projects,
            projectDependencies = projectDependencySnapshots(projectScopes),
            sourceSets = sourceSets,
            files = files,
            symbols = symbols,
            references = references.map(ReferenceRecord::snapshot),
            relationships = relationshipRecords.map(RelationshipRecord::snapshot),
            relationshipCycles = relationshipCycleSnapshots(relationshipRecords),
            projectAnalyses = context.projectAnalysisSnapshots(),
            aggregateAnalysisPresent = report.aggregateAnalysis != null,
            aggregateFindings = context.aggregateFindingSnapshots(),
            aggregateHubs = context.aggregateHubSnapshots(),
            aggregateNamedCycles = context.aggregateNamedCycleSnapshots(),
            entryPoints = context.entryPointSnapshots(),
            interfaces = context.interfaceSnapshots(),
            importantSymbols = context.importantSymbolSnapshots(),
            dependencyInjection = null,
        )
    }
}

private fun buildSnapshots(report: WorkspaceReport): List<BuildSnapshot> =
    (
        listOf(
            BuildSnapshot(
                id = stableSnapshotId("build", report.name),
                name = report.name,
                kind = BuildKind.ROOT,
                relativePath = ".",
            ),
        ) +
            report.includedBuilds.map { build ->
                BuildSnapshot(
                    id = stableSnapshotId("build", build.name),
                    name = build.name,
                    kind = BuildKind.INCLUDED,
                    relativePath = build.relativePath.normalizedPath(),
                )
            }
    ).sortedBy(BuildSnapshot::id)

private fun projectScopes(
    report: WorkspaceReport,
    buildIds: Map<String, String>,
): List<SnapshotProjectScope> =
    (
        report.rootProjects.map { summary -> projectScope(report.name, buildIds.getValue(report.name), summary) } +
            report.includedBuilds.flatMap { build ->
                build.projects.map { summary -> projectScope(build.name, buildIds.getValue(build.name), summary) }
            }
    ).sortedBy(SnapshotProjectScope::id)

private fun projectScope(
    buildName: String,
    buildId: String,
    summary: ProjectSummary,
): SnapshotProjectScope =
    SnapshotProjectScope(
        id = stableSnapshotId("project", buildName, summary.projectPath.value),
        buildName = buildName,
        buildId = buildId,
        summary = summary,
    )

private fun projectSnapshots(scopes: List<SnapshotProjectScope>): List<ProjectSnapshot> =
    scopes.map { scope ->
        ProjectSnapshot(
            id = scope.id,
            buildId = scope.buildId,
            path = scope.projectPath,
            buildFile = scope.summary.buildFile,
            sourceDirectories =
                scope.summary.sourceDirs
                    .map(String::normalizedPath)
                    .distinct()
                    .sorted(),
            subprojectPaths =
                scope.summary.subprojects
                    .distinct()
                    .sorted(),
        )
    }

private fun sourceSetSnapshots(
    report: WorkspaceReport,
    scopes: List<SnapshotProjectScope>,
): List<SourceSetSnapshot> =
    scopes
        .flatMap { scope ->
            val names = sourceSetNames(report, scope)
            val summaries = scope.summary.sourceSets.associateBy { it.name.value }
            names.map { name ->
                SourceSetSnapshot(
                    id = stableSnapshotId("source-set", scope.buildName, scope.projectPath, name),
                    projectId = scope.id,
                    name = name,
                    sourceDirectories =
                        summaries[name]
                            ?.sourceDirs
                            .orEmpty()
                            .map(String::normalizedPath)
                            .distinct()
                            .sorted(),
                )
            }
        }.sortedBy(SourceSetSnapshot::id)

private fun sourceSetNames(
    report: WorkspaceReport,
    scope: SnapshotProjectScope,
): List<String> =
    (
        scope.summary.sourceSets.map { it.name.value } +
            report.sourceFiles
                .filter { it.build == scope.buildName && it.project == scope.projectPath }
                .map(WorkspaceSourceFile::sourceSet) +
            report.workspaceIndex.symbols
                .filter { it.build == scope.buildName && it.project == scope.projectPath }
                .map(WorkspaceSymbol::sourceSet) +
            report.workspaceIndex.references
                .filter { it.build == scope.buildName && it.project == scope.projectPath }
                .map(WorkspaceReference::sourceSet)
    ).distinct().sorted()

private fun sourceSetKey(
    sourceSet: SourceSetSnapshot,
    projects: List<ProjectSnapshot>,
): SourceSetScopeKey {
    val project = projects.single { it.id == sourceSet.projectId }
    return SourceSetScopeKey(project.buildId, project.path, sourceSet.name)
}

private fun fileSnapshots(
    sourceFiles: List<WorkspaceSourceFile>,
    sourceSetIds: Map<SourceSetScopeKey, String>,
): List<SourceFileSnapshot> =
    sourceFiles
        .map { sourceFile ->
            val buildId = stableSnapshotId("build", sourceFile.build)
            SourceFileSnapshot(
                id = sourceFileId(sourceFile),
                sourceSetId =
                    sourceSetIds.getValue(
                        SourceSetScopeKey(buildId, sourceFile.project, sourceFile.sourceSet),
                    ),
                projectRelativePath = sourceFile.projectRelativeFile.normalizedPath(),
                language = sourceLanguage(sourceFile.projectRelativeFile),
                content = sourceFile.content,
            )
        }.sortedBy(SourceFileSnapshot::id)

private fun symbolSnapshots(
    report: WorkspaceReport,
    scopes: List<SnapshotProjectScope>,
    fileIds: Map<FileScopeKey, String>,
    symbolIds: Map<WorkspaceSymbolIdentity, String>,
): List<SymbolSnapshot> {
    val symbolsByOwnerKey = report.workspaceIndex.symbols.groupBy { symbol -> symbol.ownerKey() }
    return report.workspaceIndex.symbols
        .map { symbol ->
            SymbolSnapshot(
                id = symbolId(symbol),
                fileId = fileIds.getValue(symbol.fileScopeKey()),
                name = symbol.name,
                qualifiedName = symbol.qualifiedName,
                packageName = symbolPackageName(symbol, scopes),
                kind = symbol.kind.toSnapshot(),
                declarationSemantic = symbol.declarationSemantic.toSnapshot(),
                declarationLine = symbol.declarationLine,
                ownerSymbolId = symbol.ownerSymbolId(symbolsByOwnerKey, symbolIds),
                signature = symbol.signature,
                declarationRange =
                    symbol.declarationRange?.let { range ->
                        SourceRangeSnapshot(range.startOffset, range.endOffsetExclusive)
                    },
            )
        }.sortedBy(SymbolSnapshot::id)
}

private fun WorkspaceSymbol.ownerSymbolId(
    symbolsByOwnerKey: Map<SymbolOwnerKey, List<WorkspaceSymbol>>,
    symbolIds: Map<WorkspaceSymbolIdentity, String>,
): String? =
    ownerQualifiedName
        ?.let { ownerName -> ownerKey(ownerName) }
        ?.let(symbolsByOwnerKey::get)
        ?.singleOrNull { owner -> owner.containsDeclaration(this) }
        ?.identity
        ?.let(symbolIds::get)

private fun WorkspaceSymbol.containsDeclaration(child: WorkspaceSymbol): Boolean {
    val ownerRange = declarationRange ?: return true
    val childRange = child.declarationRange ?: return true
    return ownerRange.startOffset <= childRange.startOffset &&
        ownerRange.endOffsetExclusive >= childRange.endOffsetExclusive
}

private fun symbolPackageName(
    symbol: WorkspaceSymbol,
    scopes: List<SnapshotProjectScope>,
): String {
    val scope = scopes.single { it.buildName == symbol.build && it.projectPath == symbol.project }
    val candidates =
        scope.summary.sourceSets
            .singleOrNull { it.name.value == symbol.sourceSet }
            ?.symbols
            .orEmpty()
            .filter { entry ->
                entry.name.value == symbol.name &&
                    entry.lineNumber == symbol.declarationLine &&
                    symbol.projectRelativeFile.normalizedPath().endsWith(entry.filePath.value.normalizedPath())
            }
    return candidates.singleOrNull()?.packageName?.value
        ?: symbol.qualifiedName
            .removeSuffix(".${symbol.name}")
            .takeUnless { it == symbol.qualifiedName || it.isBlank() }
        ?: "_root_"
}

private fun referenceSnapshots(
    references: List<WorkspaceReference>,
    fileIds: Map<FileScopeKey, String>,
    symbolIds: Map<WorkspaceSymbolIdentity, String>,
): List<ReferenceRecord> {
    val counts = mutableMapOf<String, Int>()
    return references
        .mapNotNull { reference ->
            reference.snapshotTarget()?.let { target -> AddressableReference(reference, target) }
        }.sortedBy { addressable -> referenceCanonicalKey(addressable.source, addressable.target) }
        .map { addressable ->
            val reference = addressable.source
            val target = addressable.target
            val key = referenceCanonicalKey(reference, target)
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            ReferenceRecord(
                source = reference,
                snapshot =
                    ReferenceSnapshot(
                        id = stableSnapshotId("reference", key, ordinal.toString()),
                        sourceFileId = fileIds.getValue(reference.fileScopeKey()),
                        sourceSymbolId = reference.sourceIdentity?.let(symbolIds::getValue),
                        line = reference.line,
                        context = reference.context,
                        targetName = target.name,
                        targetQualifiedName = target.qualifiedName,
                        kind = reference.kind.toSnapshot(),
                        evidence = reference.evidence.toSnapshot(),
                        occurrenceRange =
                            reference.occurrenceRange?.let { range ->
                                SourceRangeSnapshot(range.startOffset, range.endOffsetExclusive)
                            },
                    ),
            )
        }.sortedBy { it.snapshot.id }
}

private fun relationshipRecords(
    relationships: List<WorkspaceRelationship>,
    references: List<ReferenceRecord>,
    symbolIds: Map<WorkspaceSymbolIdentity, String>,
): List<RelationshipRecord> {
    val counts = mutableMapOf<String, Int>()
    return relationships
        .sortedBy(::relationshipCanonicalKey)
        .mapNotNull { relationship ->
            val reference =
                references.firstOrNull { it.source == relationship.sourceEvidence }
                    ?: return@mapNotNull null
            if (!reference.snapshot.matches(relationship.target)) return@mapNotNull null
            val key = relationshipCanonicalKey(relationship)
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            RelationshipRecord(
                source = relationship,
                snapshot =
                    RelationshipSnapshot(
                        id = stableSnapshotId("relationship", key, ordinal.toString()),
                        referenceId = reference.snapshot.id,
                        sourceSymbolId = relationship.sourceIdentity?.let(symbolIds::getValue),
                        targetSymbolId = symbolIds.getValue(relationship.targetIdentity),
                        kind = relationship.kind.toSnapshot(),
                        resolutionEvidence = relationship.evidence.toSnapshot(),
                    ),
            )
        }.sortedBy { it.snapshot.id }
}

private fun buildEdgeSnapshots(
    report: WorkspaceReport,
    buildIds: Map<String, String>,
): List<BuildEdgeSnapshot> {
    val counts = mutableMapOf<String, Int>()
    return report.buildEdges
        .sortedWith(compareBy({ it.from }, { it.to }))
        .map { edge ->
            val key = "${edge.from}\u0000${edge.to}"
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            BuildEdgeSnapshot(
                id = stableSnapshotId("build-edge", key, ordinal.toString()),
                sourceBuildId = buildIds.getValue(edge.from),
                targetBuildId = buildIds.getValue(edge.to),
            )
        }.sortedBy(BuildEdgeSnapshot::id)
}

private fun projectDependencySnapshots(scopes: List<SnapshotProjectScope>): List<ProjectDependencySnapshot> {
    val counts = mutableMapOf<String, Int>()
    return scopes
        .flatMap { scope -> scope.summary.dependencies.map { dependency -> scope to dependency } }
        .sortedBy { (scope, dependency) ->
            listOf(
                scope.id,
                dependency.group.value,
                dependency.artifact.value,
                dependency.version.value,
                dependency.scope,
            ).joinToString("\u0000")
        }.map { (scope, dependency) ->
            val key =
                listOf(
                    scope.id,
                    dependency.group.value,
                    dependency.artifact.value,
                    dependency.version.value,
                    dependency.scope,
                ).joinToString("\u0000")
            val ordinal = counts.getOrDefault(key, 0)
            counts[key] = ordinal + 1
            ProjectDependencySnapshot(
                id = stableSnapshotId("project-dependency", key, ordinal.toString()),
                projectId = scope.id,
                group = dependency.group.value,
                artifact = dependency.artifact.value,
                version = dependency.version.value,
                scope = dependency.scope,
            )
        }.sortedBy(ProjectDependencySnapshot::id)
}

private fun sourceFileId(sourceFile: WorkspaceSourceFile): String =
    stableSnapshotId(
        "file",
        sourceFile.build,
        sourceFile.project,
        sourceFile.sourceSet,
        sourceFile.projectRelativeFile.normalizedPath(),
    )

internal fun symbolId(symbol: WorkspaceSymbol): String =
    stableSnapshotId(
        "symbol",
        symbol.build,
        symbol.project,
        symbol.sourceSet,
        symbol.qualifiedName,
        symbol.projectRelativeFile.normalizedPath(),
        symbol.signature,
    )

private fun referenceCanonicalKey(reference: WorkspaceReference): String =
    reference.snapshotTarget()?.let { target -> referenceCanonicalKey(reference, target) }
        ?: referenceCanonicalKey(
            reference = reference,
            target = SnapshotReferenceTarget(reference.targetName, reference.targetQualifiedName),
        )

private fun referenceCanonicalKey(
    reference: WorkspaceReference,
    target: SnapshotReferenceTarget,
): String =
    listOf(
        reference.build,
        reference.project,
        reference.sourceSet,
        reference.projectRelativeFile.normalizedPath(),
        reference.line.toString(),
        reference.occurrenceRange
            ?.startOffset
            ?.toString()
            .orEmpty(),
        reference.occurrenceRange
            ?.endOffsetExclusive
            ?.toString()
            .orEmpty(),
        reference.sourceIdentity?.value.orEmpty(),
        target.name,
        target.qualifiedName.orEmpty(),
        reference.kind.name,
        reference.evidence.name,
        reference.context,
    ).joinToString("\u0000")

private fun relationshipCanonicalKey(relationship: WorkspaceRelationship): String =
    listOf(
        referenceCanonicalKey(relationship.sourceEvidence),
        relationship.sourceIdentity?.value.orEmpty(),
        relationship.targetIdentity.value,
        relationship.kind.name,
        relationship.evidence.name,
    ).joinToString("\u0000")

private fun sourceLanguage(path: String): SourceLanguage =
    when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "kt" -> SourceLanguage.KOTLIN
        "kts" -> SourceLanguage.KOTLIN_SCRIPT
        "java" -> SourceLanguage.JAVA
        "groovy", "gradle" -> SourceLanguage.GROOVY
        "json" -> SourceLanguage.JSON
        "yaml", "yml" -> SourceLanguage.YAML
        "toml" -> SourceLanguage.TOML
        "md", "markdown" -> SourceLanguage.MARKDOWN
        else -> SourceLanguage.OTHER
    }

private fun SymbolDetailKind.toSnapshot(): SymbolKind = SymbolKind.valueOf(name)

private fun zone.clanker.gradle.srcx.model.DeclarationSemantic.toSnapshot(): DeclarationSemantic =
    DeclarationSemantic.valueOf(name)

private fun ReferenceKind.toSnapshot(): SnapshotReferenceKind =
    when (this) {
        ReferenceKind.NAME_REF -> SnapshotReferenceKind.NAME_REFERENCE
        ReferenceKind.TYPE_REF -> SnapshotReferenceKind.TYPE_REFERENCE
        else -> SnapshotReferenceKind.valueOf(name)
    }

private fun WorkspaceRelationshipKind.toSnapshot(): RelationshipKind = RelationshipKind.valueOf(name)

private fun ReferenceEvidence.toSnapshot(): RelationshipEvidence = RelationshipEvidence.valueOf(name)

private fun WorkspaceReference.snapshotTarget(): SnapshotReferenceTarget? {
    val qualifiedName = targetQualifiedName?.takeUnless(String::isBlank)
    val name =
        targetName.takeUnless(String::isBlank)
            ?: qualifiedName
                ?.substringAfterLast('.')
                ?.takeUnless(String::isBlank)
            ?: return null
    return SnapshotReferenceTarget(name, qualifiedName)
}

private fun ReferenceSnapshot.matches(target: WorkspaceSymbol): Boolean =
    target.name.substringAfterLast('.') == targetName &&
        (targetQualifiedName == null || target.qualifiedName == targetQualifiedName)

private fun String.normalizedPath(): String = replace('\\', '/')

private fun WorkspaceSourceFile.scopeKey(): FileScopeKey =
    FileScopeKey(build, project, sourceSet, projectRelativeFile.normalizedPath())

private fun WorkspaceSymbol.fileScopeKey(): FileScopeKey =
    FileScopeKey(build, project, sourceSet, projectRelativeFile.normalizedPath())

private fun WorkspaceReference.fileScopeKey(): FileScopeKey =
    FileScopeKey(build, project, sourceSet, projectRelativeFile.normalizedPath())

internal data class SnapshotProjectScope(
    val id: String,
    val buildName: String,
    val buildId: String,
    val summary: ProjectSummary,
) {
    val key: ProjectScopeKey get() = ProjectScopeKey(buildName, projectPath)
    val projectPath: String get() = summary.projectPath.value
}

internal data class SnapshotMappingContext(
    val report: WorkspaceReport,
    val buildIds: Map<String, String>,
    val projectScopes: List<SnapshotProjectScope>,
    val projectIds: Map<ProjectScopeKey, String>,
    val files: List<SourceFileSnapshot>,
    val fileIds: Map<FileScopeKey, String>,
    val symbols: List<SymbolSnapshot>,
    val symbolIds: Map<WorkspaceSymbolIdentity, String>,
    val relationshipRecords: List<RelationshipRecord>,
) {
    val filesById: Map<String, SourceFileSnapshot> = files.associateBy(SourceFileSnapshot::id)
    val symbolsById: Map<String, SymbolSnapshot> = symbols.associateBy(SymbolSnapshot::id)
}

internal data class ProjectScopeKey(
    val build: String,
    val project: String,
)

private data class SourceSetScopeKey(
    val buildId: String,
    val project: String,
    val sourceSet: String,
)

private data class SymbolOwnerKey(
    val build: String,
    val project: String,
    val sourceSet: String,
    val qualifiedName: String,
    val projectRelativeFile: String,
)

private fun WorkspaceSymbol.ownerKey(qualifiedName: String = this.qualifiedName): SymbolOwnerKey =
    SymbolOwnerKey(
        build = build,
        project = project,
        sourceSet = sourceSet,
        qualifiedName = qualifiedName,
        projectRelativeFile = projectRelativeFile.normalizedPath(),
    )

internal data class FileScopeKey(
    val build: String,
    val project: String,
    val sourceSet: String,
    val path: String,
)

private data class ReferenceRecord(
    val source: WorkspaceReference,
    val snapshot: ReferenceSnapshot,
)

private data class AddressableReference(
    val source: WorkspaceReference,
    val target: SnapshotReferenceTarget,
)

private data class SnapshotReferenceTarget(
    val name: String,
    val qualifiedName: String?,
)

internal data class RelationshipRecord(
    val source: WorkspaceRelationship,
    val snapshot: RelationshipSnapshot,
)
