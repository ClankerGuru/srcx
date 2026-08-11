package zone.clanker.gradle.srcx.model

/** A production class-like declaration with no resolvable inbound source references. */
data class UnusedClass(
    val name: String,
    val qualifiedName: String,
    val kind: SymbolDetailKind,
    val buildName: String,
    val projectPath: String,
    val sourceSet: String,
    val filePath: String,
    val line: Int,
)
