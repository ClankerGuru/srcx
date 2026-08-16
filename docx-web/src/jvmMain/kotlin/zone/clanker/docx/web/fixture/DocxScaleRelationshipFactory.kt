package zone.clanker.docx.web.fixture

import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ReferenceKind
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot

internal class DocxScaleRelationshipFactory(
    private val project: DocxScaleProject,
) {
    private val source = DocxScaleSourceFactory(project)

    fun reference(symbolIndex: Int): ReferenceSnapshot {
        val targetIndex = (symbolIndex + 1) % project.symbolCount
        return ReferenceSnapshot(
            id = project.referenceId(symbolIndex),
            sourceFileId = project.fileId(symbolIndex / project.dimensions.symbolsPerFile),
            sourceSymbolId = project.symbolId(symbolIndex),
            line = source.symbolLine(symbolIndex) + 1,
            context = "dependency: ${project.symbolName(targetIndex)}",
            targetName = project.symbolName(targetIndex),
            targetQualifiedName = "${project.packageName}.${project.symbolName(targetIndex)}",
            kind = ReferenceKind.PARAMETER_TYPE,
            evidence = RelationshipEvidence.DIRECT,
        )
    }

    fun relationship(symbolIndex: Int): RelationshipSnapshot =
        RelationshipSnapshot(
            id = project.relationshipId(symbolIndex),
            referenceId = project.referenceId(symbolIndex),
            sourceSymbolId = project.symbolId(symbolIndex),
            targetSymbolId = project.symbolId((symbolIndex + 1) % project.symbolCount),
            kind = RelationshipKind.PARAMETER_TYPE,
            resolutionEvidence = RelationshipEvidence.DIRECT,
        )

    fun dependencyReference(dependency: DocxScaleProject): ReferenceSnapshot =
        ReferenceSnapshot(
            id = project.dependencyReferenceId(),
            sourceFileId = project.fileId(0),
            sourceSymbolId = project.symbolId(0),
            line = DEPENDENCY_REFERENCE_LINE,
            context = "calls ${dependency.packageName}.${dependency.symbolName(0)}",
            targetName = dependency.symbolName(0),
            targetQualifiedName = "${dependency.packageName}.${dependency.symbolName(0)}",
            kind = ReferenceKind.CALL,
            evidence = RelationshipEvidence.DIRECT,
        )

    fun dependencyRelationship(dependency: DocxScaleProject): RelationshipSnapshot =
        RelationshipSnapshot(
            id = project.dependencyRelationshipId(),
            referenceId = project.dependencyReferenceId(),
            sourceSymbolId = project.symbolId(0),
            targetSymbolId = dependency.symbolId(0),
            kind = RelationshipKind.CALL,
            resolutionEvidence = RelationshipEvidence.DIRECT,
        )

    fun finding(): FindingSnapshot =
        FindingSnapshot(
            id = project.findingId,
            severity = FindingSeverity.WARNING,
            message = "Synthetic scale project participates in a deterministic relationship cycle",
            suggestion = "Use this evidence only for DOCX scale and interaction verification.",
            fileId = project.fileId(0),
            filePath =
                "src/main/kotlin/${project.packageName.replace('.', '/')}/Node${padded(0, FILE_WIDTH)}.kt",
            line = 1,
            symbolIds = listOf(project.symbolId(0)),
        )
}
