package zone.clanker.docx.web.atlas.dom.detail

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolSnapshot

internal fun relationshipEvidenceRecords(
    projects: List<ProjectGraphShard>,
    relationshipIds: Set<String>,
    preferredRelationshipId: String,
): List<RelationshipEvidenceRecord> =
    projects
        .flatMap { project -> projectRelationshipEvidence(project, relationshipIds) }
        .sortedWith(
            compareBy<RelationshipEvidenceRecord> { record ->
                record.relationship.id != preferredRelationshipId
            }.thenBy { record -> record.relationship.id }
                .thenBy { record -> record.sourceFile.content == null },
        ).distinctBy { record -> record.relationship.id }

internal fun appendRelationshipEvidence(
    parent: HTMLElement,
    records: List<RelationshipEvidenceRecord>,
    sourceContent: (RelationshipEvidenceRecord) -> String?,
    unavailableMessage: (RelationshipEvidenceRecord) -> String,
) {
    val section = htmlElement("section", "srcx-dashboard__architecture-detail-section")
    section.appendChild(htmlElement("h4", text = "Source occurrences"))
    val controls = htmlElement("div", "srcx-dashboard__architecture-occurrence-controls")
    val previous = htmlElement("button", text = "Previous") as HTMLButtonElement
    previous.type = "button"
    val status = htmlElement("p", "srcx-dashboard__architecture-occurrence-status")
    status.setAttribute("role", "status")
    status.setAttribute("aria-live", "polite")
    val next = htmlElement("button", text = "Next") as HTMLButtonElement
    next.type = "button"
    controls.appendChild(previous)
    controls.appendChild(status)
    controls.appendChild(next)
    val metadata = htmlElement("div", "srcx-dashboard__architecture-occurrence-metadata")
    val source = htmlElement("div", "srcx-dashboard__architecture-occurrence-source")
    section.appendChild(controls)
    section.appendChild(metadata)
    section.appendChild(source)
    parent.appendChild(section)
    var activeIndex = 0

    fun renderActive() {
        val record = records[activeIndex]
        previous.disabled = activeIndex == 0
        next.disabled = activeIndex == records.lastIndex
        status.textContent =
            "Record ${activeIndex + 1} of ${records.size} / " +
            "${record.sourceFile.projectRelativePath}:${record.reference.line}"
        metadata.clearContent()
        metadata.appendChild(relationshipMetadata(record))
        source.clearContent()
        val lineCounts =
            records
                .filter { candidate -> candidate.sourceFile.id == record.sourceFile.id }
                .groupingBy { candidate -> candidate.reference.line }
                .eachCount()
        appendSourceEvidence(
            source,
            SourceEvidenceSpec(
                file = record.sourceFile,
                content = sourceContent(record),
                unavailableMessage = unavailableMessage(record),
                declarationLines = record.declarationLines,
                declarationRanges = record.declarationRanges,
                activeLine = record.reference.line,
                activeRange = record.reference.occurrenceRange,
                activeKind = SourceEvidenceKind.RELATIONSHIP,
                relationshipLineCounts = lineCounts,
            ),
        )
    }
    previous.onclick = {
        if (activeIndex > 0) {
            activeIndex -= 1
            renderActive()
        }
        null
    }
    next.onclick = {
        if (activeIndex < records.lastIndex) {
            activeIndex += 1
            renderActive()
        }
        null
    }
    renderActive()
}

private fun projectRelationshipEvidence(
    project: ProjectGraphShard,
    relationshipIds: Set<String>,
): List<RelationshipEvidenceRecord> {
    val references = project.references.associateBy(ReferenceSnapshot::id)
    val files = project.files.associateBy(SourceFileSnapshot::id)
    val symbols = project.symbols.associateBy(SymbolSnapshot::id)
    val declarationLines =
        project.symbols
            .groupBy(SymbolSnapshot::fileId)
            .mapValues { (_, declarations) ->
                declarations.mapTo(mutableSetOf(), SymbolSnapshot::declarationLine)
            }
    val declarationRanges =
        project.symbols
            .groupBy(SymbolSnapshot::fileId)
            .mapValues { (_, declarations) ->
                declarations.mapNotNull(SymbolSnapshot::declarationRange).sortedBy { range -> range.startOffset }
            }
    return project.relationships.mapNotNull { relationship ->
        if (relationship.id !in relationshipIds) return@mapNotNull null
        val reference = references[relationship.referenceId] ?: return@mapNotNull null
        val sourceFile = files[reference.sourceFileId] ?: return@mapNotNull null
        val target = symbols[relationship.targetSymbolId] ?: return@mapNotNull null
        val targetFile = files[target.fileId] ?: return@mapNotNull null
        RelationshipEvidenceRecord(
            projectId = project.projectId,
            relationship = relationship,
            reference = reference,
            sourceFile = sourceFile,
            target = target,
            targetFile = targetFile,
            declarationLines = declarationLines[sourceFile.id].orEmpty(),
            declarationRanges = declarationRanges[sourceFile.id].orEmpty(),
        )
    }
}

private fun relationshipMetadata(record: RelationshipEvidenceRecord): HTMLElement =
    detailList(
        "Direction" to
            "${record.sourceFile.projectRelativePath} → ${record.targetFile.projectRelativePath}",
        "Target" to record.target.qualifiedName,
        "Kind" to record.relationship.kind.label,
        "Evidence" to record.relationship.resolutionEvidence.label,
        "Source line" to record.reference.line.toString(),
        "Source context" to record.reference.context,
    )
