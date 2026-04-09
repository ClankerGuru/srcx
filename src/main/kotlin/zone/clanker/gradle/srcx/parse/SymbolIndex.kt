package zone.clanker.gradle.srcx.parse

import org.gradle.api.logging.Logging
import zone.clanker.gradle.srcx.model.MethodCall
import zone.clanker.gradle.srcx.model.Reference
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.Symbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import java.io.File

/**
 * Cross-referenced symbol index built from source analysis.
 *
 * Maps every declaration to its usages and vice versa, enabling
 * find-usages, call-graph, and rename operations.
 *
 * @property symbols all declarations found across the analyzed source files
 * @property references all references found across the analyzed source files
 */
class SymbolIndex(
    val symbols: List<Symbol>,
    val references: List<Reference>,
) {
    private val byQualifiedName: Map<String, Symbol> = symbols.associateBy { it.qualifiedName }
    private val bySimpleName: Map<String, List<Symbol>> = symbols.groupBy { it.name.substringAfterLast('.') }

    /**
     * Resolve a reference to its target symbol.
     * Uses qualified name from imports, then falls back to simple name match.
     */
    @Suppress("ReturnCount")
    fun resolve(ref: Reference): Symbol? {
        val byQualified = ref.targetQualifiedName?.let { byQualifiedName[it] }
        if (byQualified != null) return byQualified

        val candidates = bySimpleName[ref.targetName] ?: return null
        if (candidates.size == 1) return candidates.first()
        return resolveViaImports(ref)
    }

    private fun resolveViaImports(ref: Reference): Symbol? {
        val fileImports =
            references.filter {
                it.file == ref.file && it.kind == ReferenceKind.IMPORT
            }
        val matchingImport =
            fileImports.firstOrNull {
                it.targetName == ref.targetName && it.targetQualifiedName != null
            }
        return matchingImport?.targetQualifiedName?.let { byQualifiedName[it] }
    }

    /** Find all usages of a symbol by its qualified name. */
    fun findUsages(qualifiedName: String): List<Reference> {
        val symbol = byQualifiedName[qualifiedName] ?: return emptyList()
        val simpleName = symbol.name.substringAfterLast('.')
        return references.filter { ref ->
            if (ref.file == symbol.file && ref.line == symbol.line) return@filter false
            when {
                ref.targetQualifiedName == qualifiedName -> true
                ref.targetName == simpleName -> resolve(ref)?.qualifiedName == qualifiedName
                else -> false
            }
        }
    }

    /** Find all usages of a symbol by simple name (matches any with that name). */
    fun findUsagesByName(name: String): List<Pair<Symbol, List<Reference>>> {
        val matchingSymbols =
            symbols.filter {
                it.name.substringAfterLast('.') == name || it.name == name || it.qualifiedName == name
            }
        return matchingSymbols.map { sym -> sym to findUsages(sym.qualifiedName) }
    }

    /** Get all symbols in a specific file. */
    fun symbolsInFile(file: File): List<Symbol> =
        symbols.filter { it.file.absolutePath == file.absolutePath }

    /** Get usage count for each type symbol, sorted descending. */
    fun usageCounts(): List<Pair<Symbol, Int>> =
        symbols
            .filter {
                it.kind in
                    setOf(
                        SymbolDetailKind.CLASS,
                        SymbolDetailKind.INTERFACE,
                        SymbolDetailKind.DATA_CLASS,
                        SymbolDetailKind.ENUM,
                        SymbolDetailKind.OBJECT,
                    )
            }.map { it to findUsages(it.qualifiedName).size }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

    /** Build a method-level call graph. */
    @Suppress("LoopWithTooManyJumpStatements")
    fun callGraph(): List<MethodCall> {
        val calls = mutableListOf<MethodCall>()
        val methodSymbols = symbols.filter { it.kind == SymbolDetailKind.FUNCTION }
        val methodsBySimpleName =
            methodSymbols.groupBy {
                it.name.substringAfterLast('.')
            }

        val fileImportedClasses =
            references
                .filter { it.kind == ReferenceKind.IMPORT }
                .groupBy { it.file.absolutePath }
                .mapValues { (_, refs) ->
                    refs.mapNotNull { it.targetQualifiedName }.toSet()
                }

        val fileReceiverTypes = buildReceiverTypeMap()

        for (ref in references) {
            if (ref.kind != ReferenceKind.CALL) continue
            val targets = methodsBySimpleName[ref.targetName] ?: continue
            val caller = findContainingMethod(ref.file, ref.line) ?: continue
            addCallEdges(
                ref, targets, caller, fileImportedClasses, fileReceiverTypes, calls,
            )
        }
        return calls.distinctBy {
            "${it.caller.qualifiedName}->${it.target.qualifiedName}"
        }
    }

    @Suppress("LoopWithTooManyJumpStatements", "LongParameterList")
    private fun addCallEdges(
        ref: Reference,
        targets: List<Symbol>,
        caller: Symbol,
        fileImportedClasses: Map<String, Set<String>>,
        fileReceiverTypes: Map<String, Map<String, String>>,
        calls: MutableList<MethodCall>,
    ) {
        val receiver = extractReceiver(ref.context, ref.targetName)
        val callerClassQN = caller.qualifiedName.substringBeforeLast('.', "")

        val resolvedTargets =
            if (targets.size == 1) {
                targets
            } else {
                disambiguateTargets(
                    targets, ref, caller, receiver,
                    callerClassQN, fileImportedClasses, fileReceiverTypes,
                )
            }

        for (target in resolvedTargets) {
            if (target.file == ref.file && target.line == ref.line) continue
            val targetClassQN = target.qualifiedName.substringBeforeLast('.', "")
            val isSelfCall =
                targetClassQN == callerClassQN &&
                    target.qualifiedName == caller.qualifiedName
            if (isSelfCall) continue
            calls.add(
                MethodCall(
                    caller = caller, target = target,
                    file = ref.file, line = ref.line,
                ),
            )
        }
    }

    private fun extractReceiver(context: String, methodName: String): String? {
        val pattern = Regex("""(\w+)\.$methodName\s*\(""")
        val match = pattern.find(context) ?: return null
        val receiver = match.groupValues[1]
        if (receiver in setOf("this", "super", "it", "Companion")) return null
        return receiver
    }

    @Suppress("CyclomaticComplexMethod")
    private fun buildReceiverTypeMap(): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()

        for (symbol in symbols) {
            if (symbol.kind !in setOf(SymbolDetailKind.CLASS, SymbolDetailKind.DATA_CLASS)) continue
            val filePath = symbol.file.absolutePath
            val fileRefs = references.filter { it.file.absolutePath == filePath }
            val imports =
                fileRefs
                    .filter { it.kind == ReferenceKind.IMPORT }
                    .mapNotNull { ref -> ref.targetQualifiedName?.let { ref.targetName to it } }
                    .toMap()

            runCatching {
                val lines = symbol.file.readLines()
                val classLine = if (symbol.line - 1 in lines.indices) symbol.line - 1 else return@runCatching
                val searchRange = classLine until minOf(classLine + 15, lines.size)
                val paramPattern = Regex("""(?:val|var)\s+(\w+)\s*:\s*(\w+)""")

                for (lineIdx in searchRange) {
                    val line = lines[lineIdx]
                    paramPattern.findAll(line).forEach { match ->
                        val propName = match.groupValues[1]
                        val typeName = match.groupValues[2]
                        val qualifiedType =
                            imports[typeName]
                                ?: bySimpleName[typeName]?.singleOrNull()?.qualifiedName
                                ?: if (symbol.packageName.isNotEmpty()) "${symbol.packageName}.$typeName" else null
                        if (qualifiedType != null) {
                            result.getOrPut(filePath) { mutableMapOf() }[propName] = qualifiedType
                        }
                    }
                    if (line.contains("{") && lineIdx > classLine) break
                }
            }
        }
        return result
    }

    @Suppress("LongParameterList")
    private fun disambiguateTargets(
        targets: List<Symbol>,
        ref: Reference,
        caller: Symbol,
        receiver: String?,
        callerClassQN: String,
        fileImportedClasses: Map<String, Set<String>>,
        fileReceiverTypes: Map<String, Map<String, String>>,
    ): List<Symbol> {
        val filePath = ref.file.absolutePath

        val byReceiver = resolveByReceiver(targets, receiver, fileReceiverTypes, filePath)
        if (byReceiver != null) return byReceiver

        val byImport = resolveByImport(targets, callerClassQN, fileImportedClasses, filePath)
        if (byImport != null) return byImport

        val samePackage = targets.filter { it.packageName == caller.packageName }
        return if (samePackage.isNotEmpty() && samePackage.size < targets.size) samePackage else targets
    }

    private fun resolveByReceiver(
        targets: List<Symbol>,
        receiver: String?,
        fileReceiverTypes: Map<String, Map<String, String>>,
        filePath: String,
    ): List<Symbol>? {
        if (receiver == null) return null
        val receiverQN = fileReceiverTypes[filePath]?.get(receiver) ?: return null
        val matched = targets.filter { it.qualifiedName.startsWith("$receiverQN.") }
        return matched.ifEmpty { null }
    }

    private fun resolveByImport(
        targets: List<Symbol>,
        callerClassQN: String,
        fileImportedClasses: Map<String, Set<String>>,
        filePath: String,
    ): List<Symbol>? {
        val imports = fileImportedClasses[filePath] ?: emptySet()
        val filtered =
            targets.filter { target ->
                val targetClassQN = target.qualifiedName.substringBeforeLast('.', "")
                targetClassQN in imports || targetClassQN == callerClassQN
            }
        return if (filtered.isNotEmpty() && filtered.size < targets.size) filtered else null
    }

    private fun findContainingMethod(file: File, line: Int): Symbol? {
        val fileMethods =
            symbols.filter {
                it.file.absolutePath == file.absolutePath && it.kind == SymbolDetailKind.FUNCTION
            }
        return fileMethods
            .filter { it.line <= line }
            .maxByOrNull { it.line }
    }

    companion object {
        /** Build a [SymbolIndex] from a list of source files using PSI parsing. */
        fun build(sourceFiles: List<File>): SymbolIndex {
            val allSymbols = mutableListOf<Symbol>()
            val allRefs = mutableListOf<Reference>()

            PsiEnvironment().use { env ->
                val parser = PsiParser(env)
                for (file in sourceFiles) {
                    runCatching {
                        allSymbols.addAll(parser.extractDeclarations(file))
                        allRefs.addAll(parser.extractReferences(file))
                    }.onFailure { e ->
                        val log = Logging.getLogger(SymbolIndex::class.java)
                        log.warn("srcx: failed to parse ${file.name}: ${e.message}")
                    }
                }
            }

            return SymbolIndex(allSymbols, allRefs)
        }
    }
}
