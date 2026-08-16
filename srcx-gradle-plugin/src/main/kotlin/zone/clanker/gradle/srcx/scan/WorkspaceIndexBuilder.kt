package zone.clanker.gradle.srcx.scan

import zone.clanker.gradle.srcx.model.Reference
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage

/** Builds a cumulative workspace index without filesystem or Gradle access. */
@Suppress("TooManyFunctions")
object WorkspaceIndexBuilder {
    internal fun build(scans: List<ProjectScan>): WorkspaceIndex {
        val symbols = collectSymbols(scans)
        val symbolLookup = symbolLookup(symbols)
        val references = collectReferences(scans, symbolLookup)
        val relationships = resolveRelationships(references, symbolLookup)
        val usages = calculateUsages(symbols.map { it.symbol }, relationships)
        return WorkspaceIndex(
            symbols = symbols.map { it.symbol },
            references = references.map { it.reference },
            relationships = relationships,
            usages = usages,
        )
    }

    private fun collectSymbols(scans: List<ProjectScan>): List<IndexedSymbol> =
        scans
            .sortedWith(compareBy<ProjectScan> { it.build }.thenBy { it.projectPath.value })
            .flatMap { scan ->
                scan.files
                    .sortedWith(compareBy<ProjectFileScan> { it.sourceSet.value }.thenBy { it.projectRelativeFile })
                    .flatMap { file ->
                        file.declarations
                            .sortedWith(
                                compareBy<zone.clanker.gradle.srcx.model.Symbol> { it.line }
                                    .thenBy { it.qualifiedName }
                                    .thenBy { it.kind.name },
                            ).map { declaration ->
                                IndexedSymbol(
                                    symbol =
                                        WorkspaceSymbol(
                                            build = scan.build,
                                            project = scan.projectPath.value,
                                            sourceSet = file.sourceSet.value,
                                            name = declaration.name.substringAfterLast('.'),
                                            qualifiedName = declaration.qualifiedName,
                                            kind = declaration.kind,
                                            projectRelativeFile = file.projectRelativeFile,
                                            declarationLine = declaration.line,
                                            declarationSemantic = declaration.declarationSemantic,
                                            signature = declaration.signature,
                                            ownerQualifiedName = declaration.ownerQualifiedName,
                                            declarationRange = declaration.declarationRange,
                                        ),
                                    packageName = declaration.packageName,
                                )
                            }
                    }
            }.distinctBy { indexed -> indexed.symbol.identity }
            .sortedBy { indexed -> indexed.symbol.identity.value }

    private fun collectReferences(
        scans: List<ProjectScan>,
        symbolLookup: SymbolLookup,
    ): List<IndexedReference> =
        scans
            .sortedWith(compareBy<ProjectScan> { it.build }.thenBy { it.projectPath.value })
            .flatMap { scan ->
                scan.files.flatMap { file ->
                    val importedReferences =
                        file.references
                            .filter { it.kind == ReferenceKind.IMPORT }
                            .mapNotNull { reference ->
                                reference.targetQualifiedName?.let { reference.targetName to it }
                            }
                    val imports =
                        importedReferences
                            .groupBy({ it.first }, { it.second })
                            .mapNotNull { (name, targets) -> targets.distinct().singleOrNull()?.let { name to it } }
                            .toMap()
                    val filePackage =
                        file.declarations
                            .map { it.packageName }
                            .distinct()
                            .singleOrNull()
                    file.references.map { reference ->
                        val importedTarget = reference.targetQualifiedName ?: imports[reference.targetName]
                        val source = findSourceSymbol(scan, file, reference, symbolLookup)
                        val sourcePackage = source?.packageName ?: filePackage
                        IndexedReference(
                            reference =
                                WorkspaceReference(
                                    build = scan.build,
                                    project = scan.projectPath.value,
                                    sourceSet = file.sourceSet.value,
                                    sourceSymbol = source?.symbol,
                                    targetName = reference.targetName,
                                    targetQualifiedName = importedTarget,
                                    kind = reference.kind,
                                    projectRelativeFile = file.projectRelativeFile,
                                    line = reference.line,
                                    context = reference.context,
                                    evidence = effectiveEvidence(reference, importedTarget),
                                    occurrenceRange = reference.occurrenceRange,
                                ),
                            sourcePackage = sourcePackage,
                        )
                    }
                }
            }.sortedWith(referenceComparator())

