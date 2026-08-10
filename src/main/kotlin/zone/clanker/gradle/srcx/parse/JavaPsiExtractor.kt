package zone.clanker.gradle.srcx.parse

import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import zone.clanker.gradle.srcx.model.Reference
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.Symbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import java.io.File

/**
 * Extracts [Symbol] declarations and [Reference] edges from Java PSI trees.
 */
internal class JavaPsiExtractor {
    fun declarations(javaFile: PsiJavaFile, file: File): List<Symbol> {
        val pkg = javaFile.packageName
        val results = mutableListOf<Symbol>()
        for (cls in javaFile.classes) {
            extractClass(cls, pkg, file, results)
        }
        return results
    }

    fun references(javaFile: PsiJavaFile, file: File): List<Reference> {
        val results = mutableListOf<Reference>()
        extractImports(javaFile, file, results)
        extractSupertypes(javaFile, file, results)
        extractCodeReferences(javaFile, file, results)
        return results.distinctBy { listOf(it.targetName, it.kind, it.line, it.context) }
    }

    private fun extractClass(
        cls: PsiClass,
        pkg: String,
        file: File,
        results: MutableList<Symbol>,
    ) {
        val name = cls.name ?: return
        val fqName = cls.qualifiedName ?: qualified(pkg, name)
        val kind =
            when {
                cls.isInterface -> SymbolDetailKind.INTERFACE
                cls.isEnum -> SymbolDetailKind.ENUM
                else -> SymbolDetailKind.CLASS
            }
        results.add(Symbol(name, fqName, kind, file, lineOf(cls), pkg))

        for (method in cls.methods) {
            results.add(
                Symbol(
                    "$name.${method.name}",
                    "$fqName.${method.name}",
                    SymbolDetailKind.FUNCTION,
                    file,
                    lineOf(method),
                    pkg,
                ),
            )
        }
        for (field in cls.fields) {
            results.add(
                Symbol(
                    "$name.${field.name}",
                    "$fqName.${field.name}",
                    SymbolDetailKind.PROPERTY,
                    file,
                    lineOf(field),
                    pkg,
                ),
            )
        }
        for (inner in cls.innerClasses) {
            extractClass(inner, pkg, file, results)
        }
    }

    private fun extractImports(
        javaFile: PsiJavaFile,
        file: File,
        results: MutableList<Reference>,
    ) {
        javaFile.importList?.importStatements?.forEach { imp ->
            val fqName = imp.qualifiedName ?: return@forEach
            val simpleName = fqName.substringAfterLast('.')
            results.add(
                Reference(
                    simpleName, fqName, ReferenceKind.IMPORT,
                    file, lineOf(imp), imp.text.trim(),
                ),
            )
        }
    }

    private fun extractSupertypes(
        javaFile: PsiJavaFile,
        file: File,
        results: MutableList<Reference>,
    ) {
        for (cls in javaFile.classes) {
            cls.extendsList?.referenceElements?.forEach { ref ->
                val name = ref.referenceName ?: return@forEach
                val qualified = runCatching { ref.qualifiedName }.getOrNull()
                results.add(
                    Reference(name, qualified, ReferenceKind.SUPERTYPE, file, lineOf(ref), ref.text),
                )
            }
            cls.implementsList?.referenceElements?.forEach { ref ->
                val name = ref.referenceName ?: return@forEach
                val qualified = runCatching { ref.qualifiedName }.getOrNull()
                results.add(
                    Reference(name, qualified, ReferenceKind.SUPERTYPE, file, lineOf(ref), ref.text),
                )
            }
        }
    }

    private fun extractCodeReferences(
        javaFile: PsiJavaFile,
        file: File,
        results: MutableList<Reference>,
    ) {
        val imports =
            javaFile.importList
                ?.importStatements
                ?.mapNotNull { it.qualifiedName }
                ?.associateBy { it.substringAfterLast('.') }
                ?: emptyMap()
        PsiTreeUtil
            .collectElementsOfType(javaFile, PsiJavaCodeReferenceElement::class.java)
            .forEach { reference ->
                val name = reference.referenceName ?: return@forEach
                results.add(
                    Reference(
                        name,
                        imports[name],
                        ReferenceKind.TYPE_REF,
                        file,
                        lineOf(reference),
                        reference.text,
                    ),
                )
            }
    }
}
