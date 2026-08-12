@file:Suppress("TooManyFunctions")

package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolIdentity
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Renders deterministic, filesystem-safe Markdown documents for important workspace symbols. */
internal class WorkspaceRelationshipsRenderer {
    fun render(report: WorkspaceReport): RenderedWorkspaceRelationships {
        require(report.importantSymbols.distinctBy { it.symbol.identity }.size == report.importantSymbols.size) {
            "importantSymbols must have distinct workspace identities"
        }
        val importantSymbols = report.importantSymbols.sortedWith(IMPORTANT_SYMBOL_COMPARATOR)
        val fileNames = importantSymbols.associate { it.symbol.identity to pageFileName(it.symbol) }
        require(fileNames.values.distinct().size == fileNames.size) {
            "important symbol page filenames must be unique"
        }
        val usageRelationships =
            report.workspaceIndex.relationships
                .filterNot { it.kind == WorkspaceRelationshipKind.IMPORT }
                .sortedWith(RELATIONSHIP_COMPARATOR)
        val pages =
            importantSymbols
                .map { importantSymbol ->
                    val identity = importantSymbol.symbol.identity
                    val usage =
                        WorkspaceSymbolUsage(
                            symbol = importantSymbol.symbol,
                            incoming = usageRelationships.filter { it.targetIdentity == identity },
                            outgoing = usageRelationships.filter { it.sourceIdentity == identity },
                        )
                    RelationshipPage(
                        importantSymbol = importantSymbol,
                        usage = usage,
                        fileName = fileNames.getValue(identity),
                    )
                }
        return RenderedWorkspaceRelationships(
            indexMarkdown = renderIndex(report.name, pages),
            pages =
                pages
                    .map { page ->
                        RenderedRelationshipPage(
                            fileName = page.fileName,
                            symbolIdentity = page.importantSymbol.symbol.identity.value,
                            markdown = renderPage(page, fileNames),
                        )
                    }.sortedBy { it.fileName },
            contextSectionMarkdown = renderContextSection(pages),
        )
    }

    private fun renderIndex(
        workspaceName: String,
        pages: List<RelationshipPage>,
    ): String =
        buildString {
            appendLine("# Important workspace relationships")
            appendLine()
            appendLine(
                "Important symbols selected for ${workspaceName.markdownText()}. " +
                    "Rows are ordered by descending importance score, then stable workspace identity.",
            )
            appendLine()
            if (pages.isEmpty()) {
                appendLine("No important symbols were selected for this workspace.")
                appendLine()
            } else {
                appendLine(
                    "| Symbol | Kind | Scope | Score | Local in | Workspace in | Cross-build in | Workspace-used |",
                )
                appendLine(
                    "|--------|------|-------|------:|---------:|-------------:|---------------:|----------------|",
                )
                pages.forEach { page ->
                    val symbol = page.importantSymbol.symbol
                    val status = if (page.usage.isWorkspaceUsed) "observed" else "not observed"
                    appendLine(
                        "| [${symbol.qualifiedName.markdownText()}](${page.fileName}) | " +
                            "${symbol.kind.label.markdownText()} | ${scope(symbol)} | " +
                            "${page.importantSymbol.score} | " +
                            "${page.usage.localInbound} | ${page.usage.workspaceInbound} | " +
                            "${page.usage.crossBuildInbound} | $status |",
                    )
                }
                appendLine()
            }
            appendLine("## Evidence model and limits")
            appendLine()
            appendLine("- **DIRECT**: the extractor supplied direct source evidence for the resolved target.")
            appendLine(
                "- **DERIVED**: the target was resolved from deterministic source facts, " +
                    "such as an unambiguous import.",
            )
            appendLine(
                "- **HEURISTIC**: the relationship relies on approximate name or syntax evidence " +
                    "and should be reviewed.",
            )
            appendLine(
                "- Import facts do not count as usage and are excluded from inbound, outbound, " +
                    "and workspace-used results.",
            )
            appendLine(
                "- Counts cover resolved, observed workspace relationships only. " +
                    "No resolved workspace usage is not proof " +
                    "of semantic unusedness.",
            )
            appendLine(
                "- Local inbound means the same build and project. Workspace inbound includes all projects " +
                    "and builds; " +
                    "cross-build inbound has a consumer in another build.",
            )
        }

