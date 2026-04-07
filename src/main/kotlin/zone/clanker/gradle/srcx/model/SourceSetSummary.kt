package zone.clanker.gradle.srcx.model

/**
 * The name of a Gradle source set (e.g. "main", "test", "androidTest").
 *
 * @property value the source set name, must not be blank
 */
@JvmInline
value class SourceSetName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "SourceSetName must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Symbols extracted from a single source set within a project.
 *
 * Groups symbols by their source set origin so reports can distinguish
 * main sources from test sources, androidTest, etc.
 *
 * @property name the source set name (e.g. "main", "test")
 * @property symbols the symbols found in this source set
 * @property sourceDirs the directories scanned for this source set
 */
data class SourceSetSummary(
    val name: SourceSetName,
    val symbols: List<SymbolEntry>,
    val sourceDirs: List<String>,
)