    @Suppress("ReturnCount")
    private fun findSourceSymbol(
        scan: ProjectScan,
        file: ProjectFileScan,
        reference: Reference,
        symbolLookup: SymbolLookup,
    ): IndexedSymbol? {
        val qualifiedName = reference.sourceQualifiedName ?: return null
        val candidates =
            symbolLookup.bySource[
                SymbolSourceKey(
                    build = scan.build,
                    project = scan.projectPath.value,
                    sourceSet = file.sourceSet.value,
                    qualifiedName = qualifiedName,
                ),
            ].orEmpty()
        val sameFile = candidates.filter { it.symbol.projectRelativeFile == file.projectRelativeFile }
        sameFile.singleOrNull()?.let { return it }
        candidates.singleOrNull()?.let { return it }
        val preceding = sameFile.filter { it.symbol.declarationLine <= reference.line }
        val nearestLine = preceding.maxOfOrNull { it.symbol.declarationLine }
        return preceding.filter { it.symbol.declarationLine == nearestLine }.singleOrNull()
    }

    private fun effectiveEvidence(reference: Reference, targetQualifiedName: String?): ReferenceEvidence =
        when {
            reference.evidence == ReferenceEvidence.HEURISTIC -> ReferenceEvidence.HEURISTIC
            reference.targetQualifiedName == null && targetQualifiedName != null -> ReferenceEvidence.DERIVED
            else -> reference.evidence
        }

    private fun resolveRelationships(
        references: List<IndexedReference>,
        symbolLookup: SymbolLookup,
    ): List<WorkspaceRelationship> {
        val resolved =
            references.mapNotNull { indexed ->
                val reference = indexed.reference
                val resolution = resolveTarget(indexed, symbolLookup) ?: return@mapNotNull null
                if (reference.kind != ReferenceKind.IMPORT && reference.sourceSymbol == null) return@mapNotNull null
                WorkspaceRelationship(
                    source = reference.sourceSymbol.takeUnless { reference.kind == ReferenceKind.IMPORT },
                    target = resolution.symbol.symbol,
                    kind = relationshipKind(reference.kind, reference.sourceSymbol, resolution.symbol.symbol),
                    sourceEvidence = reference,
                    evidence = resolution.evidence,
                )
            }
        return deduplicateRelationships(resolved).sortedWith(relationshipComparator())
    }

    private fun resolveTarget(
        reference: IndexedReference,
        symbolLookup: SymbolLookup,
    ): TargetResolution? {
        val fact = reference.reference
        val qualifiedName = fact.targetQualifiedName
        val candidates =
            if (qualifiedName != null) {
                symbolLookup.byQualifiedName[qualifiedName].orEmpty()
            } else {
                symbolLookup.bySimpleName[fact.targetName].orEmpty()
            }.filter { isCompatible(fact.kind, it.symbol.kind) }
        val target = selectOwnerAware(candidates, reference) ?: return null
        val evidence =
            when {
                fact.evidence == ReferenceEvidence.HEURISTIC -> ReferenceEvidence.HEURISTIC
                qualifiedName != null -> fact.evidence
                else -> ReferenceEvidence.DERIVED
            }
        return TargetResolution(target, evidence)
    }

