package zone.clanker.gradle.srcx.parse

import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import zone.clanker.gradle.srcx.model.Reference
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.Symbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import java.io.File

private const val JAVA_CONTEXT_MAX_LEN = 80

/** Extracts [Symbol] declarations and [Reference] edges from Java PSI trees. */
@Suppress("TooManyFunctions", "UnreachableCode")
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
        extractMethodCalls(javaFile, file, results)
        extractConstructors(javaFile, file, results)
        extractCodeReferences(javaFile, file, results)
        extractNameReferences(javaFile, file, results)
        return results.distinctBy {
            listOf(it.sourceQualifiedName, it.targetName, it.targetQualifiedName, it.kind, it.line, it.context)
        }
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
        val imports = mutableListOf<Pair<PsiElement, String>>()
        javaFile.importList?.importStatements?.mapNotNullTo(imports) { statement ->
            statement.qualifiedName?.let { statement to it }
        }
        javaFile.importList?.importStaticStatements?.mapNotNullTo(imports) { statement ->
            statement.importReference?.qualifiedName?.let { statement to it }
        }
        imports.sortedBy { it.first.textOffset }.forEach { (statement, fqName) ->
            results.add(
                Reference(
                    targetName = fqName.substringAfterLast('.'),
                    targetQualifiedName = fqName,
                    kind = ReferenceKind.IMPORT,
                    file = file,
                    line = lineOf(statement),
                    context = statement.text.trim(),
                ),
            )
        }
    }

    private fun extractSupertypes(
        javaFile: PsiJavaFile,
        file: File,
        results: MutableList<Reference>,
    ) {
        val imports = importedNames(javaFile)
        for (cls in javaClasses(javaFile)) {
            val source = cls.qualifiedName
            val references =
                cls.extendsList
                    ?.referenceElements
                    .orEmpty()
                    .toList() +
                    cls.implementsList
                        ?.referenceElements
                        .orEmpty()
                        .toList()
            for (reference in references) {
                val name = reference.referenceName ?: continue
                val importedName = imports[name]
                results.add(
                    Reference(
                        targetName = name,
                        targetQualifiedName = explicitQualifiedName(reference) ?: importedName,
                        kind = ReferenceKind.SUPERTYPE,
                        file = file,
                        line = lineOf(reference),
                        context = reference.text,
                        sourceQualifiedName = source,
                        evidence =
                            if (importedName != null) ReferenceEvidence.DERIVED else ReferenceEvidence.DIRECT,
                    ),
                )
            }
        }
    }

    private fun extractMethodCalls(
        javaFile: PsiJavaFile,
        file: File,
        results: MutableList<Reference>,
    ) {
        val imports = importedNames(javaFile)
        val staticImports = staticImports(javaFile)
        val calls =
            PsiTreeUtil
                .collectElementsOfType(javaFile, PsiMethodCallExpression::class.java)
                .sortedBy { it.textOffset }
        for (call in calls) {
            val expression = call.methodExpression
            val name = expression.referenceName ?: continue
            val qualifier = expression.qualifierExpression?.text
            val qualifiedName = staticImports[name] ?: qualifier?.let(imports::get)?.let { "$it.$name" }
            results.add(
                Reference(
                    targetName = name,
                    targetQualifiedName = qualifiedName,
                    kind = ReferenceKind.CALL,
                    file = file,
                    line = lineOf(call),
                    context = call.text.take(JAVA_CONTEXT_MAX_LEN),
                    sourceQualifiedName = sourceQualifiedName(call),
                ),
            )
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun extractConstructors(
        javaFile: PsiJavaFile,
        file: File,
        results: MutableList<Reference>,
    ) {
        val imports = importedNames(javaFile)
        val expressions =
            PsiTreeUtil
                .collectElementsOfType(javaFile, PsiNewExpression::class.java)
                .sortedBy { it.textOffset }
        for (expression in expressions) {
            val reference = expression.classReference ?: continue
            val name = reference.referenceName ?: continue
            results.add(
                Reference(
                    targetName = name,
                    targetQualifiedName = explicitQualifiedName(reference) ?: imports[name],
                    kind = ReferenceKind.CONSTRUCTOR,
                    file = file,
                    line = lineOf(expression),
                    context = expression.text.take(JAVA_CONTEXT_MAX_LEN),
                    sourceQualifiedName = sourceQualifiedName(expression),
                    evidence = ReferenceEvidence.DIRECT,
                ),
            )
        }
    }

    private fun extractCodeReferences(
        javaFile: PsiJavaFile,
        file: File,
        results: MutableList<Reference>,
    ) {
        val imports = importedNames(javaFile)
        val references =
            PsiTreeUtil
                .collectElementsOfType(javaFile, PsiJavaCodeReferenceElement::class.java)
                .sortedBy { it.textOffset }
        for (reference in references) {
            val name = reference.referenceName ?: continue
            val importedName = imports[name]
            results.add(
                Reference(
                    targetName = name,
                    targetQualifiedName = explicitQualifiedName(reference) ?: importedName,
                    kind = typeReferenceKind(reference),
                    file = file,
                    line = lineOf(reference),
                    context = reference.text.take(JAVA_CONTEXT_MAX_LEN),
                    sourceQualifiedName = sourceQualifiedName(reference),
                    evidence =
                        if (importedName != null) ReferenceEvidence.DERIVED else ReferenceEvidence.DIRECT,
                ),
            )
        }
    }

    private fun extractNameReferences(
        javaFile: PsiJavaFile,
        file: File,
        results: MutableList<Reference>,
    ) {
        val staticImports = staticImports(javaFile)
        val references =
            PsiTreeUtil
                .collectElementsOfType(javaFile, PsiReferenceExpression::class.java)
                .sortedBy { it.textOffset }
        for (reference in references) {
            val name = reference.referenceName ?: continue
            results.add(
                Reference(
                    targetName = name,
                    targetQualifiedName = staticImports[name],
                    kind = ReferenceKind.NAME_REF,
                    file = file,
                    line = lineOf(reference),
                    context = reference.text.take(JAVA_CONTEXT_MAX_LEN),
                    sourceQualifiedName = sourceQualifiedName(reference),
                ),
            )
        }
    }

    @Suppress("NestedBlockDepth")
    private fun sourceQualifiedName(element: PsiElement): String? {
        var parent = element.parent
        while (parent != null) {
            val qualifiedName =
                when (parent) {
                    is PsiMethod -> {
                        val owner = parent.containingClass?.qualifiedName
                        if (parent.isConstructor) owner else owner?.let { "$it.${parent.name}" }
                    }
                    is PsiField -> parent.containingClass?.qualifiedName?.let { "$it.${parent.name}" }
                    is PsiClass -> parent.qualifiedName
                    else -> null
                }
            if (qualifiedName != null) return qualifiedName
            parent = parent.parent
        }
        return null
    }

    private fun typeReferenceKind(reference: PsiJavaCodeReferenceElement): ReferenceKind {
        val type =
            PsiTreeUtil
                .getParentOfType(reference, PsiTypeElement::class.java, false)
                ?: return ReferenceKind.TYPE_REF
        val owner = type.parent
        return when {
            owner is PsiField && owner.typeElement == type -> ReferenceKind.PROPERTY_TYPE
            owner is PsiParameter && owner.typeElement == type -> ReferenceKind.PARAMETER_TYPE
            owner is PsiMethod && owner.returnTypeElement == type -> ReferenceKind.RETURN_TYPE
            else -> ReferenceKind.TYPE_REF
        }
    }

    private fun importedNames(javaFile: PsiJavaFile): Map<String, String> {
        val imports = mutableListOf<String>()
        javaFile.importList?.importStatements?.mapNotNullTo(imports) { it.qualifiedName }
        javaFile.importList?.importStaticStatements?.mapNotNullTo(imports) { it.importReference?.qualifiedName }
        return imports
            .groupBy { it.substringAfterLast('.') }
            .mapNotNull { (name, targets) -> targets.distinct().singleOrNull()?.let { name to it } }
            .toMap()
    }

    private fun staticImports(javaFile: PsiJavaFile): Map<String, String> =
        javaFile.importList
            ?.importStaticStatements
            ?.mapNotNull { it.importReference?.qualifiedName }
            ?.groupBy { it.substringAfterLast('.') }
            ?.mapNotNull { (name, targets) -> targets.distinct().singleOrNull()?.let { name to it } }
            ?.toMap()
            .orEmpty()

    private fun explicitQualifiedName(reference: PsiJavaCodeReferenceElement): String? =
        runCatching { reference.qualifiedName }.getOrNull()?.takeIf { it.contains('.') }

    private fun javaClasses(javaFile: PsiJavaFile): List<PsiClass> =
        PsiTreeUtil
            .collectElementsOfType(javaFile, PsiClass::class.java)
            .filter { it.qualifiedName != null }
            .sortedBy { it.textOffset }
}
