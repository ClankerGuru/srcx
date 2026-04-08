package zone.clanker.gradle.srcx.parse

import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import zone.clanker.gradle.srcx.model.Reference
import zone.clanker.gradle.srcx.model.Symbol
import java.io.File

/**
 * Parses .kt, .java, and .gradle.kts files using the Kotlin compiler PSI.
 *
 * Delegates to [KotlinPsiExtractor] and [JavaPsiExtractor] for
 * language-specific extraction.
 */
class PsiParser(
    private val env: PsiEnvironment,
) {
    private val kotlinExtractor = KotlinPsiExtractor()
    private val javaExtractor = JavaPsiExtractor()

    fun extractDeclarations(file: File): List<Symbol> =
        when (file.extension) {
            "kt", "kts" -> parseKtFile(file)?.let { kotlinExtractor.declarations(it, file) } ?: emptyList()
            "java" -> parseJavaFile(file)?.let { javaExtractor.declarations(it, file) } ?: emptyList()
            else -> emptyList()
        }

    fun extractReferences(file: File): List<Reference> =
        when (file.extension) {
            "kt", "kts" -> parseKtFile(file)?.let { kotlinExtractor.references(it, file) } ?: emptyList()
            "java" -> parseJavaFile(file)?.let { javaExtractor.references(it, file) } ?: emptyList()
            else -> emptyList()
        }

    private fun parseKtFile(file: File): KtFile? {
        val vf = LightVirtualFile(file.name, KotlinFileType.INSTANCE, file.readText())
        return env.psiManager.findFile(vf) as? KtFile
    }

    private fun parseJavaFile(file: File): PsiJavaFile? {
        val vf = LightVirtualFile(file.name, JavaFileType.INSTANCE, file.readText())
        return env.psiManager.findFile(vf) as? PsiJavaFile
    }
}

/** Count newlines in [text] up to [offset] to compute 1-based line number. */
internal fun lineOf(text: String, offset: Int): Int {
    var count = 0
    for (i in 0 until offset.coerceAtMost(text.length)) {
        if (text[i] == '\n') count++
    }
    return count + 1
}

/** Convenience: line number from a PSI element. */
internal fun lineOf(element: PsiElement): Int {
    val text = element.containingFile?.text ?: return 1
    return lineOf(text, element.textOffset)
}

internal fun qualified(pkg: String, name: String): String =
    if (pkg.isNotEmpty()) "$pkg.$name" else name
