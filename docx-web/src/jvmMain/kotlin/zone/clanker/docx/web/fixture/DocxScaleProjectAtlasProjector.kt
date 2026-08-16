package zone.clanker.docx.web.fixture

import zone.clanker.report.model.AtlasCount
import zone.clanker.report.model.AtlasEdge
import zone.clanker.report.model.AtlasEdgeCategory
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.AtlasNodeType
import zone.clanker.report.model.SourceLanguage

internal class DocxScaleProjectAtlasProjector(
    private val project: DocxScaleProject,
) {
    private val source = DocxScaleSourceFactory(project)

    fun fileNode(fileIndex: Int): AtlasNode {
        val file = source.sourceFile(fileIndex)
        val findingIds = if (fileIndex == 0) listOf(project.findingId) else emptyList()
        return AtlasNode(
            id = file.id,
            type = AtlasNodeType.FILE,
            name = file.projectRelativePath.substringAfterLast('/'),
            path = file.projectRelativePath,
            buildId = project.buildId,
            buildName = project.dimensions.buildName(project.buildIndex),
            projectId = project.id,
            projectPath = project.projectPath,
            sourceSet = source.sourceSetName(fileIndex),
            sourceFileId = file.id,
            language = SourceLanguage.KOTLIN.label,
            kind = SourceLanguage.KOTLIN.name,
            findingIds = findingIds,
        )
    }

    fun fileEdge(fileIndex: Int): AtlasEdge {
        val sourceSymbolIndex = (fileIndex + 1) * project.dimensions.symbolsPerFile - 1
        val relationshipId = project.relationshipId(sourceSymbolIndex)
        return AtlasEdge(
            id = relationshipId,
            sourceId = project.fileId(fileIndex),
            targetId = project.fileId(fileIndex + 1),
            category = AtlasEdgeCategory.REFERENCES,
            relationshipIds = listOf(relationshipId),
            referenceIds = listOf(project.referenceId(sourceSymbolIndex)),
            recordCount = 1,
            kindCounts = listOf(AtlasCount("PARAMETER_TYPE", "Parameter type", 1)),
            evidenceCounts = listOf(AtlasCount("DIRECT", "Direct", 1)),
        )
    }

    fun dependencyEdge(dependency: DocxScaleProject): AtlasEdge =
        AtlasEdge(
            id = project.dependencyRelationshipId(),
            sourceId = project.fileId(0),
            targetId = dependency.fileId(0),
            category = AtlasEdgeCategory.CALLS,
            relationshipIds = listOf(project.dependencyRelationshipId()),
            referenceIds = listOf(project.dependencyReferenceId()),
            recordCount = 1,
            kindCounts = listOf(AtlasCount("CALL", "Call", 1)),
            evidenceCounts = listOf(AtlasCount("DIRECT", "Direct", 1)),
            crossBuild = project.buildId != dependency.buildId,
        )
}