    private fun renderPage(
        page: RelationshipPage,
        fileNames: Map<WorkspaceSymbolIdentity, String>,
    ): String =
        buildString {
            val importantSymbol = page.importantSymbol
            val symbol = importantSymbol.symbol
            appendLine("# ${symbol.qualifiedName.markdownText()}")
            appendLine()
            appendIdentity(symbol)
            appendImportance(importantSymbol)
            appendUsage(page.usage)
            appendRelationships("Who uses it", "Consumer", page.usage.incoming, fileNames, inbound = true)
            appendRelationships("What it uses", "Dependency", page.usage.outgoing, fileNames, inbound = false)
            appendCrossBuildEdges(page.usage, fileNames)
        }

    private fun StringBuilder.appendIdentity(symbol: WorkspaceSymbol) {
        appendLine("## Identity")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|-------|-------|")
        appendLine("| Workspace identity | ${symbol.identity.value.markdownCode()} |")
        appendLine("| Kind | ${symbol.kind.label.markdownText()} |")
        appendLine("| Build | ${symbol.build.markdownCode()} |")
        appendLine("| Project | ${symbol.project.markdownCode()} |")
        appendLine("| Source set | ${symbol.sourceSet.markdownCode()} |")
        appendLine("| Project-relative file | ${symbol.projectRelativeFile.markdownCode()} |")
        appendLine("| Declaration line | ${symbol.declarationLine} |")
        appendLine()
    }

    private fun StringBuilder.appendImportance(importantSymbol: ImportantSymbol) {
        val reasons =
            importantSymbol.reasons
                .sortedBy { it.ordinal }
                .joinToString("; ") { it.label.markdownText() }
        appendLine("## Importance")
        appendLine()
        appendLine("| Score | Reasons |")
        appendLine("|------:|---------|")
        appendLine("| ${importantSymbol.score} | $reasons |")
        appendLine()
    }

    private fun StringBuilder.appendUsage(usage: WorkspaceSymbolUsage) {
        val status =
            if (usage.isWorkspaceUsed) {
                "yes - resolved workspace usage observed"
            } else {
                "no resolved workspace usage observed; not proof of semantic unusedness"
            }
        appendLine("## Usage")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|--------|------:|")
        appendLine("| Local inbound | ${usage.localInbound} |")
        appendLine("| Workspace inbound | ${usage.workspaceInbound} |")
        appendLine("| Cross-build inbound | ${usage.crossBuildInbound} |")
        appendLine("| Workspace-used status | ${status.markdownText()} |")
        appendLine()
        when {
            !usage.isWorkspaceUsed ->
                appendLine(
                    "No resolved workspace usage was observed. This is not proof that the declaration is " +
                        "semantically unused.",
                )
            usage.localInbound == 0 ->
                appendLine(
                    "No local inbound relationship was resolved, but consumers were observed " +
                        "elsewhere in the workspace.",
                )
            else -> appendLine("Resolved non-import workspace usage was observed.")
        }
        appendLine()
    }

