package zone.clanker.docx.index

import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
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
import zone.clanker.report.model.SourceRangeSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSiteManifest
import zone.clanker.report.model.WorkspaceSnapshot
import zone.clanker.report.model.WorkspaceSummaryShard
import java.nio.file.Files
import java.nio.file.Path

internal object WorkspaceIndexFixture {
    const val INDEX_WORKSPACE_ID = "registered:fixture"
    const val SOURCE_WORKSPACE_ID = "workspace:fixture"
    const val BUILD_ID = "build:fixture"
    const val ALPHA_PROJECT_ID = "project:alpha"
    const val BETA_PROJECT_ID = "project:beta"
    const val ALPHA_SOURCE_SET_ID = "source-set:alpha:main"
    const val BETA_SOURCE_SET_ID = "source-set:beta:main"
    const val ALPHA_CLASS_ID = "symbol:alpha:class"
    const val ALPHA_FUNCTION_ID = "symbol:alpha:function"

    fun createSite(): Path = Files.createTempDirectory("docx-index-site-")

    fun createDatabase(): Path {
        val directory = Files.createTempDirectory("docx-index-database-")
        return directory.resolve("workspace-index.sqlite")
    }

    fun writeGeneration(
        root: Path,
        generationId: String,
        alphaClassName: String = "AlphaService",
        duplicateBetaSymbolId: Boolean = false,
    ) {
        val references = projectReferences()
        val summary = workspaceSummary(references)
        val manifest =
            WorkspaceSiteManifest(
                generationId = generationId,
                snapshotSchemaVersion = WorkspaceSnapshot.CURRENT_SCHEMA_VERSION,
                workspaceFile = "data/workspace.json",
                dashboardFile = "data/dashboard.json",
                projectShards = references,
                assetFiles = emptyList(),
            )
        Files.createDirectories(root.resolve("data/shards"))
        Files.writeString(root.resolve("data/manifest.json"), WorkspaceSiteJson.encodeManifest(manifest))
        Files.writeString(root.resolve(manifest.workspaceFile), WorkspaceSiteJson.encodeWorkspace(summary))
        Files.writeString(
            root.resolve(references.first().file),
            WorkspaceSiteJson.encodeProject(alphaProject(alphaClassName)),
        )
        Files.writeString(
            root.resolve(references.last().file),
            WorkspaceSiteJson.encodeProject(betaProject(duplicateBetaSymbolId)),
        )
    }

    private fun projectReferences(): List<ProjectShardReference> =
        listOf(
            ProjectShardReference(ALPHA_PROJECT_ID, "data/shards/alpha.json"),
            ProjectShardReference(BETA_PROJECT_ID, "data/shards/beta.json"),
        )

    private fun workspaceSummary(references: List<ProjectShardReference>): WorkspaceSummaryShard =
        WorkspaceSummaryShard(
            workspace = WorkspaceIdentity(SOURCE_WORKSPACE_ID, "Index fixture"),
            builds = listOf(BuildSnapshot(BUILD_ID, "fixture", BuildKind.ROOT, ".")),
            projects =
                listOf(
                    ProjectSnapshot(ALPHA_PROJECT_ID, BUILD_ID, ":alpha", "alpha/build.gradle.kts"),
                    ProjectSnapshot(BETA_PROJECT_ID, BUILD_ID, ":beta", "beta/build.gradle.kts"),
                ),
            projectShards = references,
        )

    private fun alphaProject(alphaClassName: String): ProjectGraphShard {
        val sourceSet = SourceSetSnapshot(ALPHA_SOURCE_SET_ID, ALPHA_PROJECT_ID, "main")
        val file = alphaSourceFile(sourceSet, alphaClassName)
        val alphaClass =
            symbol(
                id = ALPHA_CLASS_ID,
                fileId = file.id,
                name = alphaClassName,
                qualifiedName = "example.alpha.$alphaClassName",
                kind = SymbolKind.CLASS,
            ).copy(
                signature = "class $alphaClassName",
                declarationRange = SourceRangeSnapshot(0, requireNotNull(file.content).length),
            )
        val functionStart = requireNotNull(file.content).indexOf("fun runAlpha")
        val function =
            symbol(
                id = ALPHA_FUNCTION_ID,
                fileId = file.id,
                name = "runAlpha",
                qualifiedName = "example.alpha.runAlpha",
                kind = SymbolKind.FUNCTION,
            ).copy(
                ownerSymbolId = alphaClass.id,
                signature = "fun runAlpha(): $alphaClassName",
                declarationRange =
                    SourceRangeSnapshot(
                        functionStart,
                        requireNotNull(file.content).indexOf('\n', functionStart),
                    ),
            )
        val evidence = alphaEvidence(file, function, alphaClass)
        val laterReference =
            evidence.reference.copy(
                id = "reference:alpha:call:later",
                line = 3,
            )
        val laterRelationship =
            evidence.relationship.copy(
                id = "relationship:alpha:call:later",
                referenceId = laterReference.id,
            )
        return ProjectGraphShard(
            projectId = ALPHA_PROJECT_ID,
            sourceSets = listOf(sourceSet),
            files = listOf(file),
            symbols = listOf(alphaClass, function),
            references = listOf(evidence.reference, laterReference),
            relationships = listOf(evidence.relationship, laterRelationship),
            findings = listOf(evidence.finding),
        )
    }

