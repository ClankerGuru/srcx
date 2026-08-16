package zone.clanker.docx.index.importing

import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.WorkspaceGraphDeclarationKind
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphSearchTarget
import zone.clanker.report.model.WorkspaceSummaryShard

internal fun indexedGraphDescriptors(
    summary: WorkspaceSummaryShard,
    rows: WorkspaceGraphSourceRows,
): List<IndexedGraphDescriptor> {
    val base = WorkspaceGraphBaseDescriptors(summary, rows).descriptors()
    val detail = WorkspaceGraphDetailDescriptors(rows, base).descriptors()
    val hierarchy = base + detail
    val overlays = WorkspaceGraphOverlayDescriptors(rows, hierarchy).descriptors()
    return (hierarchy + overlays).sortedBy(IndexedGraphDescriptor::id)
}

private class WorkspaceGraphBaseDescriptors(
    private val summary: WorkspaceSummaryShard,
    private val rows: WorkspaceGraphSourceRows,
) {
    private val buildsById = summary.builds.associateBy(BuildSnapshot::id)
    private val projectsById = summary.projects.associateBy(ProjectSnapshot::id)

    fun descriptors(): List<IndexedGraphDescriptor> =
        listOf(workspaceDescriptor()) +
            summary.builds.map(::buildDescriptor) +
            summary.projects.map(::projectDescriptor) +
            sourceSetDescriptors()

    private fun sourceSetDescriptors(): List<IndexedGraphDescriptor> {
        val summaryRows =
            summary.sourceSets.associate { sourceSet ->
                sourceSet.id to GraphSourceSetRow(sourceSet.id, sourceSet.projectId, sourceSet.name)
            }
        rows.sourceSets.forEach { sourceSet ->
            summaryRows[sourceSet.id]?.let { summarized ->
                require(summarized == sourceSet) {
                    "Indexed graph source set conflicts with its summary: ${sourceSet.id}"
                }
            }
        }
        return (summaryRows.values + rows.sourceSets)
            .distinctBy(GraphSourceSetRow::id)
            .sortedBy(GraphSourceSetRow::id)
            .map(::sourceSetDescriptor)
    }

    private fun workspaceDescriptor(): IndexedGraphDescriptor =
        IndexedGraphDescriptor(
            id = summary.workspace.id,
            kind = WorkspaceGraphNodeKind.WORKSPACE,
            parentId = null,
            depth = 0,
            semanticLevel = GRAPH_WORKSPACE_LEVEL,
            label = summary.workspace.name,
            secondaryLabel = "Workspace",
        )

    private fun buildDescriptor(build: BuildSnapshot): IndexedGraphDescriptor =
        IndexedGraphDescriptor(
            id = build.id,
            kind = WorkspaceGraphNodeKind.BUILD,
            parentId = summary.workspace.id,
            depth = GRAPH_BUILD_DEPTH,
            semanticLevel = GRAPH_BUILD_LEVEL,
            label = build.name,
            secondaryLabel = build.kind.label,
            searchFields = mapOf(WorkspaceGraphSearchTarget.BUILD to listOf(build.id, build.name, build.relativePath)),
        )

    private fun projectDescriptor(project: ProjectSnapshot): IndexedGraphDescriptor =
        IndexedGraphDescriptor(
            id = project.id,
            kind = WorkspaceGraphNodeKind.PROJECT,
            parentId = project.buildId,
            depth = GRAPH_PROJECT_DEPTH,
            semanticLevel = GRAPH_PROJECT_LEVEL,
            label = project.path,
            secondaryLabel = buildsById.getValue(project.buildId).name,
            searchFields =
                mapOf(
                    WorkspaceGraphSearchTarget.PROJECT to listOf(project.id, project.path, project.buildFile),
                ),
        )

    private fun sourceSetDescriptor(sourceSet: GraphSourceSetRow): IndexedGraphDescriptor {
        require(sourceSet.projectId in projectsById) { "Indexed graph source set belongs to an unknown project" }
        return IndexedGraphDescriptor(
            id = sourceSet.id,
            kind = WorkspaceGraphNodeKind.SOURCE_SET,
            parentId = sourceSet.projectId,
            depth = GRAPH_SOURCE_SET_DEPTH,
            semanticLevel = GRAPH_SOURCE_SET_LEVEL,
            label = sourceSet.name,
            secondaryLabel = projectsById.getValue(sourceSet.projectId).path,
        )
    }
}

