package zone.clanker.gradle.srcx.parse

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtSuperTypeEntry
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import zone.clanker.gradle.srcx.model.DeclarationSemantic
import zone.clanker.gradle.srcx.model.Reference
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.Symbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import java.io.File

private const val CONTEXT_MAX_LEN = 80

/**
 * Extracts [Symbol] declarations and [Reference] edges from Kotlin PSI trees.
 */
@Suppress("TooManyFunctions", "UnreachableCode")
internal class KotlinPsiExtractor {
    fun declarations(ktFile: KtFile, file: File): List<Symbol> {
        val pkg = ktFile.packageFqName.asString()
        val results = mutableListOf<Symbol>()
        extractClasses(ktFile, pkg, file, results)
        extractObjects(ktFile, pkg, file, results)
        extractTopLevel(ktFile, pkg, file, results)
        return results
    }

    fun references(ktFile: KtFile, file: File): List<Reference> {
        val results = mutableListOf<Reference>()
        val importMap =
            ktFile.importDirectives
                .mapNotNull { directive ->
                    directive.importedFqName?.asString()?.let { qualifiedName ->
                        (directive.aliasName ?: qualifiedName.substringAfterLast('.')) to qualifiedName
                    }
                }.groupBy({ it.first }, { it.second })
                .mapNotNull { (name, targets) -> targets.distinct().singleOrNull()?.let { name to it } }
                .toMap()
        extractImports(ktFile, file, results)
        extractSupertypes(ktFile, file, importMap, results)
        extractCalls(ktFile, file, importMap, results)
        extractTypeReferences(ktFile, file, importMap, results)
        extractNameReferences(ktFile, file, importMap, results)
        return results.distinctBy {
            listOf(it.sourceQualifiedName, it.targetName, it.targetQualifiedName, it.kind, it.line, it.context)
        }
    }

    // --- Declarations ---

    private fun extractClasses(
        ktFile: KtFile,
        pkg: String,
        file: File,
        results: MutableList<Symbol>,
    ) {
        for (cls in ktFile.collectDescendantsOfType<KtClass>()) {
            val name = cls.name ?: continue
            val fqName = cls.fqName?.asString() ?: qualified(pkg, name)
            val line = lineOf(ktFile.text, cls.textOffset)
            results.add(
                Symbol(
                    name,
                    fqName,
                    classKind(cls),
                    file,
                    line,
                    pkg,
                    classDeclarationSemantic(cls),
                ),
            )
            extractMembers(cls, name, fqName, pkg, file, ktFile.text, results)
        }
    }

    @Suppress("LongParameterList")
    private fun extractMembers(
        cls: KtClass,
        className: String,
        classFqName: String,
        pkg: String,
        file: File,
        text: String,
        results: MutableList<Symbol>,
    ) {
        for (fn in cls.declarations.filterIsInstance<KtNamedFunction>()) {
            val fnName = fn.name ?: continue
            val line = lineOf(text, fn.textOffset)
            results.add(
                Symbol("$className.$fnName", "$classFqName.$fnName", SymbolDetailKind.FUNCTION, file, line, pkg),
            )
        }
        for (prop in cls.declarations.filterIsInstance<KtProperty>()) {
            val propName = prop.name ?: continue
            val line = lineOf(text, prop.textOffset)
            results.add(
                Symbol("$className.$propName", "$classFqName.$propName", SymbolDetailKind.PROPERTY, file, line, pkg),
            )
        }
    }

    private fun extractObjects(
        ktFile: KtFile,
        pkg: String,
        file: File,
        results: MutableList<Symbol>,
    ) {
        @Suppress("LoopWithTooManyJumpStatements")
        for (obj in ktFile.collectDescendantsOfType<KtObjectDeclaration>()) {
            if (obj.isCompanion()) continue
            val name = obj.name ?: continue
            val fqName = obj.fqName?.asString() ?: qualified(pkg, name)
            val line = lineOf(ktFile.text, obj.textOffset)
            results.add(
                Symbol(
                    name,
                    fqName,
                    SymbolDetailKind.OBJECT,
                    file,
                    line,
                    pkg,
                    DeclarationSemantic.SINGLETON_OBJECT,
                ),
            )
        }
    }

