package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasNode
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
import zone.clanker.report.model.WorkspaceSummaryShard

internal object AtlasTypedFilterFixture {
    const val ALPHA_FILE_ID = "file:typed:alpha"
    const val BETA_FILE_ID = "file:typed:beta"
    const val GAMMA_FILE_ID = "file:typed:gamma"
    const val ALPHA_SYMBOL_ID = "symbol:typed:alpha"
    const val BETA_SYMBOL_ID = "symbol:typed:beta"
    const val GAMMA_SYMBOL_ID = "symbol:typed:gamma"

    val summary =
        WorkspaceSummaryShard(
            workspace = WorkspaceIdentity("workspace:typed", "Typed filter workspace"),
            builds = listOf(BuildSnapshot(BUILD_ID, "nebula-build", BuildKind.ROOT, ".")),
            projects =
                listOf(
                    ProjectSnapshot(
                        id = PROJECT_ID,
                        buildId = BUILD_ID,
                        path = ":orion",
                        buildFile = "orion.gradle.kts",
                    ),
                ),
            projectShards = listOf(ProjectShardReference(PROJECT_ID, "data/shards/orion.json")),
        )

    val project =
        ProjectGraphShard(
            projectId = PROJECT_ID,
            sourceSets = listOf(SourceSetSnapshot(SOURCE_SET_ID, PROJECT_ID, "main")),
            files = files(),
            symbols = symbols(),
            references = references(),
            relationships = relationships(),
            findings = findings(),
            cycles =
                listOf(
                    CycleSnapshot(
                        id = "cycle:typed:alpha-beta",
                        symbolIds = listOf(ALPHA_SYMBOL_ID, BETA_SYMBOL_ID, ALPHA_SYMBOL_ID),
                        relationshipIds = listOf(ALPHA_BETA_ONE_RELATIONSHIP_ID, BETA_ALPHA_RELATIONSHIP_ID),
                    ),
                ),
        )

    private fun files(): List<SourceFileSnapshot> =
        listOf(
            sourceFile(ALPHA_FILE_ID, "src/main/kotlin/com/acme/alpha/AlphaService.kt"),
            sourceFile(BETA_FILE_ID, "src/main/java/com/acme/beta/BetaPort.java", SourceLanguage.JAVA),
            sourceFile(GAMMA_FILE_ID, "src/main/kotlin/com/acme/gamma/OrbitFunctions.kt"),
        )

    private fun symbols(): List<SymbolSnapshot> =
        listOf(
            FixtureSymbolSpec(
                id = ALPHA_SYMBOL_ID,
                fileId = ALPHA_FILE_ID,
                name = "AlphaService",
                packageName = "com.acme.alpha",
                kind = SymbolKind.CLASS,
                semantic = DeclarationSemantic.CONCRETE_CLASS,
            ).snapshot(),
            FixtureSymbolSpec(
                id = BETA_SYMBOL_ID,
                fileId = BETA_FILE_ID,
                name = "BetaPort",
                packageName = "com.acme.beta",
                kind = SymbolKind.INTERFACE,
                semantic = DeclarationSemantic.INTERFACE,
            ).snapshot(),
            FixtureSymbolSpec(
                id = GAMMA_SYMBOL_ID,
                fileId = GAMMA_FILE_ID,
                name = "calculateOrbit",
                packageName = "com.acme.gamma",
                kind = SymbolKind.FUNCTION,
                semantic = DeclarationSemantic.OTHER,
            ).snapshot(),
        )

    private fun references(): List<ReferenceSnapshot> =
        listOf(
            reference(
                id = "reference:typed:alpha-beta-one",
                sourceFileId = ALPHA_FILE_ID,
                sourceSymbolId = ALPHA_SYMBOL_ID,
                targetName = "BetaPort",
                targetQualifiedName = "com.acme.beta.BetaPort",
            ),
            reference(
                id = "reference:typed:alpha-beta-two",
                sourceFileId = ALPHA_FILE_ID,
                sourceSymbolId = ALPHA_SYMBOL_ID,
                targetName = "BetaPort",
                targetQualifiedName = "com.acme.beta.BetaPort",
            ),
            reference(
                id = "reference:typed:beta-alpha",
                sourceFileId = BETA_FILE_ID,
                sourceSymbolId = BETA_SYMBOL_ID,
                targetName = "AlphaService",
                targetQualifiedName = "com.acme.alpha.AlphaService",
            ),
            reference(
                id = "reference:typed:gamma-alpha",
                sourceFileId = GAMMA_FILE_ID,
                sourceSymbolId = GAMMA_SYMBOL_ID,
                targetName = "AlphaService",
                targetQualifiedName = "com.acme.alpha.AlphaService",
            ),
        )

