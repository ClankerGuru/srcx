package zone.clanker.docx.web.fixture

import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot

internal class DocxScaleProjectGraphFactory(
    private val project: DocxScaleProject,
    private val dependency: DocxScaleProject?,
) {
    private val source = DocxScaleSourceFactory(project)
    private val relationships = DocxScaleRelationshipFactory(project)

    fun graph(): ProjectGraphShard =
        ProjectGraphShard(
            projectId = project.id,
            sourceSets = sourceSets(),
            files = files(),
            symbols = symbols(),
            references = references(),
            relationships = relationshipRecords(),
            findings = listOf(relationships.finding()),
            cycles = listOf(cycle()),
        )

    private fun sourceSets(): List<SourceSetSnapshot> =
        (
            SOURCE_SET_NAMES.map(source::sourceSet) +
                dependency?.let { project -> DocxScaleSourceFactory(project).sourceSet("main") }
        ).filterNotNull()
            .distinctBy(SourceSetSnapshot::id)
            .sortedBy(SourceSetSnapshot::id)

    private fun files(): List<SourceFileSnapshot> =
        (
            (0 until project.fileCount).map(source::sourceFile) +
                dependency?.let { project -> DocxScaleSourceFactory(project).sourceFile(0) }
        ).filterNotNull()
            .distinctBy(SourceFileSnapshot::id)
            .sortedBy(SourceFileSnapshot::id)

    private fun symbols(): List<SymbolSnapshot> =
        (
            (0 until project.symbolCount).map(source::symbol) +
                dependency?.let { project -> DocxScaleSourceFactory(project).symbol(0) }
        ).filterNotNull()
            .distinctBy(SymbolSnapshot::id)
            .sortedBy(SymbolSnapshot::id)

    private fun references(): List<ReferenceSnapshot> =
        (
            (0 until project.symbolCount).map(relationships::reference) +
                dependency?.let(relationships::dependencyReference)
        ).filterNotNull()
            .sortedBy(ReferenceSnapshot::id)

    private fun relationshipRecords(): List<RelationshipSnapshot> =
        (
            (0 until project.symbolCount).map(relationships::relationship) +
                dependency?.let(relationships::dependencyRelationship)
        ).filterNotNull()
            .sortedBy(RelationshipSnapshot::id)

    private fun cycle(): CycleSnapshot =
        CycleSnapshot(
            id =
                "cycle:scale:${project.dimensions.profileName}:${padded(project.buildIndex, BUILD_WIDTH)}:" +
                    padded(project.projectIndex, PROJECT_WIDTH),
            symbolIds = (0 until project.symbolCount).map(project::symbolId) + project.symbolId(0),
            relationshipIds = (0 until project.symbolCount).map(project::relationshipId),
        )
}
