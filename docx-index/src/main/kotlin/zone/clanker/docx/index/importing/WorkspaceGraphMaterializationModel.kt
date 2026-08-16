package zone.clanker.docx.index.importing

import zone.clanker.report.model.WorkspaceGraphDeclarationKind
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphRelationKind
import zone.clanker.report.model.WorkspaceGraphSearchTarget

internal data class IndexedGraphDescriptor(
    val id: String,
    val kind: WorkspaceGraphNodeKind,
    val parentId: String?,
    val depth: Int,
    val semanticLevel: Int,
    val label: String,
    val secondaryLabel: String?,
    val searchFields: Map<WorkspaceGraphSearchTarget, List<String>> = emptyMap(),
    val declarationKinds: Set<WorkspaceGraphDeclarationKind> = emptySet(),
)

internal data class IndexedGraphFact(
    val id: String,
    val kind: WorkspaceGraphRelationKind,
    val sourceId: String,
    val targetId: String,
)

internal data class IndexedWorkspaceGraph(
    val descriptors: List<IndexedGraphDescriptor>,
    val facts: List<IndexedGraphFact>,
)

internal const val GRAPH_WORKSPACE_LEVEL: Int = 0
internal const val GRAPH_BUILD_LEVEL: Int = 1
internal const val GRAPH_PROJECT_LEVEL: Int = 2
internal const val GRAPH_SOURCE_SET_LEVEL: Int = 3
internal const val GRAPH_PACKAGE_LEVEL: Int = 4
internal const val GRAPH_FILE_LEVEL: Int = 5
internal const val GRAPH_SYMBOL_LEVEL: Int = 6
internal const val GRAPH_OVERLAY_LEVEL: Int = 7
internal const val GRAPH_BUILD_DEPTH: Int = 1
internal const val GRAPH_PROJECT_DEPTH: Int = 2
internal const val GRAPH_SOURCE_SET_DEPTH: Int = 3

internal fun indexedPackageId(
    sourceSetId: String,
    qualifiedName: String,
): String = "source-package:${sourceSetId.length}:$sourceSetId:${qualifiedName.length}:$qualifiedName"

internal fun indexedUnknownPackageId(sourceSetId: String): String =
    "$UNKNOWN_PACKAGE_PREFIX${sourceSetId.length}:$sourceSetId"

internal fun isIndexedUnknownPackageId(id: String): Boolean = id.startsWith(UNKNOWN_PACKAGE_PREFIX)

internal fun packagePrefixes(packageName: String): List<String> {
    val segments = packageName.split('.')
    require(segments.all(String::isNotBlank)) { "Indexed graph package contains a blank segment: $packageName" }
    return segments.indices.map { index -> segments.take(index + 1).joinToString(".") }
}

private const val UNKNOWN_PACKAGE_PREFIX: String = "source-unknown-package:"