    private fun relationships(): List<RelationshipSnapshot> =
        listOf(
            relationship(
                ALPHA_BETA_ONE_RELATIONSHIP_ID,
                "reference:typed:alpha-beta-one",
                ALPHA_SYMBOL_ID,
                BETA_SYMBOL_ID,
            ),
            relationship(
                "relationship:typed:alpha-beta-two",
                "reference:typed:alpha-beta-two",
                ALPHA_SYMBOL_ID,
                BETA_SYMBOL_ID,
            ),
            relationship(
                BETA_ALPHA_RELATIONSHIP_ID,
                "reference:typed:beta-alpha",
                BETA_SYMBOL_ID,
                ALPHA_SYMBOL_ID,
            ),
            relationship(
                "relationship:typed:gamma-alpha",
                "reference:typed:gamma-alpha",
                GAMMA_SYMBOL_ID,
                ALPHA_SYMBOL_ID,
            ),
        )

    private fun findings(): List<FindingSnapshot> =
        listOf(
            finding("alpha", ALPHA_FILE_ID, "src/main/kotlin/com/acme/alpha/AlphaService.kt", ALPHA_SYMBOL_ID),
            finding("beta", BETA_FILE_ID, "src/main/java/com/acme/beta/BetaPort.java", BETA_SYMBOL_ID),
            finding("gamma", GAMMA_FILE_ID, "src/main/kotlin/com/acme/gamma/OrbitFunctions.kt", GAMMA_SYMBOL_ID),
        )

    private fun finding(
        suffix: String,
        fileId: String,
        filePath: String,
        symbolId: String,
    ): FindingSnapshot =
        FindingSnapshot(
            id = "finding:typed:$suffix",
            severity = FindingSeverity.WARNING,
            message = "Typed declaration evidence",
            suggestion = "Inspect the declaration graph.",
            fileId = fileId,
            filePath = filePath,
            line = 3,
            symbolIds = listOf(symbolId),
        )

    private fun sourceFile(
        id: String,
        path: String,
        language: SourceLanguage = SourceLanguage.KOTLIN,
    ): SourceFileSnapshot =
        SourceFileSnapshot(
            id = id,
            sourceSetId = SOURCE_SET_ID,
            projectRelativePath = path,
            language = language,
            content = "package fixture\n\ndeclaration\n",
        )

    private fun FixtureSymbolSpec.snapshot(): SymbolSnapshot =
        SymbolSnapshot(
            id = id,
            fileId = fileId,
            name = name,
            qualifiedName = "$packageName.$name",
            packageName = packageName,
            kind = kind,
            declarationSemantic = semantic,
            declarationLine = 3,
        )

    private data class FixtureSymbolSpec(
        val id: String,
        val fileId: String,
        val name: String,
        val packageName: String,
        val kind: SymbolKind,
        val semantic: DeclarationSemantic,
    )

    private fun reference(
        id: String,
        sourceFileId: String,
        sourceSymbolId: String,
        targetName: String,
        targetQualifiedName: String,
    ): ReferenceSnapshot =
        ReferenceSnapshot(
            id = id,
            sourceFileId = sourceFileId,
            sourceSymbolId = sourceSymbolId,
            line = 3,
            context = "$sourceSymbolId references $targetName",
            targetName = targetName,
            targetQualifiedName = targetQualifiedName,
            kind = ReferenceKind.CALL,
            evidence = RelationshipEvidence.DIRECT,
        )

    private fun relationship(
        id: String,
        referenceId: String,
        sourceSymbolId: String,
        targetSymbolId: String,
    ): RelationshipSnapshot =
        RelationshipSnapshot(
            id = id,
            referenceId = referenceId,
            sourceSymbolId = sourceSymbolId,
            targetSymbolId = targetSymbolId,
            kind = RelationshipKind.CALL,
            resolutionEvidence = RelationshipEvidence.DIRECT,
        )

    private const val BUILD_ID = "build:typed"
    private const val PROJECT_ID = "project:typed:orion"
    private const val SOURCE_SET_ID = "source-set:typed:main"
    private const val ALPHA_BETA_ONE_RELATIONSHIP_ID = "relationship:typed:alpha-beta-one"
    private const val BETA_ALPHA_RELATIONSHIP_ID = "relationship:typed:beta-alpha"
}

internal fun AtlasFrame.primaryNodeIds(): List<String> = nodes.filter(AtlasNode::primary).map(AtlasNode::id)