    private fun selectOwnerAware(
        candidates: List<IndexedSymbol>,
        reference: IndexedReference,
    ): IndexedSymbol? {
        val distinct = candidates.distinctBy { it.symbol.identity }
        val fact = reference.reference
        val sameProject = distinct.filter { it.symbol.build == fact.build && it.symbol.project == fact.project }
        val sameBuild = distinct.filter { it.symbol.build == fact.build }
        val sourcePackage = reference.sourcePackage
        val localScopes =
            listOfNotNull(
                sourcePackage?.let { packageName -> sameProject.filter { it.packageName == packageName } },
                sameProject,
                sourcePackage?.let { packageName -> sameBuild.filter { it.packageName == packageName } },
                sameBuild,
            )
        val workspaceScopes = if (fact.targetQualifiedName == null) emptyList() else listOf(distinct)
        val scopes = localScopes + workspaceScopes
        return scopes.asSequence().mapNotNull { it.singleOrNull() }.firstOrNull()
    }

    private fun isCompatible(referenceKind: ReferenceKind, symbolKind: SymbolDetailKind): Boolean =
        when (referenceKind) {
            ReferenceKind.CALL -> symbolKind == SymbolDetailKind.FUNCTION
            ReferenceKind.CONSTRUCTOR,
            ReferenceKind.SUPERTYPE,
            ReferenceKind.TYPE_REF,
            ReferenceKind.PROPERTY_TYPE,
            ReferenceKind.PARAMETER_TYPE,
            ReferenceKind.RETURN_TYPE,
            -> symbolKind in TYPE_KINDS
            ReferenceKind.IMPORT,
            ReferenceKind.NAME_REF,
            -> true
        }

    private fun relationshipKind(
        referenceKind: ReferenceKind,
        source: WorkspaceSymbol?,
        target: WorkspaceSymbol,
    ): WorkspaceRelationshipKind =
        when (referenceKind) {
            ReferenceKind.IMPORT -> WorkspaceRelationshipKind.IMPORT
            ReferenceKind.CALL -> WorkspaceRelationshipKind.CALL
            ReferenceKind.CONSTRUCTOR -> WorkspaceRelationshipKind.CONSTRUCTOR
            ReferenceKind.NAME_REF -> WorkspaceRelationshipKind.NAME_REFERENCE
            ReferenceKind.TYPE_REF -> WorkspaceRelationshipKind.TYPE_REFERENCE
            ReferenceKind.PROPERTY_TYPE -> WorkspaceRelationshipKind.PROPERTY_TYPE
            ReferenceKind.PARAMETER_TYPE -> WorkspaceRelationshipKind.PARAMETER_TYPE
            ReferenceKind.RETURN_TYPE -> WorkspaceRelationshipKind.RETURN_TYPE
            ReferenceKind.SUPERTYPE ->
                when {
                    source?.kind == SymbolDetailKind.INTERFACE -> WorkspaceRelationshipKind.EXTENDS
                    target.kind == SymbolDetailKind.INTERFACE -> WorkspaceRelationshipKind.IMPLEMENTS
                    else -> WorkspaceRelationshipKind.EXTENDS
                }
        }

    private fun deduplicateRelationships(relationships: List<WorkspaceRelationship>): List<WorkspaceRelationship> =
        relationships
            .groupBy { relationship ->
                val evidence = relationship.sourceEvidence
                val importOwner =
                    listOf(evidence.build, evidence.project, evidence.sourceSet, evidence.projectRelativeFile)
                        .joinToString("::")
                val source = relationship.sourceIdentity?.value ?: importOwner
                "$source->${relationship.targetIdentity.value}@${evidence.line}"
            }.values
            .map { overlaps ->
                overlaps.maxWith(
                    compareBy<WorkspaceRelationship> { relationshipSpecificity(it.kind) }
                        .thenBy { evidenceSpecificity(it.evidence) }
                        .thenBy { it.kind.name },
                )
            }

    private fun calculateUsages(
        symbols: List<WorkspaceSymbol>,
        relationships: List<WorkspaceRelationship>,
    ): List<WorkspaceSymbolUsage> {
        val usageRelationships = relationships.filter { it.kind != WorkspaceRelationshipKind.IMPORT }
        val incomingBySymbol = usageRelationships.groupBy(WorkspaceRelationship::targetIdentity)
        val outgoingBySymbol = usageRelationships.groupBy(WorkspaceRelationship::sourceIdentity)
        return symbols.map { symbol ->
            WorkspaceSymbolUsage(
                symbol = symbol,
                incoming = incomingBySymbol[symbol.identity].orEmpty(),
                outgoing = outgoingBySymbol[symbol.identity].orEmpty(),
            )
        }
    }