private class WorkspaceGraphDetailDescriptors(
    private val rows: WorkspaceGraphSourceRows,
    baseDescriptors: List<IndexedGraphDescriptor>,
) {
    private val sourceSetIds =
        baseDescriptors
            .filter { descriptor -> descriptor.kind == WorkspaceGraphNodeKind.SOURCE_SET }
            .mapTo(mutableSetOf(), IndexedGraphDescriptor::id)
    private val filesById = rows.files.filter { file -> file.sourceSetId in sourceSetIds }.associateBy(GraphFileRow::id)
    private val symbols = rows.symbols.filter { symbol -> symbol.fileId in filesById }
    private val packageNameByFileId = packageNamesByFile()
    private val packages = packageDescriptors()
    private val files = fileDescriptors()

    fun descriptors(): List<IndexedGraphDescriptor> = packages + files + symbolDescriptors()

    private fun packageNamesByFile(): Map<String, String> =
        symbols
            .groupBy(GraphSymbolRow::fileId)
            .mapValues { (_, fileSymbols) ->
                val packages = fileSymbols.map(GraphSymbolRow::packageName).distinct().sorted()
                packages.singleOrNull()
            }.mapNotNull { (fileId, packageName) -> packageName?.let { fileId to it } }
            .toMap()

    private fun packageDescriptors(): List<IndexedGraphDescriptor> {
        val observed =
            packageNameByFileId.entries.flatMap { (fileId, packageName) ->
                val sourceSetId = filesById.getValue(fileId).sourceSetId
                val prefixes = packagePrefixes(packageName)
                prefixes.mapIndexed { index, prefix ->
                    IndexedGraphDescriptor(
                        id = indexedPackageId(sourceSetId, prefix),
                        kind = WorkspaceGraphNodeKind.PACKAGE,
                        parentId =
                            if (index == 0) {
                                sourceSetId
                            } else {
                                indexedPackageId(sourceSetId, prefixes[index - 1])
                            },
                        depth = GRAPH_SOURCE_SET_DEPTH + index + 1,
                        semanticLevel = GRAPH_PACKAGE_LEVEL,
                        label = prefix.substringAfterLast('.'),
                        secondaryLabel = prefix,
                        searchFields = mapOf(WorkspaceGraphSearchTarget.PACKAGE to listOf(prefix)),
                    )
                }
            }
        val unknown =
            filesById.values
                .filter { file -> file.id !in packageNameByFileId }
                .map(GraphFileRow::sourceSetId)
                .distinct()
                .map(::unknownPackageDescriptor)
        return (observed + unknown).distinctBy(IndexedGraphDescriptor::id).sortedBy(IndexedGraphDescriptor::id)
    }

    private fun fileDescriptors(): List<IndexedGraphDescriptor> =
        filesById.values.sortedBy(GraphFileRow::id).map { file ->
            val packageName = packageNameByFileId[file.id]
            val packageDepth = packageName?.let(::packagePrefixes)?.size ?: 1
            IndexedGraphDescriptor(
                id = file.id,
                kind = WorkspaceGraphNodeKind.FILE,
                parentId =
                    packageName?.let { name -> indexedPackageId(file.sourceSetId, name) }
                        ?: indexedUnknownPackageId(file.sourceSetId),
                depth = GRAPH_SOURCE_SET_DEPTH + packageDepth + 1,
                semanticLevel = GRAPH_FILE_LEVEL,
                label = file.path.substringAfterLast('/'),
                secondaryLabel = file.path,
                searchFields = file.searchFields(),
            )
        }

    private fun symbolDescriptors(): List<IndexedGraphDescriptor> {
        val depths = files.associate { file -> file.id to file.depth }
        return symbols.sortedBy(GraphSymbolRow::id).map { symbol ->
            IndexedGraphDescriptor(
                id = symbol.id,
                kind = symbol.kind.nodeKind(),
                parentId = symbol.fileId,
                depth = depths.getValue(symbol.fileId) + 1,
                semanticLevel = GRAPH_SYMBOL_LEVEL,
                label = symbol.name,
                secondaryLabel = symbol.qualifiedName,
                searchFields = symbol.searchFields(),
                declarationKinds = symbol.kind.declarationKinds(),
            )
        }
    }

    private fun unknownPackageDescriptor(sourceSetId: String): IndexedGraphDescriptor =
        IndexedGraphDescriptor(
            id = indexedUnknownPackageId(sourceSetId),
            kind = WorkspaceGraphNodeKind.PACKAGE,
            parentId = sourceSetId,
            depth = GRAPH_SOURCE_SET_DEPTH + 1,
            semanticLevel = GRAPH_PACKAGE_LEVEL,
            label = "Unknown package",
            secondaryLabel = "Package fact unavailable",
            searchFields = mapOf(WorkspaceGraphSearchTarget.PACKAGE to listOf("Unknown package")),
        )
}

