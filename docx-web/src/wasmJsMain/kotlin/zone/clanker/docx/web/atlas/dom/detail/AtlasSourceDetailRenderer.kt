package zone.clanker.docx.web.atlas.dom.detail

import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.evidenceProjects
import zone.clanker.docx.web.evidence.WorkspaceEvidenceInspectionState
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolSnapshot

internal data class AtlasSourceDetailRenderer(
    val root: HTMLDivElement,
    val fields: HTMLElement,
    val model: AtlasDomSurfaceModel,
    val project: ProjectGraphShard,
    val evidence: AtlasDomDetailRenderer,
)

internal fun projectForNode(
    projects: List<ProjectGraphShard>,
    nodeId: String,
): ProjectGraphShard? =
    projects.firstOrNull { project -> nodeId in project.ownedFileIds || nodeId in project.ownedSymbolIds }
        ?: projects.firstOrNull { project ->
            project.files.any { file -> file.id == nodeId } || project.symbols.any { symbol -> symbol.id == nodeId }
        }

internal fun renderNodeDetail(
    renderer: AtlasSourceDetailRenderer,
    nodeId: String,
) {
    val project = renderer.project
    val symbol = project.symbols.firstOrNull { candidate -> candidate.id == nodeId }
    val file = project.files.firstOrNull { candidate -> candidate.id == nodeId }
    when {
        symbol != null -> renderSymbolDetail(renderer, symbol)
        file != null -> renderFileDetail(renderer.root, renderer.fields, renderer.model, project, file)
        else -> renderUnavailableDetail(renderer.root, renderer.fields)
    }
}

private fun renderSymbolDetail(
    renderer: AtlasSourceDetailRenderer,
    symbol: SymbolSnapshot,
) {
    val project = renderer.project
    val file = project.files.single { candidate -> candidate.id == symbol.fileId }
    setDetailHeading(renderer.root, symbol.kind.label, symbol.name)
    renderer.fields.appendChild(
        detailList(
            "Qualified name" to symbol.qualifiedName,
            "Semantic" to symbol.declarationSemantic.label,
            "Source file" to file.projectRelativePath,
            "Declaration line" to symbol.declarationLine.toString(),
            "Outgoing records" to project.relationships.count { it.sourceSymbolId == symbol.id }.toString(),
            "Incoming records" to project.relationships.count { it.targetSymbolId == symbol.id }.toString(),
        ),
    )
    val exactEvidence = renderer.evidence.inspection as? WorkspaceEvidenceInspectionState.Symbol
    if (exactEvidence?.symbolId == symbol.id) {
        appendExactSymbolEvidence(renderer.fields, renderer.model, exactEvidence, renderer.evidence.pagedActions)
        if (exactEvidence.error == null || exactEvidence.declaration != null || exactEvidence.usages != null) return
    }
    appendSourceEvidence(
        renderer.fields,
        SourceEvidenceSpec(
            file = file,
            content = renderer.model.sourceContent(project.projectId, file),
            unavailableMessage = renderer.model.sourceUnavailableMessage(project.projectId, file),
            declarationLines = declarationLines(project, file.id),
            declarationRanges = declarationRanges(project, file.id),
            activeLine = symbol.declarationLine,
            activeRange = symbol.declarationRange,
            activeKind = SourceEvidenceKind.DECLARATION,
        ),
    )
}