    private fun symbolLookup(symbols: List<IndexedSymbol>): SymbolLookup =
        SymbolLookup(
            bySource =
                symbols.groupBy { indexed ->
                    val symbol = indexed.symbol
                    SymbolSourceKey(
                        build = symbol.build,
                        project = symbol.project,
                        sourceSet = symbol.sourceSet,
                        qualifiedName = symbol.qualifiedName,
                    )
                },
            byQualifiedName = symbols.groupBy { it.symbol.qualifiedName },
            bySimpleName = symbols.groupBy { it.symbol.name },
        )

    private fun referenceComparator(): Comparator<IndexedReference> =
        compareBy<IndexedReference> { it.reference.build }
            .thenBy { it.reference.project }
            .thenBy { it.reference.sourceSet }
            .thenBy { it.reference.projectRelativeFile }
            .thenBy { it.reference.line }
            .thenBy { it.reference.kind.name }
            .thenBy { it.reference.targetQualifiedName.orEmpty() }
            .thenBy { it.reference.targetName }
            .thenBy { it.reference.context }

    private fun relationshipComparator(): Comparator<WorkspaceRelationship> =
        compareBy<WorkspaceRelationship> { it.sourceIdentity?.value.orEmpty() }
            .thenBy { it.targetIdentity.value }
            .thenBy { it.sourceEvidence.projectRelativeFile }
            .thenBy { it.sourceEvidence.line }
            .thenBy { it.kind.name }

    @Suppress("MagicNumber")
    private fun relationshipSpecificity(kind: WorkspaceRelationshipKind): Int =
        when (kind) {
            WorkspaceRelationshipKind.EXTENDS,
            WorkspaceRelationshipKind.IMPLEMENTS,
            -> 9
            WorkspaceRelationshipKind.CONSTRUCTOR -> 8
            WorkspaceRelationshipKind.PROPERTY_TYPE,
            WorkspaceRelationshipKind.PARAMETER_TYPE,
            WorkspaceRelationshipKind.RETURN_TYPE,
            -> 7
            WorkspaceRelationshipKind.CALL -> 6
            WorkspaceRelationshipKind.TYPE_REFERENCE -> 5
            WorkspaceRelationshipKind.NAME_REFERENCE -> 4
            WorkspaceRelationshipKind.IMPORT -> 3
        }

    @Suppress("MagicNumber")
    private fun evidenceSpecificity(evidence: ReferenceEvidence): Int =
        when (evidence) {
            ReferenceEvidence.DIRECT -> 3
            ReferenceEvidence.DERIVED -> 2
            ReferenceEvidence.HEURISTIC -> 1
        }

    private val TYPE_KINDS =
        setOf(
            SymbolDetailKind.CLASS,
            SymbolDetailKind.INTERFACE,
            SymbolDetailKind.ENUM,
            SymbolDetailKind.DATA_CLASS,
            SymbolDetailKind.OBJECT,
        )
}

private data class IndexedSymbol(
    val symbol: WorkspaceSymbol,
    val packageName: String,
)

private data class IndexedReference(
    val reference: WorkspaceReference,
    val sourcePackage: String?,
)

private data class SymbolSourceKey(
    val build: String,
    val project: String,
    val sourceSet: String,
    val qualifiedName: String,
)

private data class SymbolLookup(
    val bySource: Map<SymbolSourceKey, List<IndexedSymbol>>,
    val byQualifiedName: Map<String, List<IndexedSymbol>>,
    val bySimpleName: Map<String, List<IndexedSymbol>>,
)

private data class TargetResolution(
    val symbol: IndexedSymbol,
    val evidence: ReferenceEvidence,
)