private fun GraphFileRow.searchFields(): Map<WorkspaceGraphSearchTarget, List<String>> {
    val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return buildMap {
        put(WorkspaceGraphSearchTarget.FILE, listOf(path))
        if (extension.isNotEmpty()) put(WorkspaceGraphSearchTarget.FILE_EXTENSION, listOf(extension))
    }
}

private fun GraphSymbolRow.searchFields(): Map<WorkspaceGraphSearchTarget, List<String>> {
    val fields = listOf(name, qualifiedName)
    return buildMap {
        put(WorkspaceGraphSearchTarget.SYMBOL, fields)
        when (kind) {
            SymbolKind.CLASS,
            SymbolKind.DATA_CLASS,
            -> put(WorkspaceGraphSearchTarget.CLASS, fields)

            SymbolKind.FUNCTION -> put(WorkspaceGraphSearchTarget.METHOD, fields)
            SymbolKind.INTERFACE,
            SymbolKind.OBJECT,
            SymbolKind.ENUM,
            SymbolKind.PROPERTY,
            -> Unit
        }
    }
}

private fun SymbolKind.nodeKind(): WorkspaceGraphNodeKind =
    when (this) {
        SymbolKind.CLASS,
        SymbolKind.DATA_CLASS,
        SymbolKind.INTERFACE,
        SymbolKind.OBJECT,
        SymbolKind.ENUM,
        -> WorkspaceGraphNodeKind.TYPE

        SymbolKind.FUNCTION,
        SymbolKind.PROPERTY,
        -> WorkspaceGraphNodeKind.MEMBER
    }

private fun SymbolKind.declarationKinds(): Set<WorkspaceGraphDeclarationKind> =
    when (this) {
        SymbolKind.CLASS,
        SymbolKind.DATA_CLASS,
        -> setOf(WorkspaceGraphDeclarationKind.CLASS)

        SymbolKind.INTERFACE -> setOf(WorkspaceGraphDeclarationKind.INTERFACE)
        SymbolKind.OBJECT -> setOf(WorkspaceGraphDeclarationKind.OBJECT)
        SymbolKind.ENUM -> setOf(WorkspaceGraphDeclarationKind.ENUM)
        SymbolKind.FUNCTION ->
            setOf(
                WorkspaceGraphDeclarationKind.FUNCTION,
                WorkspaceGraphDeclarationKind.METHOD,
            )

        SymbolKind.PROPERTY -> setOf(WorkspaceGraphDeclarationKind.PROPERTY)
    }
