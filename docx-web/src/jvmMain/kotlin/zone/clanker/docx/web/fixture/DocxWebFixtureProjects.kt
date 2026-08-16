package zone.clanker.docx.web.fixture

import zone.clanker.report.model.DeclarationSemantic
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectGraphShard
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

internal val fixtureWorkspaceIdentity = WorkspaceIdentity("workspace:fixture", "Fixture Workspace")

internal fun fixtureAppProject(): ProjectGraphShard {
    val sourceSet = SourceSetSnapshot("source-set:fixture:main", "project:fixture:app", "main")
    val consumerFile =
        SourceFileSnapshot(
            id = "file:fixture:consumer",
            sourceSetId = sourceSet.id,
            projectRelativePath = "src/main/kotlin/fixture/Consumer.kt",
            language = SourceLanguage.KOTLIN,
            content = "package fixture\n\nclass Consumer { val target = Target() }\n",
        )
    val targetFile =
        SourceFileSnapshot(
            id = "file:fixture:target",
            sourceSetId = sourceSet.id,
            projectRelativePath = "src/main/kotlin/fixture/Target.kt",
            language = SourceLanguage.KOTLIN,
            content = "package fixture\n\nclass Target\n",
        )
    val consumer = fixtureConsumerSymbol(consumerFile.id)
    val target = fixtureTargetSymbol(targetFile.id)
    val reference = fixtureConstructorReference(consumerFile, consumer, target)
    return ProjectGraphShard(
        projectId = "project:fixture:app",
        sourceSets = listOf(sourceSet),
        files = listOf(consumerFile, targetFile),
        symbols = listOf(consumer, target),
        references = listOf(reference),
        relationships =
            listOf(
                RelationshipSnapshot(
                    id = "relationship:fixture:constructor",
                    referenceId = reference.id,
                    sourceSymbolId = consumer.id,
                    targetSymbolId = target.id,
                    kind = RelationshipKind.CONSTRUCTOR,
                    resolutionEvidence = RelationshipEvidence.DIRECT,
                ),
            ),
        findings = listOf(fixtureBoundaryFinding(consumerFile, consumer, target)),
    )
}

internal fun fixtureLibraryProject(): ProjectGraphShard {
    val sourceSet = SourceSetSnapshot("source-set:fixture:library:main", "project:fixture:library", "main")
    val libraryFile =
        SourceFileSnapshot(
            id = "file:fixture:library-port",
            sourceSetId = sourceSet.id,
            projectRelativePath = "src/main/kotlin/fixture/library/LibraryPort.kt",
            language = SourceLanguage.KOTLIN,
            content = "package fixture.library\n\ninterface LibraryPort\n",
        )
    val librarySymbol =
        SymbolSnapshot(
            id = "symbol:fixture:library-port",
            fileId = libraryFile.id,
            name = "LibraryPort",
            qualifiedName = "fixture.library.LibraryPort",
            packageName = "fixture.library",
            kind = SymbolKind.INTERFACE,
            declarationSemantic = DeclarationSemantic.INTERFACE,
            declarationLine = FIXTURE_DECLARATION_LINE,
        )
    return ProjectGraphShard(
        projectId = "project:fixture:library",
        sourceSets = listOf(sourceSet),
        files = listOf(libraryFile),
        symbols = listOf(librarySymbol),
        relationships = emptyList(),
    )
}

private fun fixtureConsumerSymbol(fileId: String): SymbolSnapshot =
    SymbolSnapshot(
        id = "symbol:fixture:consumer",
        fileId = fileId,
        name = "Consumer",
        qualifiedName = "fixture.Consumer",
        packageName = "fixture",
        kind = SymbolKind.CLASS,
        declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
        declarationLine = FIXTURE_DECLARATION_LINE,
    )

private fun fixtureTargetSymbol(fileId: String): SymbolSnapshot =
    SymbolSnapshot(
        id = "symbol:fixture:target",
        fileId = fileId,
        name = "Target",
        qualifiedName = "fixture.Target",
        packageName = "fixture",
        kind = SymbolKind.CLASS,
        declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
        declarationLine = FIXTURE_DECLARATION_LINE,
    )

private fun fixtureConstructorReference(
    consumerFile: SourceFileSnapshot,
    consumer: SymbolSnapshot,
    target: SymbolSnapshot,
): ReferenceSnapshot =
    ReferenceSnapshot(
        id = "reference:fixture:constructor",
        sourceFileId = consumerFile.id,
        sourceSymbolId = consumer.id,
        line = FIXTURE_DECLARATION_LINE,
        context = "class Consumer { val target = Target() }",
        targetName = target.name,
        targetQualifiedName = target.qualifiedName,
        kind = ReferenceKind.CONSTRUCTOR,
        evidence = RelationshipEvidence.DIRECT,
    )

private fun fixtureBoundaryFinding(
    consumerFile: SourceFileSnapshot,
    consumer: SymbolSnapshot,
    target: SymbolSnapshot,
): FindingSnapshot =
    FindingSnapshot(
        id = "finding:fixture:boundary",
        severity = FindingSeverity.WARNING,
        message = "Consumer owns a direct construction relationship",
        suggestion = "Review whether the boundary belongs in this project.",
        fileId = consumerFile.id,
        filePath = consumerFile.projectRelativePath,
        line = FIXTURE_DECLARATION_LINE,
        symbolIds = listOf(consumer.id, target.id),
    )

private const val FIXTURE_DECLARATION_LINE = 3