    private fun StringBuilder.appendRelationships(
        title: String,
        endpointLabel: String,
        relationships: List<WorkspaceRelationship>,
        fileNames: Map<WorkspaceSymbolIdentity, String>,
        inbound: Boolean,
    ) {
        appendLine("## $title")
        appendLine()
        if (relationships.isEmpty()) {
            appendLine(
                if (inbound) "No resolved consumers were observed." else "No resolved dependencies were observed.",
            )
            appendLine()
            return
        }
        appendLine(
            "| $endpointLabel | Relationship kind | Cross-build | Evidence | Evidence build | Evidence project | " +
                "Evidence file | Line | Context |",
        )
        appendLine(
            "|----------|--------------|-------------|----------|----------------|------------------|" +
                "---------------|-----:|---------|",
        )
        relationships.sortedWith(RELATIONSHIP_COMPARATOR).forEach { relationship ->
            val endpoint = if (inbound) relationship.source else relationship.target
            appendLine(
                "| ${endpointCell(endpoint, relationship, fileNames)} | ${relationship.kind.label.markdownText()} | " +
                    "${crossBuildCell(relationship)} | ${relationship.evidence.name} | " +
                    "${relationship.sourceEvidence.build.markdownCode()} | " +
                    "${relationship.sourceEvidence.project.markdownCode()} | " +
                    "${relationship.sourceEvidence.projectRelativeFile.markdownCode()} | " +
                    "${relationship.sourceEvidence.line} | ${relationship.sourceEvidence.context.markdownText()} |",
            )
        }
        appendLine()
    }

    private fun StringBuilder.appendCrossBuildEdges(
        usage: WorkspaceSymbolUsage,
        fileNames: Map<WorkspaceSymbolIdentity, String>,
    ) {
        val directedRelationships =
            usage.incoming.map { DirectedRelationship("inbound", it) } +
                usage.outgoing.map { DirectedRelationship("outbound", it) }
        val edges =
            directedRelationships
                .filter { isCrossBuild(it.relationship) }
                .sortedWith(DIRECTED_RELATIONSHIP_COMPARATOR)
        appendLine("## Cross-build edges")
        appendLine()
        if (edges.isEmpty()) {
            appendLine("No resolved cross-build relationships were observed for this symbol.")
            appendLine()
            return
        }
        appendLine("| Direction | From | Relationship kind | To | Evidence | Evidence location | Context |")
        appendLine("|-----------|------|--------------|----|----------|-------------------|---------|")
        edges.forEach { edge ->
            val relationship = edge.relationship
            appendLine(
                "| ${edge.direction} | ${endpointCell(relationship.source, relationship, fileNames)} | " +
                    "${relationship.kind.label.markdownText()} | " +
                    "${endpointCell(relationship.target, relationship, fileNames)} | ${relationship.evidence.name} | " +
                    "${evidenceLocation(relationship)} | ${relationship.sourceEvidence.context.markdownText()} |",
            )
        }
        appendLine()
    }

    private fun renderContextSection(pages: List<RelationshipPage>): String =
        buildString {
            appendLine("## Important workspace relationships")
            appendLine()
            appendLine("[Browse the complete relationship index](relationships/index.md).")
            appendLine()
            if (pages.isEmpty()) {
                appendLine("No important workspace symbols were selected.")
            } else {
                pages.take(CONTEXT_LINK_LIMIT).forEach { page ->
                    val importantSymbol = page.importantSymbol
                    appendLine(
                        "- [${importantSymbol.symbol.qualifiedName.markdownText()}]" +
                            "(relationships/${page.fileName}) - score ${importantSymbol.score}; " +
                            "workspace inbound ${page.usage.workspaceInbound}; " +
                            "cross-build inbound ${page.usage.crossBuildInbound}",
                    )
                }
            }
            appendLine()
        }

