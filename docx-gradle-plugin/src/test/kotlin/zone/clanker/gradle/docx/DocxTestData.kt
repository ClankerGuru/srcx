package zone.clanker.gradle.docx

import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.DeclarationSemantic
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
import zone.clanker.report.model.WorkspaceSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Suppress("LongMethod")
internal fun workspaceFixture(name: String = "fixture"): WorkspaceSnapshot =
    WorkspaceSnapshot(
        workspace = WorkspaceIdentity(id = "workspace:a", name = name),
        builds = listOf(BuildSnapshot("build:a", name, BuildKind.ROOT, ".")),
        projects = listOf(ProjectSnapshot("project:a", "build:a", ":", "build.gradle.kts")),
        sourceSets = listOf(SourceSetSnapshot("source-set:a", "project:a", "main")),
        files =
            listOf(
                SourceFileSnapshot(
                    id = "file:a",
                    sourceSetId = "source-set:a",
                    projectRelativePath = "src/main/kotlin/Consumer.kt",
                    language = SourceLanguage.KOTLIN,
                    content = "class Consumer(val target: Target)\n",
                ),
                SourceFileSnapshot(
                    id = "file:b",
                    sourceSetId = "source-set:a",
                    projectRelativePath = "src/main/kotlin/Target.kt",
                    language = SourceLanguage.KOTLIN,
                    content = "class Target\n",
                ),
            ),
        symbols =
            listOf(
                SymbolSnapshot(
                    id = "symbol:a",
                    fileId = "file:a",
                    name = "Consumer",
                    qualifiedName = "fixture.Consumer",
                    packageName = "fixture",
                    kind = SymbolKind.CLASS,
                    declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
                    declarationLine = 1,
                ),
                SymbolSnapshot(
                    id = "symbol:b",
                    fileId = "file:b",
                    name = "Target",
                    qualifiedName = "fixture.Target",
                    packageName = "fixture",
                    kind = SymbolKind.CLASS,
                    declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
                    declarationLine = 1,
                ),
            ),
        references =
            listOf(
                ReferenceSnapshot(
                    id = "reference:a",
                    sourceFileId = "file:a",
                    sourceSymbolId = "symbol:a",
                    line = 1,
                    context = "Target",
                    targetName = "Target",
                    targetQualifiedName = "fixture.Target",
                    kind = ReferenceKind.CONSTRUCTOR,
                    evidence = RelationshipEvidence.DIRECT,
                ),
            ),
        relationships =
            listOf(
                RelationshipSnapshot(
                    id = "relationship:a",
                    referenceId = "reference:a",
                    sourceSymbolId = "symbol:a",
                    targetSymbolId = "symbol:b",
                    kind = RelationshipKind.CONSTRUCTOR,
                    resolutionEvidence = RelationshipEvidence.DIRECT,
                ),
            ),
    )

@Suppress("LongMethod")
internal fun crossProjectWorkspaceFixture(): WorkspaceSnapshot {
    val fixture = workspaceFixture()
    val project = ProjectSnapshot("project:b", "build:a", ":target", "target/build.gradle.kts")
    val sourceSet = SourceSetSnapshot("source-set:b", project.id, "main")
    val file =
        SourceFileSnapshot(
            id = "file:c",
            sourceSetId = sourceSet.id,
            projectRelativePath = "src/main/kotlin/External.kt",
            language = SourceLanguage.KOTLIN,
            content = "class External\n",
        )
    val symbol =
        SymbolSnapshot(
            id = "symbol:c",
            fileId = file.id,
            name = "External",
            qualifiedName = "fixture.External",
            packageName = "fixture",
            kind = SymbolKind.CLASS,
            declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
            declarationLine = 1,
        )
    val reference =
        ReferenceSnapshot(
            id = "reference:b",
            sourceFileId = "file:a",
            line = 1,
            context = "import fixture.External",
            targetName = "External",
            targetQualifiedName = "fixture.External",
            kind = ReferenceKind.IMPORT,
            evidence = RelationshipEvidence.DIRECT,
        )
    val relationship =
        RelationshipSnapshot(
            id = "relationship:b",
            referenceId = reference.id,
            targetSymbolId = symbol.id,
            kind = RelationshipKind.IMPORT,
            resolutionEvidence = RelationshipEvidence.DIRECT,
        )
    return fixture.copy(
        projects = fixture.projects + project,
        sourceSets = fixture.sourceSets + sourceSet,
        files = fixture.files + file,
        symbols = fixture.symbols + symbol,
        references = fixture.references + reference,
        relationships = fixture.relationships + relationship,
    )
}