internal fun appendExactSymbolEvidence(
    fields: HTMLElement,
    model: AtlasDomSurfaceModel,
    evidence: WorkspaceEvidenceInspectionState.Symbol,
    actions: AtlasPagedEvidenceActions,
) {
    appendExactEvidenceStatus(fields, evidence.loading, evidence.error)
    evidence.declaration?.let { page ->
        val declaration = page.declaration
        if (declaration == null) {
            fields.appendChild(detailList("Declaration" to "No declaration was found in this generation."))
        } else {
            val project =
                model.evidenceProjects().firstOrNull { candidate ->
                    candidate.projectId == declaration.location.projectId
                }
            val file = project?.files?.firstOrNull { candidate -> candidate.id == declaration.location.fileId }
            appendDeclarationEvidence(
                parent = fields,
                renderer =
                    AtlasDeclarationEvidenceRenderer(
                        page = page,
                        file = file,
                        content = file?.let { source -> model.sourceContent(declaration.location.projectId, source) },
                        fileDeclarationRanges =
                            project
                                ?.let { loaded -> declarationRanges(loaded, declaration.location.fileId) }
                                .orEmpty(),
                        unavailableMessage =
                            file?.let { source ->
                                model.sourceUnavailableMessage(declaration.location.projectId, source)
                            } ?: "Loading the declaration's bounded project and source file…",
                    ),
            )
            if (file == null) fields.appendChild(exactDeclarationLoadStatus(model, declaration.location.projectId))
        }
    }
    evidence.usages?.let { usages ->
        appendPagedRelationshipEvidence(
            parent = fields,
            renderer =
                AtlasPagedEvidenceRenderer(
                    state = usages,
                    file = model::evidenceFile,
                    sourceContent = model::evidenceSourceContent,
                    declarationRanges = model::evidenceDeclarationRanges,
                    actions = actions,
                ),
        )
    }
    if (evidence.usages == null && !evidence.loading && evidence.error == null) {
        fields.appendChild(detailList("Reverse usages" to "No callers or references in this generation."))
    }
}

private fun exactDeclarationLoadStatus(
    model: AtlasDomSurfaceModel,
    projectId: String,
): HTMLElement {
    val message =
        when {
            model.evidence.requestedProjectId == projectId && model.evidence.loading ->
                "Loading the declaration's bounded project and source file…"
            model.evidence.requestedProjectId == projectId && model.evidence.error != null ->
                "Declaration source failed: ${model.evidence.error}"
            else -> "The declaration's bounded project or source file is unavailable."
        }
    return detailList("Declaration source" to message)
}

private fun renderFileDetail(
    root: HTMLDivElement,
    fields: HTMLElement,
    model: AtlasDomSurfaceModel,
    project: ProjectGraphShard,
    file: SourceFileSnapshot,
) {
    val sourceSet = project.sourceSets.single { candidate -> candidate.id == file.sourceSetId }
    val referencesById = project.references.associateBy(ReferenceSnapshot::id)
    val symbolsById = project.symbols.associateBy(SymbolSnapshot::id)
    setDetailHeading(root, "File", file.projectRelativePath.substringAfterLast('/'))
    fields.appendChild(
        detailList(
            "Path" to file.projectRelativePath,
            "Language" to file.language.label,
            "Source set" to sourceSet.name,
            "Declarations" to project.symbols.count { it.fileId == file.id }.toString(),
            "Outgoing records" to
                project.relationships
                    .count { relationship ->
                        referencesById[relationship.referenceId]?.sourceFileId == file.id
                    }.toString(),
            "Incoming records" to
                project.relationships
                    .count { relationship ->
                        symbolsById[relationship.targetSymbolId]?.fileId == file.id
                    }.toString(),
        ),
    )
    appendSourceEvidence(
        fields,
        SourceEvidenceSpec(
            file = file,
            content = model.sourceContent(project.projectId, file),
            unavailableMessage = model.sourceUnavailableMessage(project.projectId, file),
            declarationLines = declarationLines(project, file.id),
            declarationRanges = declarationRanges(project, file.id),
        ),
    )
}

internal fun AtlasDomSurfaceModel.sourceContent(
    projectId: String,
    file: SourceFileSnapshot,
): String? = file.content ?: evidence.source.contentFor(projectId, file.id)

internal fun AtlasDomSurfaceModel.sourceUnavailableMessage(
    projectId: String,
    file: SourceFileSnapshot,
): String =
    when {
        evidence.source.matches(projectId, file.id) && evidence.source.loading ->
            "Loading the selected source file…"
        evidence.source.errorFor(projectId, file.id) != null ->
            "Source evidence failed: ${evidence.source.errorFor(projectId, file.id)}"
        else -> "Source text was not included for this bounded file."
    }
