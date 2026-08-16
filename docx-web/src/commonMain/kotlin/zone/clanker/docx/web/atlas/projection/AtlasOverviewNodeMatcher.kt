package zone.clanker.docx.web.atlas.projection

import zone.clanker.docx.web.atlas.session.AtlasDeclarationKind
import zone.clanker.docx.web.atlas.session.AtlasFilters
import zone.clanker.docx.web.atlas.session.AtlasSearchFilter
import zone.clanker.docx.web.atlas.session.AtlasSearchTarget
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.AtlasNodeType

internal fun AtlasFilters.hasOverviewNodeFilter(declarationFiltering: Boolean): Boolean =
    search.query.isNotBlank() ||
        (declarationFiltering && declarations.toSet() != AtlasDeclarationKind.entries.toSet()) ||
        relationships.minimumCountExclusive > 0

internal fun AtlasNode.matchesOverviewDeclaration(
    filters: AtlasFilters,
    declarationFiltering: Boolean,
): Boolean =
    when {
        !declarationFiltering -> true
        filters.declarations.toSet() == AtlasDeclarationKind.entries.toSet() -> true
        type != AtlasNodeType.SYMBOL -> false
        else -> kind in filters.declarations.flatMapTo(mutableSetOf(), AtlasDeclarationKind::symbolKindNames)
    }

internal fun AtlasNode.matchesOverviewSearch(search: AtlasSearchFilter): Boolean {
    val query = search.query.trim().lowercase()
    if (query.isEmpty()) return true
    val targets = search.targets.ifEmpty { AtlasSearchTarget.entries }
    return targets.any { target -> matchesOverviewSearchTarget(target, query) }
}

private fun AtlasNode.matchesOverviewSearchTarget(
    target: AtlasSearchTarget,
    query: String,
): Boolean =
    when (target) {
        in locationSearchTargets -> matchesLocationTarget(target, query)
        in declarationSearchTargets -> matchesDeclarationTarget(target, query)
        AtlasSearchTarget.PROBLEM -> hasAnalyzerFinding && fields(name, qualifiedName, path).matches(query)
        AtlasSearchTarget.CYCLE ->
            (hasObservedCycle || hasAnalysisCycle) && fields(name, qualifiedName, path).matches(query)
        else -> false
    }

private fun AtlasNode.matchesLocationTarget(
    target: AtlasSearchTarget,
    query: String,
): Boolean =
    when (target) {
        AtlasSearchTarget.BUILD -> fields(buildId, buildName).matches(query)
        AtlasSearchTarget.PROJECT -> fields(projectId, projectPath).matches(query)
        AtlasSearchTarget.SOURCE_SET -> fields(sourceSet).matches(query)
        AtlasSearchTarget.PACKAGE -> fields(qualifiedName?.substringBeforeLast('.', ""), path).matches(query)
        AtlasSearchTarget.FILE -> type == AtlasNodeType.FILE && fields(name, path).matches(query)
        AtlasSearchTarget.FILE_EXTENSION -> type == AtlasNodeType.FILE && path.fileExtension().contains(query)
        else -> false
    }

private fun AtlasNode.matchesDeclarationTarget(
    target: AtlasSearchTarget,
    query: String,
): Boolean =
    when (target) {
        in nominalDeclarationSearchTargets -> matchesNominalDeclarationTarget(target, query)
        AtlasSearchTarget.METHOD,
        AtlasSearchTarget.FUNCTION,
        -> isSymbolKind("FUNCTION") && fields(name, qualifiedName).matches(query)
        AtlasSearchTarget.PROPERTY -> isSymbolKind("PROPERTY") && fields(name, qualifiedName).matches(query)
        AtlasSearchTarget.TYPE,
        AtlasSearchTarget.SYMBOL,
        -> type == AtlasNodeType.SYMBOL && fields(name, qualifiedName, kind).matches(query)
        else -> false
    }

private fun AtlasNode.matchesNominalDeclarationTarget(
    target: AtlasSearchTarget,
    query: String,
): Boolean =
    when (target) {
        AtlasSearchTarget.CLASS -> isSymbolKind("CLASS", "DATA_CLASS") && fields(name, qualifiedName).matches(query)
        AtlasSearchTarget.INTERFACE -> isSymbolKind("INTERFACE") && fields(name, qualifiedName).matches(query)
        AtlasSearchTarget.OBJECT -> isSymbolKind("OBJECT") && fields(name, qualifiedName).matches(query)
        AtlasSearchTarget.ENUM -> isSymbolKind("ENUM") && fields(name, qualifiedName).matches(query)
        else -> false
    }

private fun AtlasNode.isSymbolKind(vararg expected: String): Boolean =
    type == AtlasNodeType.SYMBOL && kind in expected

private fun fields(vararg values: String?): List<String> = values.filterNotNull()

private fun List<String>.matches(query: String): Boolean = any { value -> value.lowercase().contains(query) }

private fun String?.fileExtension(): String = this?.substringAfterLast('.', "").orEmpty().lowercase()

private val AtlasDeclarationKind.symbolKindNames: List<String>
    get() =
        when (this) {
            AtlasDeclarationKind.CLASS -> listOf("CLASS", "DATA_CLASS")
            AtlasDeclarationKind.INTERFACE -> listOf("INTERFACE")
            AtlasDeclarationKind.OBJECT -> listOf("OBJECT")
            AtlasDeclarationKind.ENUM -> listOf("ENUM")
            AtlasDeclarationKind.FUNCTION,
            AtlasDeclarationKind.METHOD,
            -> listOf("FUNCTION")
            AtlasDeclarationKind.PROPERTY -> listOf("PROPERTY")
        }

private val locationSearchTargets =
    setOf(
        AtlasSearchTarget.BUILD,
        AtlasSearchTarget.PROJECT,
        AtlasSearchTarget.SOURCE_SET,
        AtlasSearchTarget.PACKAGE,
        AtlasSearchTarget.FILE,
        AtlasSearchTarget.FILE_EXTENSION,
    )

private val declarationSearchTargets =
    setOf(
        AtlasSearchTarget.CLASS,
        AtlasSearchTarget.INTERFACE,
        AtlasSearchTarget.OBJECT,
        AtlasSearchTarget.ENUM,
        AtlasSearchTarget.TYPE,
        AtlasSearchTarget.METHOD,
        AtlasSearchTarget.FUNCTION,
        AtlasSearchTarget.PROPERTY,
        AtlasSearchTarget.SYMBOL,
    )

private val nominalDeclarationSearchTargets =
    setOf(
        AtlasSearchTarget.CLASS,
        AtlasSearchTarget.INTERFACE,
        AtlasSearchTarget.OBJECT,
        AtlasSearchTarget.ENUM,
    )
