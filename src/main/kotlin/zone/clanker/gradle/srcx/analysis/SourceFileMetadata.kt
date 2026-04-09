package zone.clanker.gradle.srcx.analysis

import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtSuperTypeEntry
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import zone.clanker.gradle.srcx.parse.PsiEnvironment
import java.io.File

/**
 * A source file with parsed metadata -- imports, class name, annotations, supertypes.
 *
 * Used by architecture analysis to classify components and build dependency graphs
 * without compiling anything.
 *
 * @property file the source file
 * @property packageName the declared package
 * @property qualifiedName fully qualified class name
 * @property simpleName simple class name
 * @property imports non-platform import statements
 * @property annotations annotation names found on the class
 * @property supertypes names of extended/implemented types
 * @property isInterface whether this is an interface
 * @property isAbstract whether this is abstract
 * @property isObject whether this is a Kotlin object
 * @property isDataClass whether this is a data class
 * @property language the source language
 * @property lineCount total lines in the file
 * @property methods list of method names declared
 */
data class SourceFileMetadata(
    val file: File,
    val packageName: String,
    val qualifiedName: String,
    val simpleName: String,
    val imports: List<String>,
    val annotations: List<String>,
    val supertypes: List<String>,
    val isInterface: Boolean,
    val isAbstract: Boolean,
    val isObject: Boolean,
    val isDataClass: Boolean,
    val language: Language,
    val lineCount: Int,
    val methods: List<String>,
) {
    /** Source file language. */
    enum class Language { KOTLIN, JAVA }
}

private val PLATFORM_PREFIXES = listOf("java.", "javax.", "kotlin.", "kotlinx.")

/**
 * Parse a Kotlin or Java source file into [SourceFileMetadata] using PSI.
 * Requires a [PsiManager] from a shared [PsiEnvironment].
 */
fun parseSourceFile(file: File, psiManager: PsiManager): SourceFileMetadata? {
    if (!file.isFile) return null
    return when (file.extension) {
        "kt" -> parseKotlinFile(file, psiManager)
        "java" -> parseJavaFile(file, psiManager)
        else -> null
    }
}

/**
 * Parse a Kotlin or Java source file into [SourceFileMetadata].
 * Creates a temporary [PsiEnvironment] for single-file parsing.
 * Prefer the overload that accepts a [PsiManager] for batch parsing.
 */
fun parseSourceFile(file: File): SourceFileMetadata? {
    if (!file.isFile) return null
    if (file.extension != "kt" && file.extension != "java") return null
    return PsiEnvironment().use { env -> parseSourceFile(file, env.psiManager) }
}

/** Resolved type-level info from the first class/object in a Kotlin file. */
private data class KtTypeInfo(
    val className: String,
    val annotations: List<String>,
    val supertypes: List<String>,
    val isInterface: Boolean,
    val isAbstract: Boolean,
    val isObject: Boolean,
    val isDataClass: Boolean,
)

private fun resolveKtTypeInfo(ktFile: KtFile, fallbackName: String): KtTypeInfo {
    val firstClass = ktFile.collectDescendantsOfType<KtClass>().firstOrNull()
    if (firstClass != null) {
        return KtTypeInfo(
            className = firstClass.name ?: fallbackName,
            annotations = firstClass.annotationEntries.mapNotNull { it.shortName?.asString() },
            supertypes = firstClass.superTypeListEntries.mapNotNull { supertypeName(it) },
            isInterface = firstClass.isInterface(),
            isAbstract = firstClass.hasModifier(KtTokens.ABSTRACT_KEYWORD),
            isObject = false,
            isDataClass = firstClass.isData(),
        )
    }
    val firstObject = ktFile.collectDescendantsOfType<KtObjectDeclaration>().firstOrNull { !it.isCompanion() }
    if (firstObject != null) {
        return KtTypeInfo(
            className = firstObject.name ?: fallbackName,
            annotations = firstObject.annotationEntries.mapNotNull { it.shortName?.asString() },
            supertypes = firstObject.superTypeListEntries.mapNotNull { supertypeName(it) },
            isInterface = false,
            isAbstract = false,
            isObject = true,
            isDataClass = false,
        )
    }
    return KtTypeInfo(
        className = fallbackName,
        annotations = emptyList(),
        supertypes = emptyList(),
        isInterface = false,
        isAbstract = false,
        isObject = false,
        isDataClass = false,
    )
}