internal fun crossProjectCycleWorkspaceFixture(): WorkspaceSnapshot {
    val fixture = crossProjectWorkspaceFixture()
    val externalReference =
        ReferenceSnapshot(
            id = "reference:cycle-out",
            sourceFileId = "file:a",
            sourceSymbolId = "symbol:a",
            line = 1,
            context = "External()",
            targetName = "External",
            targetQualifiedName = "fixture.External",
            kind = ReferenceKind.CONSTRUCTOR,
            evidence = RelationshipEvidence.DIRECT,
        )
    val returnReference =
        ReferenceSnapshot(
            id = "reference:cycle-back",
            sourceFileId = "file:c",
            sourceSymbolId = "symbol:c",
            line = 1,
            context = "Consumer()",
            targetName = "Consumer",
            targetQualifiedName = "fixture.Consumer",
            kind = ReferenceKind.CONSTRUCTOR,
            evidence = RelationshipEvidence.DIRECT,
        )
    val outbound =
        RelationshipSnapshot(
            id = "relationship:cycle-out",
            referenceId = externalReference.id,
            sourceSymbolId = "symbol:a",
            targetSymbolId = "symbol:c",
            kind = RelationshipKind.CONSTRUCTOR,
            resolutionEvidence = RelationshipEvidence.DIRECT,
        )
    val inbound =
        RelationshipSnapshot(
            id = "relationship:cycle-back",
            referenceId = returnReference.id,
            sourceSymbolId = "symbol:c",
            targetSymbolId = "symbol:a",
            kind = RelationshipKind.CONSTRUCTOR,
            resolutionEvidence = RelationshipEvidence.DIRECT,
        )
    return fixture.copy(
        references = (fixture.references + externalReference + returnReference).sortedBy(ReferenceSnapshot::id),
        relationships = (fixture.relationships + outbound + inbound).sortedBy(RelationshipSnapshot::id),
        relationshipCycles =
            listOf(
                CycleSnapshot(
                    id = "cycle:cross-project",
                    symbolIds = listOf("symbol:a", "symbol:c", "symbol:a"),
                    relationshipIds = listOf(outbound.id, inbound.id),
                ),
            ),
    )
}

internal fun viewerDistribution(vararg extraEntries: Pair<String, ByteArray>): ByteArray =
    zipBytes(
        "index.html" to
            "<html><body><main id=app></main><script type=module src=\"assets/docx-viewer.mjs\"></script></body></html>"
                .encodeToByteArray(),
        "assets/docx-viewer.mjs" to
            "WebAssembly.instantiateStreaming(fetch('assets/docx-viewer.wasm'));".encodeToByteArray(),
        "assets/docx-viewer.wasm" to byteArrayOf(0, 97, 115, 109),
        *extraEntries,
    )

internal fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name).apply { time = 0 })
            zip.write(content)
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

internal fun tempDirectory(prefix: String): File =
    File.createTempFile(prefix, "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

internal fun docxTestPlan(
    scope: DocxScopePlan =
        DocxScopePlan(
            builds = allNames(),
            projects = allNames(),
            sourceSets = allNames(),
            includeTests = true,
            configuredBuilds = emptyList(),
            unknownSelections = UnknownSelectionPolicy.FAIL,
        ),
    features: List<DocxFeaturePlan> = testFeaturePlans(),
    missingCapabilities: MissingCapabilityPolicy = MissingCapabilityPolicy.WARN,
): DocxAnalysisPlan =
    DocxAnalysisPlan(
        preset = DocxPreset.FULL,
        output =
            DocxOutputPlan(
                directory = Docx.DEFAULT_OUTPUT_DIRECTORY,
                snapshotFile = Docx.DEFAULT_SNAPSHOT_FILE,
                formats = listOf(DocxOutputFormat.HTML),
            ),
        scope = scope,
        features = features,
        requiredCapabilities = emptyList(),
        missingCapabilities = missingCapabilities,
        updates =
            DocxUpdatePlan(
                mode = DocxUpdateMode.ASYNC,
                liveReload = false,
                maxParallelism = 1,
                debounceMilliseconds = 0,
            ),
    )

internal fun allNames(): NameSelectionPlan =
    NameSelectionPlan(
        all = true,
        included = emptyList(),
        excluded = emptyList(),
    )

private fun testFeaturePlans(): List<DocxFeaturePlan> =
    DocxFeature.entries
        .map { feature ->
            when (feature) {
                DocxFeature.FINDINGS ->
                    DocxFeaturePlan(
                        feature = feature,
                        enabled = true,
                        severities = FindingSeveritySelection.entries.sortedBy(Enum<*>::name),
                    )
                DocxFeature.SYMBOLS,
                DocxFeature.RELATIONSHIPS,
                DocxFeature.CYCLES,
                ->
                    DocxFeaturePlan(
                        feature = feature,
                        enabled = true,
                        depth = GraphDepthPlan(GraphDepthMode.FULL),
                        includeDisconnected = true,
                        relationshipKinds = RelationshipKindSelection.entries.sortedBy(Enum<*>::name),
                    )
                DocxFeature.DEPENDENCY_INJECTION -> DocxFeaturePlan(feature = feature, enabled = false)
                DocxFeature.SOURCES ->
                    DocxFeaturePlan(
                        feature = feature,
                        enabled = true,
                        includeSourceContent = true,
                    )
                else -> DocxFeaturePlan(feature = feature, enabled = true)
            }
        }.sortedBy { it.feature.name }
