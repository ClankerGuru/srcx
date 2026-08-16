package zone.clanker.docx.service.index

import zone.clanker.docx.service.temporaryDirectory
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

internal object ServiceIndexFixture {
    const val WORKSPACE_ID: String = "service-index-fixture"
    const val GENERATION_ID: String = "generation-indexed"
    const val BUILD_ID: String = "build:fixture"
    const val PROJECT_ID: String = "project:fixture"
    const val SOURCE_SET_ID: String = "source-set:fixture:main"
    const val SYMBOL_ID: String = "symbol:fixture:class"
    const val START_SYMBOL_ID: String = "symbol:fixture:start"

    fun createSite(): Path = temporaryDirectory("docx-service-index-site-")

    fun createDatabase(): Path = temporaryDirectory("docx-service-index-database-").resolve("workspace-index.sqlite")

    fun writeGeneration(root: Path) {
        val shard = ProjectShardReference(PROJECT_ID, "data/shards/fixture.json")
        val manifest =
            WorkspaceSiteManifest(
                generationId = GENERATION_ID,
                snapshotSchemaVersion = WorkspaceSnapshot.CURRENT_SCHEMA_VERSION,
                workspaceFile = "data/workspace.json",
                dashboardFile = "data/dashboard.json",
                projectShards = listOf(shard),
                assetFiles = emptyList(),
            )
        Files.createDirectories(root.resolve("data/shards"))
        Files.writeString(root.resolve("data/manifest.json"), WorkspaceSiteJson.encodeManifest(manifest))
        Files.writeString(root.resolve(manifest.workspaceFile), WorkspaceSiteJson.encodeWorkspace(summary(shard)))
        Files.writeString(root.resolve(shard.file), WorkspaceSiteJson.encodeProject(project()))
    }

    private fun summary(shard: ProjectShardReference): WorkspaceSummaryShard =
        WorkspaceSummaryShard(
            workspace = WorkspaceIdentity("source-workspace", "Service index fixture"),
            builds = listOf(BuildSnapshot(BUILD_ID, "fixture", BuildKind.ROOT, ".")),
            projects = listOf(ProjectSnapshot(PROJECT_ID, BUILD_ID, ":fixture", "fixture/build.gradle.kts")),
            projectShards = listOf(shard),
        )

    private fun project(): ProjectGraphShard {
        val sourceSet = SourceSetSnapshot(SOURCE_SET_ID, PROJECT_ID, "main")
        val file = sourceFile(sourceSet)
        val symbols = projectSymbols(file)
        val reference = callReference(file, symbols)
        return ProjectGraphShard(
            projectId = PROJECT_ID,
            sourceSets = listOf(sourceSet),
            files = listOf(file),
            symbols = listOf(symbols.service, symbols.start),
            references = listOf(reference),
            relationships = listOf(callRelationship(reference, symbols)),
            findings = listOf(finding(file, symbols.service)),
        )
    }

    private fun sourceFile(sourceSet: SourceSetSnapshot): SourceFileSnapshot =
        SourceFileSnapshot(
            id = "file:fixture:main",
            sourceSetId = sourceSet.id,
            projectRelativePath = "src/main/kotlin/example/Service.kt",
            language = SourceLanguage.KOTLIN,
            content = "class Service {\nfun start() = Service()\n}\n",
        )

    private fun projectSymbols(file: SourceFileSnapshot): FixtureProjectSymbols {
        val content = requireNotNull(file.content)
        val service =
            symbol(
                file.id,
                FixtureSymbolDefinition(SYMBOL_ID, "Service", "example.Service", SymbolKind.CLASS, 1),
            ).copy(
                signature = "class Service",
                declarationRange = SourceRangeSnapshot(0, content.length),
            )
        val startOffset = content.indexOf("fun start")
        val start =
            symbol(
                file.id,
                FixtureSymbolDefinition(START_SYMBOL_ID, "start", "example.start", SymbolKind.FUNCTION, 2),
            ).copy(
                ownerSymbolId = service.id,
                signature = "fun start(): Service",
                declarationRange = SourceRangeSnapshot(startOffset, content.indexOf('\n', startOffset)),
            )
        return FixtureProjectSymbols(service, start)
    }

    private fun callReference(
        file: SourceFileSnapshot,
        symbols: FixtureProjectSymbols,
    ): ReferenceSnapshot =
        ReferenceSnapshot(
            id = "reference:fixture:call",
            sourceFileId = file.id,
            sourceSymbolId = symbols.start.id,
            line = 2,
            context = "start() = Service()",
            targetName = symbols.service.name,
            targetQualifiedName = symbols.service.qualifiedName,
            kind = ReferenceKind.CALL,
            evidence = RelationshipEvidence.DIRECT,
            occurrenceRange =
                requireNotNull(file.content).lastIndexOf("Service").let { start ->
                    SourceRangeSnapshot(start, start + "Service".length)
                },
        )

    private fun callRelationship(
        reference: ReferenceSnapshot,
        symbols: FixtureProjectSymbols,
    ): RelationshipSnapshot =
        RelationshipSnapshot(
            id = "relationship:fixture:call",
            referenceId = reference.id,
            sourceSymbolId = symbols.start.id,
            targetSymbolId = symbols.service.id,
            kind = RelationshipKind.CALL,
            resolutionEvidence = RelationshipEvidence.DIRECT,
        )

    private fun finding(
        file: SourceFileSnapshot,
        service: SymbolSnapshot,
    ): FindingSnapshot =
        FindingSnapshot(
            id = "finding:fixture:warning",
            severity = FindingSeverity.WARNING,
            message = "Fixture warning",
            suggestion = "Review the fixture",
            fileId = file.id,
            filePath = file.projectRelativePath,
            line = 1,
            symbolIds = listOf(service.id),
        )

    private fun symbol(
        fileId: String,
        definition: FixtureSymbolDefinition,
    ): SymbolSnapshot =
        SymbolSnapshot(
            id = definition.id,
            fileId = fileId,
            name = definition.name,
            qualifiedName = definition.qualifiedName,
            packageName = definition.qualifiedName.substringBeforeLast('.'),
            kind = definition.kind,
            declarationSemantic =
                if (definition.kind == SymbolKind.CLASS) {
                    DeclarationSemantic.CONCRETE_CLASS
                } else {
                    DeclarationSemantic.OTHER
                },
            declarationLine = definition.line,
        )

    private data class FixtureSymbolDefinition(
        val id: String,
        val name: String,
        val qualifiedName: String,
        val kind: SymbolKind,
        val line: Int,
    )

    private data class FixtureProjectSymbols(
        val service: SymbolSnapshot,
        val start: SymbolSnapshot,
    )
}