private fun parseKotlinFile(file: File, psiManager: PsiManager): SourceFileMetadata? {
    val vf = LightVirtualFile(file.name, KotlinFileType.INSTANCE, file.readText())
    val ktFile = psiManager.findFile(vf) as? KtFile ?: return null

    val packageName = ktFile.packageFqName.asString()
    val imports =
        ktFile.importDirectives
            .mapNotNull { it.importedFqName?.asString() }
            .filter { fqn -> PLATFORM_PREFIXES.none { fqn.startsWith(it) } }

    val info = resolveKtTypeInfo(ktFile, file.nameWithoutExtension)
    val methods = ktFile.collectDescendantsOfType<KtNamedFunction>().mapNotNull { it.name }
    val qualifiedName = if (packageName.isNotEmpty()) "$packageName.${info.className}" else info.className

    return SourceFileMetadata(
        file = file,
        packageName = packageName,
        qualifiedName = qualifiedName,
        simpleName = info.className,
        imports = imports,
        annotations = info.annotations,
        supertypes = info.supertypes,
        isInterface = info.isInterface,
        isAbstract = info.isAbstract,
        isObject = info.isObject,
        isDataClass = info.isDataClass,
        language = SourceFileMetadata.Language.KOTLIN,
        lineCount = countCodeLines(file.readLines()),
        methods = methods,
    )
}

private fun supertypeName(entry: KtSuperTypeListEntry): String? =
    when (entry) {
        is KtSuperTypeCallEntry -> entry.calleeExpression.constructorReferenceExpression?.text
        is KtSuperTypeEntry -> entry.typeReference?.text?.substringBefore('<')
        else -> null
    }

/** Resolved type-level info from the first class in a Java file. */
private data class JavaTypeInfo(
    val className: String,
    val annotations: List<String>,
    val supertypes: List<String>,
    val isInterface: Boolean,
    val isAbstract: Boolean,
    val methods: List<String>,
)

private fun resolveJavaTypeInfo(javaFile: PsiJavaFile, fallbackName: String): JavaTypeInfo {
    val cls =
        javaFile.classes.firstOrNull()
            ?: return JavaTypeInfo(
                className = fallbackName,
                annotations = emptyList(),
                supertypes = emptyList(),
                isInterface = false,
                isAbstract = false,
                methods = emptyList(),
            )
    val superList = mutableListOf<String>()
    cls.extendsList?.referenceElements?.forEach { ref ->
        ref.referenceName?.let { superList.add(it) }
    }
    cls.implementsList?.referenceElements?.forEach { ref ->
        ref.referenceName?.let { superList.add(it) }
    }
    return JavaTypeInfo(
        className = cls.name ?: fallbackName,
        annotations = cls.annotations.mapNotNull { it.qualifiedName?.substringAfterLast('.') },
        supertypes = superList,
        isInterface = cls.isInterface,
        isAbstract = cls.hasModifierProperty(PsiModifier.ABSTRACT),
        methods = cls.methods.mapNotNull(PsiMethod::getName),
    )
}

private fun parseJavaFile(file: File, psiManager: PsiManager): SourceFileMetadata? {
    val vf = LightVirtualFile(file.name, JavaFileType.INSTANCE, file.readText())
    val javaFile = psiManager.findFile(vf) as? PsiJavaFile ?: return null

    val packageName = javaFile.packageName
    val imports =
        javaFile.importList
            ?.importStatements
            ?.mapNotNull { it.qualifiedName }
            ?.filter { fqn -> PLATFORM_PREFIXES.none { fqn.startsWith(it) } }
            ?: emptyList()

    val info = resolveJavaTypeInfo(javaFile, file.nameWithoutExtension)
    val qualifiedName = if (packageName.isNotEmpty()) "$packageName.${info.className}" else info.className

    return SourceFileMetadata(
        file = file,
        packageName = packageName,
        qualifiedName = qualifiedName,
        simpleName = info.className,
        imports = imports,
        annotations = info.annotations,
        supertypes = info.supertypes,
        isInterface = info.isInterface,
        isAbstract = info.isAbstract,
        isObject = false,
        isDataClass = false,
        language = SourceFileMetadata.Language.JAVA,
        lineCount = countCodeLines(file.readLines()),
        methods = info.methods,
    )
}

private fun countCodeLines(lines: List<String>): Int {
    var inBlockComment = false
    return lines.count { line ->
        val trimmed = line.trim()
        when {
            inBlockComment -> {
                if (trimmed.contains("*/")) inBlockComment = false
                false
            }
            trimmed.startsWith("/*") -> {
                inBlockComment = !trimmed.contains("*/")
                false
            }
            trimmed.isEmpty() || trimmed.startsWith("//") -> false
            else -> true
        }
    }
}

/** Scan source directories and parse all source files into [SourceFileMetadata]. */
fun scanSources(srcDirs: List<File>): List<SourceFileMetadata> {
    val files =
        srcDirs
            .filter { it.exists() }
            .flatMap { dir ->
                dir
                    .walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .toList()
            }
    if (files.isEmpty()) return emptyList()

    return PsiEnvironment().use { env ->
        val psiManager = env.psiManager
        files.mapNotNull { file -> parseSourceFile(file, psiManager) }
    }
}