    private fun extractTopLevel(
        ktFile: KtFile,
        pkg: String,
        file: File,
        results: MutableList<Symbol>,
    ) {
        for (fn in ktFile.declarations.filterIsInstance<KtNamedFunction>()) {
            val name = fn.name ?: continue
            val fqName = fn.fqName?.asString() ?: qualified(pkg, name)
            results.add(Symbol(name, fqName, SymbolDetailKind.FUNCTION, file, lineOf(ktFile.text, fn.textOffset), pkg))
        }
        for (prop in ktFile.declarations.filterIsInstance<KtProperty>()) {
            val name = prop.name ?: continue
            val fqName = prop.fqName?.asString() ?: qualified(pkg, name)
            results
                .add(Symbol(name, fqName, SymbolDetailKind.PROPERTY, file, lineOf(ktFile.text, prop.textOffset), pkg))
        }
    }

    // --- References ---

    private fun extractImports(ktFile: KtFile, file: File, results: MutableList<Reference>) {
        for (imp in ktFile.importDirectives) {
            val fqName = imp.importedFqName?.asString() ?: continue
            val simpleName = fqName.substringAfterLast('.')
            results
                .add(
                    Reference(
                        simpleName, fqName, ReferenceKind.IMPORT, file, lineOf(ktFile.text, imp.textOffset),
                        imp.text
                            .trim(),
                    ),
                )
        }
    }

    private fun extractSupertypes(
        ktFile: KtFile,
        file: File,
        importMap: Map<String, String>,
        results: MutableList<Reference>,
    ) {
        for (cls in ktFile.collectDescendantsOfType<KtClass>()) {
            for (entry in cls.superTypeListEntries) {
                val typeText = supertypeName(entry) ?: continue
                val typeName = typeText.substringAfterLast('.')
                val importedName = importMap[typeName]
                val qualifiedName = typeText.takeIf { it.contains('.') } ?: importedName
                val line = lineOf(ktFile.text, entry.textOffset)
                results.add(
                    Reference(
                        targetName = typeName,
                        targetQualifiedName = qualifiedName,
                        kind = ReferenceKind.SUPERTYPE,
                        file = file,
                        line = line,
                        context = entry.text.trim(),
                        sourceQualifiedName = cls.fqName?.asString(),
                        evidence =
                            if (importedName != null) {
                                ReferenceEvidence.DERIVED
                            } else {
                                ReferenceEvidence.DIRECT
                            },
                    ),
                )
            }
        }
    }

    private fun extractCalls(
        ktFile: KtFile,
        file: File,
        importMap: Map<String, String>,
        results: MutableList<Reference>,
    ) {
        for (call in ktFile.collectDescendantsOfType<KtCallExpression>()) {
            val calleeText = call.calleeExpression?.text ?: continue
            val callee = calleeText.substringAfterLast('.')
            val kind =
                if (callee.firstOrNull()?.isUpperCase() ==
                    true
                ) {
                    ReferenceKind.CONSTRUCTOR
                } else {
                    ReferenceKind.CALL
                }
            results
                .add(
                    Reference(
                        targetName = callee,
                        targetQualifiedName =
                            calleeText.takeIf { it.contains('.') }
                                ?: importMap[callee],
                        kind = kind,
                        file = file,
                        line = lineOf(ktFile.text, call.textOffset),
                        context = call.text.take(CONTEXT_MAX_LEN),
                        sourceQualifiedName = sourceQualifiedName(call),
                        evidence =
                            if (kind == ReferenceKind.CONSTRUCTOR) {
                                ReferenceEvidence.HEURISTIC
                            } else {
                                ReferenceEvidence.DIRECT
                            },
                    ),
                )
        }
    }