    private fun alphaSourceFile(
        sourceSet: SourceSetSnapshot,
        alphaClassName: String,
    ): SourceFileSnapshot =
        SourceFileSnapshot(
            id = "file:alpha:main",
            sourceSetId = sourceSet.id,
            projectRelativePath = "src/main/kotlin/example/alpha/Alpha.kt",
            language = SourceLanguage.KOTLIN,
            content = "class $alphaClassName {\nfun runAlpha() = $alphaClassName()\n}\n",
        )

    private fun alphaEvidence(
        file: SourceFileSnapshot,
        function: SymbolSnapshot,
        alphaClass: SymbolSnapshot,
    ): AlphaEvidence {
        val content = requireNotNull(file.content)
        val occurrenceStart = content.lastIndexOf(alphaClass.name)
        val reference =
            ReferenceSnapshot(
                id = "reference:alpha:call",
                sourceFileId = file.id,
                sourceSymbolId = function.id,
                line = 2,
                context = "runAlpha() = ${alphaClass.name}()",
                targetName = alphaClass.name,
                targetQualifiedName = alphaClass.qualifiedName,
                kind = ReferenceKind.CALL,
                evidence = RelationshipEvidence.DIRECT,
                occurrenceRange = SourceRangeSnapshot(occurrenceStart, occurrenceStart + alphaClass.name.length),
            )
        return AlphaEvidence(
            reference = reference,
            relationship =
                RelationshipSnapshot(
                    id = "relationship:alpha:call",
                    referenceId = reference.id,
                    sourceSymbolId = function.id,
                    targetSymbolId = alphaClass.id,
                    kind = RelationshipKind.CALL,
                    resolutionEvidence = RelationshipEvidence.DIRECT,
                ),
            finding =
                FindingSnapshot(
                    id = "finding:alpha:warning",
                    severity = FindingSeverity.WARNING,
                    message = "Alpha has one warning",
                    suggestion = "Review Alpha",
                    fileId = file.id,
                    filePath = file.projectRelativePath,
                    line = 1,
                    symbolIds = listOf(alphaClass.id),
                ),
        )
    }

    private fun betaProject(duplicateSymbolId: Boolean): ProjectGraphShard {
        val sourceSet = SourceSetSnapshot(BETA_SOURCE_SET_ID, BETA_PROJECT_ID, "main")
        val file =
            SourceFileSnapshot(
                id = "file:beta:main",
                sourceSetId = sourceSet.id,
                projectRelativePath = "src/main/kotlin/example/beta/Beta.kt",
                language = SourceLanguage.KOTLIN,
                content = "interface BetaPort\n",
            )
        val betaSymbol =
            symbol(
                id = if (duplicateSymbolId) ALPHA_CLASS_ID else "symbol:beta:interface",
                fileId = file.id,
                name = "BetaPort",
                qualifiedName = "example.beta.BetaPort",
                kind = SymbolKind.INTERFACE,
            )
        return ProjectGraphShard(
            projectId = BETA_PROJECT_ID,
            sourceSets = listOf(sourceSet),
            files = listOf(file),
            symbols = listOf(betaSymbol),
            relationships = emptyList(),
            findings =
                listOf(
                    FindingSnapshot(
                        id = "finding:beta:info",
                        severity = FindingSeverity.INFO,
                        message = "Beta is informational",
                        suggestion = "Keep Beta documented",
                        fileId = file.id,
                        filePath = file.projectRelativePath,
                        line = 1,
                        symbolIds = listOf(betaSymbol.id),
                    ),
                ),
        )
    }

    private fun symbol(
        id: String,
        fileId: String,
        name: String,
        qualifiedName: String,
        kind: SymbolKind,
    ): SymbolSnapshot =
        SymbolSnapshot(
            id = id,
            fileId = fileId,
            name = name,
            qualifiedName = qualifiedName,
            packageName = qualifiedName.substringBeforeLast('.'),
            kind = kind,
            declarationSemantic = kind.declarationSemantic,
            declarationLine = if (kind == SymbolKind.FUNCTION) 2 else 1,
        )

    private val SymbolKind.declarationSemantic: DeclarationSemantic
        get() =
            when (this) {
                SymbolKind.CLASS -> DeclarationSemantic.CONCRETE_CLASS
                SymbolKind.INTERFACE -> DeclarationSemantic.INTERFACE
                else -> DeclarationSemantic.OTHER
            }

    private data class AlphaEvidence(
        val reference: ReferenceSnapshot,
        val relationship: RelationshipSnapshot,
        val finding: FindingSnapshot,
    )
}
