package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphSearchTarget

internal fun WorkspaceGraphFilter.matchesNode(record: WorkspaceSourceHierarchyRecord): Boolean {
    val normalizedQuery = query.trim().lowercase()
    return when {
        nodeKinds.isNotEmpty() && record.kind !in nodeKinds -> false
        declarationKinds.isNotEmpty() &&
            record.kind in DECLARATION_FILTERED_NODE_KINDS &&
            record.declarationKinds.none(declarationKinds::contains) -> false

        normalizedQuery.isEmpty() -> true
        searchTargets.isEmpty() -> record.matchesUntyped(normalizedQuery)
        else ->
            searchTargets.any { target ->
                val expected = normalizedQuery.expectedValue(target)
                record.searchFields[target].orEmpty().any { candidate -> target.matches(expected, candidate) }
            }
    }
}

private val DECLARATION_FILTERED_NODE_KINDS =
    setOf(
        WorkspaceGraphNodeKind.TYPE,
        WorkspaceGraphNodeKind.MEMBER,
        WorkspaceGraphNodeKind.PROBLEM,
        WorkspaceGraphNodeKind.CYCLE,
    )

private fun WorkspaceSourceHierarchyRecord.matchesUntyped(query: String): Boolean =
    sequenceOf(id, label, secondaryLabel.orEmpty())
        .plus(searchFields.values.flatten())
        .any { candidate -> query in candidate.lowercase() }

private fun String.expectedValue(target: WorkspaceGraphSearchTarget): String =
    if (target == WorkspaceGraphSearchTarget.FILE_EXTENSION) {
        removePrefix("*").removePrefix(".")
    } else {
        this
    }

private fun WorkspaceGraphSearchTarget.matches(
    expected: String,
    candidate: String,
): Boolean =
    if (this == WorkspaceGraphSearchTarget.FILE_EXTENSION) {
        expected.isNotEmpty() && candidate.lowercase() == expected
    } else {
        expected in candidate.lowercase()
    }