    private fun extractTypeReferences(
        ktFile: KtFile,
        file: File,
        importMap: Map<String, String>,
        results: MutableList<Reference>,
    ) {
        for (type in ktFile.collectDescendantsOfType<KtUserType>()) {
            val name = type.referencedName ?: continue
            val importedName = importMap[name]
            results.add(
                Reference(
                    targetName = name,
                    targetQualifiedName = type.text.takeIf { it.contains('.') } ?: importedName,
                    kind = typeReferenceKind(type),
                    file = file,
                    line = lineOf(ktFile.text, type.textOffset),
                    context = type.text.take(CONTEXT_MAX_LEN),
                    sourceQualifiedName = sourceQualifiedName(type),
                    evidence =
                        if (importedName != null) {
                            ReferenceEvidence.DERIVED
                        } else {
                            ReferenceEvidence.DIRECT
                        },
                ),
            )
        }
    }

    private fun extractNameReferences(
        ktFile: KtFile,
        file: File,
        importMap: Map<String, String>,
        results: MutableList<Reference>,
    ) {
        for (reference in ktFile.collectDescendantsOfType<KtNameReferenceExpression>()) {
            val name = reference.getReferencedName()
            if (name.firstOrNull()?.isUpperCase() != true) continue
            val importedName = importMap[name]
            results.add(
                Reference(
                    targetName = name,
                    targetQualifiedName = importedName,
                    kind = ReferenceKind.NAME_REF,
                    file = file,
                    line = lineOf(ktFile.text, reference.textOffset),
                    context = reference.text.take(CONTEXT_MAX_LEN),
                    sourceQualifiedName = sourceQualifiedName(reference),
                    evidence = ReferenceEvidence.HEURISTIC,
                ),
            )
        }
    }

    // --- Helpers ---

    private fun classKind(cls: KtClass): SymbolDetailKind =
        when {
            cls.isInterface() -> SymbolDetailKind.INTERFACE
            cls.isEnum() -> SymbolDetailKind.ENUM
            cls.isData() -> SymbolDetailKind.DATA_CLASS
            else -> SymbolDetailKind.CLASS
        }

    private fun classDeclarationSemantic(cls: KtClass): DeclarationSemantic =
        when {
            cls.isInterface() -> DeclarationSemantic.INTERFACE
            cls.isEnum() -> DeclarationSemantic.ENUM
            cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) || cls.hasModifier(KtTokens.SEALED_KEYWORD) ->
                DeclarationSemantic.ABSTRACT_CLASS
            else -> DeclarationSemantic.CONCRETE_CLASS
        }

    private fun supertypeName(entry: KtSuperTypeListEntry): String? =
        when (entry) {
            is KtSuperTypeCallEntry ->
                entry.calleeExpression
                    .typeReference
                    ?.text
                    ?.substringBefore('<')
            is KtSuperTypeEntry -> entry.typeReference?.text?.substringBefore('<')
            else -> null
        }

    private fun sourceQualifiedName(element: PsiElement): String? {
        var parent = element.parent
        while (parent != null) {
            val qualifiedName =
                when (parent) {
                    is KtNamedFunction -> parent.fqName?.asString()
                    is KtProperty -> parent.fqName?.asString()
                    is KtClassOrObject -> parent.fqName?.asString()
                    else -> null
                }
            if (qualifiedName != null) return qualifiedName
            parent = parent.parent
        }
        return null
    }

    private fun typeReferenceKind(type: KtUserType): ReferenceKind {
        val typeReference =
            generateSequence(type.parent) { it.parent }
                .filterIsInstance<KtTypeReference>()
                .firstOrNull()
                ?: return ReferenceKind.TYPE_REF
        val owner = typeReference.parent
        return when {
            owner is KtProperty && owner.typeReference == typeReference -> ReferenceKind.PROPERTY_TYPE
            owner is KtParameter && owner.typeReference == typeReference -> ReferenceKind.PARAMETER_TYPE
            owner is KtNamedFunction && owner.typeReference == typeReference -> ReferenceKind.RETURN_TYPE
            else -> ReferenceKind.TYPE_REF
        }
    }
}
