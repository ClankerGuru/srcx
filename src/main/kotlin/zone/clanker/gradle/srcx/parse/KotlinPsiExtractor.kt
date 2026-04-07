package zone.clanker.gradle.srcx.parse

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtSuperTypeEntry
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import zone.clanker.gradle.srcx.model.Reference
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.Symbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import java.io.File

private const val CONTEXT_MAX_LEN = 80

/**
 * Extracts [Symbol] declarations and [Reference] edges from Kotlin PSI trees.
 */
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
                .mapNotNull { it.importedFqName?.asString() }
                .associateBy { it.substringAfterLast('.') }
        extractImports(ktFile, file, results)
        extractSupertypes(ktFile, file, importMap, results)
        extractCalls(ktFile, file, importMap, results)
        return results
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
            results.add(Symbol(name, fqName, classKind(cls), file, line, pkg))
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
            results.add(Symbol(name, fqName, SymbolDetailKind.OBJECT, file, line, pkg))
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
                val typeName = supertypeName(entry) ?: continue
                val line = lineOf(ktFile.text, entry.textOffset)
                results.add(
                    Reference(typeName, importMap[typeName], ReferenceKind.SUPERTYPE, file, line, entry.text.trim()),
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
            val callee = call.calleeExpression?.text ?: continue
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
                        callee, importMap[callee], kind, file, lineOf(ktFile.text, call.textOffset),
                        call.text
                            .take(CONTEXT_MAX_LEN),
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

    private fun supertypeName(entry: KtSuperTypeListEntry): String? =
        when (entry) {
            is KtSuperTypeCallEntry -> entry.calleeExpression.constructorReferenceExpression?.text
            is KtSuperTypeEntry -> entry.typeReference?.text?.substringBefore('<')
            else -> null
        }
}
