package zone.clanker.gradle.docx.site

import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceSearchEntry
import zone.clanker.report.model.WorkspaceSearchKind
import zone.clanker.report.model.WorkspaceSearchLocation
import zone.clanker.report.model.WorkspaceSearchTarget
import zone.clanker.report.model.workspaceSearchTerms

internal fun StaticSearchProjectionContext.packageEntries(): List<WorkspaceSearchEntry> =
    graphs.flatMap { graph ->
        ownedSymbols(graph)
            .groupBy(SymbolSnapshot::packageName)
            .map { (packageName, symbols) ->
                val project = projectsById.getValue(graph.projectId)
                WorkspaceSearchEntry(
                    id = "package:${graph.projectId}:$packageName",
                    kind = WorkspaceSearchKind.PACKAGE,
                    label = packageName,
                    detail = "${scopeDetail(graph.projectId)} / ${symbols.size} declarations",
                    terms = workspaceSearchTerms(packageName, project.path),
                    target =
                        WorkspaceSearchTarget(
                            WorkspaceSearchLocation(buildId = project.buildId, projectId = project.id),
                        ),
                )
            }
    }

internal fun StaticSearchProjectionContext.fileEntries(): List<WorkspaceSearchEntry> =
    graphs.flatMap { graph ->
        ownedFiles(graph).map { file -> fileEntry(graph.projectId, file) }
    }

internal fun StaticSearchProjectionContext.symbolEntries(): List<WorkspaceSearchEntry> =
    graphs.flatMap { graph ->
        val filesById = graph.files.associateBy(SourceFileSnapshot::id)
        ownedSymbols(graph).map { symbol ->
            val file = filesById.getValue(symbol.fileId)
            val sourceSet = sourceSet(file)
            val project = projectsById.getValue(graph.projectId)
            WorkspaceSearchEntry(
                id = symbol.id,
                kind = symbol.kind.searchKind(),
                label = symbol.name,
                detail =
                    "${symbol.kind.label} / ${symbol.qualifiedName} / " +
                        scopeDetail(graph.projectId, sourceSet.id),
                terms =
                    workspaceSearchTerms(
                        symbol.name,
                        symbol.qualifiedName,
                        symbol.packageName,
                        file.projectRelativePath,
                    ),
                target =
                    WorkspaceSearchTarget(
                        WorkspaceSearchLocation(
                            buildId = project.buildId,
                            projectId = project.id,
                            sourceSetId = sourceSet.id,
                            fileId = file.id,
                            line = symbol.declarationLine,
                        ),
                    ),
                badges = badges(graph.projectId, symbol.id),
            )
        }
    }

private fun StaticSearchProjectionContext.fileEntry(
    projectId: String,
    file: SourceFileSnapshot,
): WorkspaceSearchEntry =
    run {
        val sourceSet = sourceSet(file)
        val project = projectsById.getValue(projectId)
        val extension = file.projectRelativePath.fileExtension()
        WorkspaceSearchEntry(
            id = file.id,
            kind = WorkspaceSearchKind.FILE,
            label = file.projectRelativePath.substringAfterLast('/'),
            detail = "${scopeDetail(projectId, sourceSet.id)} / ${file.projectRelativePath}",
            terms =
                workspaceSearchTerms(
                    file.projectRelativePath,
                    sourceSet.name,
                    file.language.label,
                    extension.orEmpty(),
                ),
            target =
                WorkspaceSearchTarget(
                    location =
                        WorkspaceSearchLocation(
                            buildId = project.buildId,
                            projectId = project.id,
                            sourceSetId = sourceSet.id,
                            fileId = file.id,
                        ),
                    extension = extension,
                ),
            badges = badges(projectId, file.id),
        )
    }

private fun SymbolKind.searchKind(): WorkspaceSearchKind =
    when (this) {
        SymbolKind.CLASS,
        SymbolKind.DATA_CLASS,
        -> WorkspaceSearchKind.CLASS
        SymbolKind.INTERFACE -> WorkspaceSearchKind.INTERFACE
        SymbolKind.OBJECT -> WorkspaceSearchKind.OBJECT
        SymbolKind.ENUM -> WorkspaceSearchKind.ENUM
        SymbolKind.FUNCTION -> WorkspaceSearchKind.FUNCTION
        SymbolKind.PROPERTY -> WorkspaceSearchKind.PROPERTY
    }

private fun String.fileExtension(): String? =
    substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase()
        .takeIf { extension -> extension.isNotBlank() && extension.all(Char::isLetterOrDigit) }