    private fun pageFileName(symbol: WorkspaceSymbol): String {
        val slug =
            symbol.qualifiedName
                .lowercase(Locale.ROOT)
                .replace(NON_SLUG_CHARACTER, "-")
                .trim('-')
                .take(MAX_SLUG_LENGTH)
                .trimEnd('-')
                .ifBlank { "symbol" }
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(symbol.identity.value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and BYTE_MASK) }
                .take(HASH_LENGTH)
        return "$slug-$digest.md"
    }

    private data class RelationshipPage(
        val importantSymbol: ImportantSymbol,
        val usage: WorkspaceSymbolUsage,
        val fileName: String,
    )

    private data class DirectedRelationship(
        val direction: String,
        val relationship: WorkspaceRelationship,
    )

    /** Fully rendered root relationship subtree and compact root-context section. */
    data class RenderedWorkspaceRelationships(
        val indexMarkdown: String,
        val pages: List<RenderedRelationshipPage>,
        val contextSectionMarkdown: String,
    )

    /** One collision-safe Markdown page in the relationship subtree. */
    data class RenderedRelationshipPage(
        val fileName: String,
        val symbolIdentity: String,
        val markdown: String,
    )

    companion object {
        internal const val CONTEXT_LINK_LIMIT = 8
        private const val MAX_SLUG_LENGTH = 72
        private const val HASH_LENGTH = 12
        private const val BYTE_MASK = 0xff
        private val NON_SLUG_CHARACTER = Regex("[^a-z0-9]+")
        private val IMPORTANT_SYMBOL_COMPARATOR =
            compareByDescending<ImportantSymbol> { it.score }.thenBy { it.symbol.identity.value }
        private val RELATIONSHIP_COMPARATOR =
            compareBy<WorkspaceRelationship> {
                it.source
                    ?.identity
                    ?.value
                    .orEmpty()
            }.thenBy { it.sourceEvidence.build }
                .thenBy { it.sourceEvidence.project }
                .thenBy { it.sourceEvidence.sourceSet }
                .thenBy { it.target.identity.value }
                .thenBy { it.kind.name }
                .thenBy { it.evidence.name }
                .thenBy { it.sourceEvidence.projectRelativeFile }
                .thenBy { it.sourceEvidence.line }
                .thenBy { it.sourceEvidence.context }
        private val DIRECTED_RELATIONSHIP_COMPARATOR =
            compareBy<DirectedRelationship> { it.direction }
                .thenBy(RELATIONSHIP_COMPARATOR) { it.relationship }
    }
}

private fun scope(symbol: WorkspaceSymbol): String =
    listOf(symbol.build, symbol.project, symbol.sourceSet).joinToString(" / ") { it.markdownCode() }

private fun endpointCell(
    symbol: WorkspaceSymbol?,
    relationship: WorkspaceRelationship,
    fileNames: Map<WorkspaceSymbolIdentity, String>,
): String {
    if (symbol == null) {
        val evidence = relationship.sourceEvidence
        return "unresolved declaration (${scope(evidence.build, evidence.project, evidence.sourceSet)})"
    }
    val label =
        "${symbol.qualifiedName.markdownText()} " +
            "(${scope(symbol.build, symbol.project, symbol.sourceSet)})"
    return fileNames[symbol.identity]?.let { fileName -> "[$label]($fileName)" } ?: label
}

private fun scope(
    build: String,
    project: String,
    sourceSet: String,
): String = listOf(build, project, sourceSet).joinToString(" / ") { it.markdownCode() }

private fun crossBuildCell(relationship: WorkspaceRelationship): String =
    if (isCrossBuild(relationship)) {
        "yes (${sourceBuild(relationship).markdownCode()} to ${relationship.target.build.markdownCode()})"
    } else {
        "no"
    }

private fun evidenceLocation(relationship: WorkspaceRelationship): String {
    val evidence = relationship.sourceEvidence
    return "${evidence.build.markdownCode()} / ${evidence.project.markdownCode()} / " +
        "${evidence.projectRelativeFile.markdownCode()}:${evidence.line}"
}

private fun isCrossBuild(relationship: WorkspaceRelationship): Boolean =
    sourceBuild(relationship) != relationship.target.build

private fun sourceBuild(relationship: WorkspaceRelationship): String =
    relationship.source?.build ?: relationship.sourceEvidence.build

private fun String.markdownCode(): String = "<code>${htmlText()}</code>"

private fun String.markdownText(): String =
    htmlText()
        .replace("|", "\\|")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.htmlText(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
