package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceGraphDeclarationKind
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphSearchTarget

internal class WorkspaceSourceDetailDescriptors(
    loadedFacts: WorkspaceSourceHierarchyLoadedFacts,
    baseDescriptors: List<WorkspaceSourceHierarchyDescriptor>,
) {
    private val includedSourceSetIds =
        baseDescriptors
            .filter { descriptor -> descriptor.kind == WorkspaceGraphNodeKind.SOURCE_SET }
            .mapTo(mutableSetOf(), WorkspaceSourceHierarchyDescriptor::id)
    private val includedFilesById =
        loadedFacts.filesById.filterValues { file -> file.sourceSetId in includedSourceSetIds }
    private val includedSymbolsById =
        loadedFacts.symbolsById.filterValues { symbol -> symbol.fileId in includedFilesById }
    private val packageNameByFileId = packageNamesByFile()
    private val packageDescriptors = createPackageDescriptors()
    private val fileDescriptors = createFileDescriptors()

    fun descriptors(): List<WorkspaceSourceHierarchyDescriptor> =
        packageDescriptors + fileDescriptors + createSymbolDescriptors()

    private fun packageNamesByFile(): Map<String, String> =
        includedSymbolsById.values
            .groupBy(SymbolSnapshot::fileId)
            .mapValues { (fileId, symbols) ->
                val packageNames = symbols.map(SymbolSnapshot::packageName).distinct().sorted()
                require(packageNames.size == 1) { "Source file has conflicting package facts: $fileId" }
                packageNames.single()
            }

    private fun createPackageDescriptors(): List<WorkspaceSourceHierarchyDescriptor> {
        val observedPackages =
            packageNameByFileId.entries.flatMap { (fileId, packageName) ->
                val sourceSetId = includedFilesById.getValue(fileId).sourceSetId
                val prefixes = packagePrefixes(packageName)
                prefixes.mapIndexed { index, prefix ->
                    WorkspaceSourceHierarchyDescriptor(
                        id = workspaceSourcePackageId(sourceSetId, prefix),
                        kind = WorkspaceGraphNodeKind.PACKAGE,
                        parentId =
                            if (index == 0) {
                                sourceSetId
                            } else {
                                workspaceSourcePackageId(sourceSetId, prefixes[index - 1])
                            },
                        depth = SOURCE_SET_DEPTH + index + 1,
                        semanticLevel = PACKAGE_LEVEL,
                        label = prefix.substringAfterLast('.'),
                        secondaryLabel = prefix,
                        searchFields = mapOf(WorkspaceGraphSearchTarget.PACKAGE to listOf(prefix)),
                    )
                }
            }
        val unknownPackages =
            includedFilesById.values
                .filter { file -> file.id !in packageNameByFileId }
                .map(SourceFileSnapshot::sourceSetId)
                .distinct()
                .map(::unknownPackageDescriptor)
        return (observedPackages + unknownPackages)
            .distinctBy(WorkspaceSourceHierarchyDescriptor::id)
            .sortedBy(WorkspaceSourceHierarchyDescriptor::id)
    }

    private fun createFileDescriptors(): List<WorkspaceSourceHierarchyDescriptor> =
        includedFilesById.values
            .sortedBy(SourceFileSnapshot::id)
            .map { file ->
                val packageName = packageNameByFileId[file.id]
                val packageDepth = packageName?.let(::packagePrefixes)?.size ?: 1
                WorkspaceSourceHierarchyDescriptor(
                    id = file.id,
                    kind = WorkspaceGraphNodeKind.FILE,
                    parentId =
                        packageName?.let { name -> workspaceSourcePackageId(file.sourceSetId, name) }
                            ?: workspaceUnknownSourcePackageId(file.sourceSetId),
                    depth = SOURCE_SET_DEPTH + packageDepth + 1,
                    semanticLevel = FILE_LEVEL,
                    label = file.projectRelativePath.substringAfterLast('/'),
                    secondaryLabel = file.projectRelativePath,
                    searchFields = file.searchFields(),
                )
            }

    private fun createSymbolDescriptors(): List<WorkspaceSourceHierarchyDescriptor> {
        val fileDepths = fileDescriptors.associate { file -> file.id to file.depth }
        return includedSymbolsById.values
            .sortedBy(SymbolSnapshot::id)
            .map { symbol ->
                WorkspaceSourceHierarchyDescriptor(
                    id = symbol.id,
                    kind = symbol.kind.graphNodeKind(),
                    parentId = symbol.fileId,
                    depth = fileDepths.getValue(symbol.fileId) + 1,
                    semanticLevel = SYMBOL_LEVEL,
                    label = symbol.name,
                    secondaryLabel = symbol.qualifiedName,
                    searchFields = symbol.searchFields(),
                    declarationKinds = symbol.kind.declarationKinds(),
                )
            }
    }

    private fun unknownPackageDescriptor(sourceSetId: String): WorkspaceSourceHierarchyDescriptor =
        WorkspaceSourceHierarchyDescriptor(
            id = workspaceUnknownSourcePackageId(sourceSetId),
            kind = WorkspaceGraphNodeKind.PACKAGE,
            parentId = sourceSetId,
            depth = SOURCE_SET_DEPTH + 1,
            semanticLevel = PACKAGE_LEVEL,
            label = UNKNOWN_PACKAGE_LABEL,
            secondaryLabel = UNKNOWN_PACKAGE_SECONDARY_LABEL,
            searchFields = mapOf(WorkspaceGraphSearchTarget.PACKAGE to listOf(UNKNOWN_PACKAGE_LABEL)),
        )
}

internal fun workspaceSourcePackageId(
    sourceSetId: String,
    qualifiedName: String,
): String = "source-package:${sourceSetId.length}:$sourceSetId:${qualifiedName.length}:$qualifiedName"

internal fun workspaceUnknownSourcePackageId(sourceSetId: String): String =
    "$UNKNOWN_PACKAGE_ID_PREFIX${sourceSetId.length}:$sourceSetId"

internal fun isWorkspaceUnknownSourcePackageId(id: String): Boolean = id.startsWith(UNKNOWN_PACKAGE_ID_PREFIX)

private const val UNKNOWN_PACKAGE_ID_PREFIX: String = "source-unknown-package:"
private const val UNKNOWN_PACKAGE_LABEL: String = "Unknown package"
private const val UNKNOWN_PACKAGE_SECONDARY_LABEL: String = "Package fact unavailable"

private fun packagePrefixes(packageName: String): List<String> {
    val segments = packageName.split('.')
    require(segments.all { segment -> segment.isNotBlank() }) {
        "Source package must use non-blank segments: $packageName"
    }
    return segments.indices.map { index -> segments.take(index + 1).joinToString(".") }
}

private fun SymbolKind.graphNodeKind(): WorkspaceGraphNodeKind =
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

private fun SourceFileSnapshot.searchFields(): Map<WorkspaceGraphSearchTarget, List<String>> {
    val extension = projectRelativePath.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return buildMap {
        put(WorkspaceGraphSearchTarget.FILE, listOf(projectRelativePath))
        if (extension.isNotEmpty()) put(WorkspaceGraphSearchTarget.FILE_EXTENSION, listOf(extension))
    }
}

private fun SymbolSnapshot.searchFields(): Map<WorkspaceGraphSearchTarget, List<String>> {
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
