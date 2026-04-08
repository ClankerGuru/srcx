package zone.clanker.gradle.srcx.analysis

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

/**
 * Parse a Kotlin or Java source file into [SourceFileMetadata].
 * Reads only structural parts -- package, imports, class declaration, annotations.
 * Does not compile anything.
 */
@Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
fun parseSourceFile(file: File): SourceFileMetadata? {
    if (!file.isFile) return null
    val lang =
        when (file.extension) {
            "kt" -> SourceFileMetadata.Language.KOTLIN
            "java" -> SourceFileMetadata.Language.JAVA
            else -> return null
        }

    val lines = file.readLines()
    val packageName =
        lines
            .firstOrNull { it.trimStart().startsWith("package ") }
            ?.trimStart()
            ?.removePrefix("package ")
            ?.trimEnd(';')
            ?.trim() ?: ""

    val imports =
        lines
            .filter { it.trimStart().startsWith("import ") }
            .map {
                it
                    .trimStart()
                    .removePrefix("import ")
                    .trimEnd(';')
                    .trim()
            }.filter {
                !it.startsWith("java.") &&
                    !it.startsWith("javax.") &&
                    !it.startsWith("kotlin.") &&
                    !it.startsWith("kotlinx.")
            }

    val annotations = mutableListOf<String>()
    val methods = mutableListOf<String>()
    var className = ""
    var isInterface = false
    var isAbstract = false
    var isObject = false
    var isDataClass = false
    var supertypes = listOf<String>()

    for (line in lines) {
        val trimmed = line.trim()

        if (className.isEmpty()) {
            if (isAnnotationLine(trimmed)) {
                val anno = trimmed.substringBefore("(").substringBefore(" ").removePrefix("@")
                annotations.add(anno)
            }

            val classMatch = findClassDeclaration(trimmed, lang)
            if (classMatch != null) {
                className = classMatch.name
                isInterface = classMatch.isInterface
                isAbstract = classMatch.isAbstract
                isObject = classMatch.isObject
                isDataClass = classMatch.isDataClass
                supertypes = classMatch.supertypes
            }
        }

        if (lang == SourceFileMetadata.Language.KOTLIN) {
            if (isKotlinMethodLine(trimmed)) {
                extractKotlinMethodName(trimmed)?.let { methods.add(it) }
            }
        } else {
            val javaMethodPattern =
                Regex(
                    """^(public|private|protected|static|final|synchronized|abstract|override|\s)*""" +
                        """(void|int|long|boolean|String|List|Map|Set|Optional|[A-Z]\w*(<.*>)?)\s+\w+\s*\(.*""",
                )
            if (javaMethodPattern.containsMatchIn(trimmed)) {
                extractJavaMethodName(trimmed)?.let { methods.add(it) }
            }
        }
    }

    if (className.isEmpty()) {
        className = file.nameWithoutExtension
    }

    val qualifiedName = if (packageName.isNotEmpty()) "$packageName.$className" else className

    return SourceFileMetadata(
        file = file,
        packageName = packageName,
        qualifiedName = qualifiedName,
        simpleName = className,
        imports = imports,
        annotations = annotations,
        supertypes = supertypes,
        isInterface = isInterface,
        isAbstract = isAbstract,
        isObject = isObject,
        isDataClass = isDataClass,
        language = lang,
        lineCount = countCodeLines(lines),
        methods = methods,
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

private data class ClassDeclaration(
    val name: String,
    val isInterface: Boolean,
    val isAbstract: Boolean,
    val isObject: Boolean,
    val isDataClass: Boolean,
    val supertypes: List<String>,
)

private val ANNOTATION_EXCLUSION_PREFIXES = listOf("@file", "@param", "@return")

private val KOTLIN_METHOD_PREFIXES =
    listOf(
        "fun ", "suspend fun ", "override fun ",
        "private fun ", "internal fun ", "protected fun ",
    )

private fun isKotlinMethodLine(line: String): Boolean =
    KOTLIN_METHOD_PREFIXES.any { line.startsWith(it) }

private fun isAnnotationLine(line: String): Boolean =
    line.startsWith("@") && ANNOTATION_EXCLUSION_PREFIXES.none { line.startsWith(it) }

private fun isCommentLine(line: String): Boolean =
    line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")

private fun extractNameToken(keywords: List<String>, classIdx: Int): String? {
    val raw =
        keywords
            .getOrNull(classIdx + 1)
            ?.substringBefore("(")
            ?.substringBefore("{")
            ?.substringBefore(":")
            ?.substringBefore("<")
            ?.trim()
            ?: return null
    return if (raw.isNotEmpty() && raw[0].isUpperCase()) raw else null
}

@Suppress("ReturnCount", "NestedBlockDepth", "CyclomaticComplexMethod", "LongMethod")
private fun findClassDeclaration(line: String, lang: SourceFileMetadata.Language): ClassDeclaration? {
    if (isCommentLine(line)) return null

    val keywords = line.split(" ", "\t").filter { it.isNotBlank() }
    val classIdx = keywords.indexOfFirst { it in setOf("class", "interface", "object", "enum") }
    if (classIdx < 0) return null

    val typeKeyword = keywords[classIdx]
    val nameToken = extractNameToken(keywords, classIdx) ?: return null

    val isAbstract = "abstract" in keywords.subList(0, classIdx)
    val isData = "data" in keywords.subList(0, classIdx)

    val supertypes = mutableListOf<String>()
    val afterName = line.substringAfter(nameToken, "")
    if (lang == SourceFileMetadata.Language.KOTLIN) {
        val afterConstructor =
            if (afterName.contains("(")) {
                var depth = 0
                var endIdx = 0
                for ((i, ch) in afterName.withIndex()) {
                    if (ch == '(') {
                        depth++
                    } else if (ch == ')') {
                        depth--
                        if (depth == 0) {
                            endIdx = i + 1
                            break
                        }
                    }
                }
                afterName.substring(endIdx)
            } else {
                afterName
            }
        val colonPart = afterConstructor.substringAfter(":", "").substringBefore("{").trim()
        if (colonPart.isNotEmpty()) {
            colonPart.split(",").forEach { s ->
                val clean =
                    s
                        .trim()
                        .substringBefore("(")
                        .substringBefore("<")
                        .trim()
                if (clean.isNotEmpty() && clean[0].isUpperCase()) supertypes.add(clean)
            }
        }
    } else {
        val extendsPart =
            afterName
                .substringAfter("extends ", "")
                .substringBefore("{")
                .substringBefore("implements")
                .trim()
        if (extendsPart.isNotEmpty()) {
            extendsPart.split(",").forEach { s ->
                val clean = s.trim().substringBefore("<").trim()
                if (clean.isNotEmpty() && clean[0].isUpperCase()) supertypes.add(clean)
            }
        }
        val implPart = afterName.substringAfter("implements ", "").substringBefore("{").trim()
        if (implPart.isNotEmpty()) {
            implPart.split(",").forEach { s ->
                val clean = s.trim().substringBefore("<").trim()
                if (clean.isNotEmpty() && clean[0].isUpperCase()) supertypes.add(clean)
            }
        }
    }

    return ClassDeclaration(
        name = nameToken,
        isInterface = typeKeyword == "interface",
        isAbstract = isAbstract,
        isObject = typeKeyword == "object",
        isDataClass = isData,
        supertypes = supertypes,
    )
}

private fun extractKotlinMethodName(line: String): String? {
    val funIdx = line.indexOf("fun ")
    if (funIdx < 0) return null
    val afterFun = line.substring(funIdx + 4).trim()
    val name = afterFun.substringBefore("(").substringAfterLast(".").trim()
    return name.ifEmpty { null }
}

private fun extractJavaMethodName(line: String): String? {
    val parenIdx = line.indexOf('(')
    if (parenIdx < 0) return null
    val beforeParen = line.substring(0, parenIdx).trim()
    val name = beforeParen.substringAfterLast(" ").trim()
    return if (name.isNotEmpty() && name[0].isLowerCase()) name else null
}

/** Scan source directories and parse all source files into [SourceFileMetadata]. */
fun scanSources(srcDirs: List<File>): List<SourceFileMetadata> =
    srcDirs
        .filter { it.exists() }
        .flatMap { dir ->
            dir
                .walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .mapNotNull { parseSourceFile(it) }
                .toList()
        }
