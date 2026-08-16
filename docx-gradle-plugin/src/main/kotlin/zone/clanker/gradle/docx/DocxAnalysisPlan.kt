package zone.clanker.gradle.docx

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DocxAnalysisPlan(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val preset: DocxPreset,
    val output: DocxOutputPlan,
    val scope: DocxScopePlan,
    val features: List<DocxFeaturePlan>,
    val requiredCapabilities: List<AnalysisCapability>,
    val missingCapabilities: MissingCapabilityPolicy,
    val updates: DocxUpdatePlan,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported DOCX analysis-plan schema: $schemaVersion" }
        val featureOrder = features.map(DocxFeaturePlan::feature)
        require(featureOrder == featureOrder.distinct().sortedBy(Enum<*>::name)) {
            "DOCX feature plans must be distinct and deterministic"
        }
        require(featureOrder.toSet() == DocxFeature.entries.toSet()) {
            "DOCX analysis plans must declare every feature"
        }
        require(requiredCapabilities == requiredCapabilities.distinct().sortedBy(Enum<*>::name)) {
            "DOCX analysis capabilities must be distinct and deterministic"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 3
    }
}

@Serializable
data class DocxOutputPlan(
    val directory: String,
    val snapshotFile: String,
    val formats: List<DocxOutputFormat>,
) {
    init {
        requireNormalizedRelativePath(directory, "DOCX output directory")
        requireNormalizedRelativePath(snapshotFile, "SRCX snapshot file")
        require(!relativePathContains(directory, snapshotFile)) {
            "DOCX output directory '$directory' must not contain SRCX snapshot '$snapshotFile'"
        }
        require(!relativePathContains("$directory.staging", snapshotFile)) {
            "DOCX staging directory '$directory.staging' must not contain SRCX snapshot '$snapshotFile'"
        }
        require(!relativePathContains("$directory.rollback", snapshotFile)) {
            "DOCX rollback directory '$directory.rollback' must not contain SRCX snapshot '$snapshotFile'"
        }
        requireSupportedOutputFormats(formats)
    }
}

@Serializable
data class DocxScopePlan(
    val builds: NameSelectionPlan,
    val projects: NameSelectionPlan,
    val sourceSets: NameSelectionPlan,
    val includeTests: Boolean,
    val configuredBuilds: List<DocxBuildScopePlan>,
    val unknownSelections: UnknownSelectionPolicy,
) {
    init {
        val names = configuredBuilds.map(DocxBuildScopePlan::name)
        require(names == names.distinct().sorted()) {
            "Configured build scopes must be distinct and deterministic"
        }
    }
}

@Serializable
data class NameSelectionPlan(
    val all: Boolean,
    val included: List<String>,
    val excluded: List<String>,
) {
    init {
        require(included.all { it.isNotBlank() && it == it.trim() }) {
            "Included scope names must be non-blank and normalized"
        }
        require(excluded.all { it.isNotBlank() && it == it.trim() }) {
            "Excluded scope names must be non-blank and normalized"
        }
        require(included == included.distinct().sorted()) { "Included scope names must be distinct and deterministic" }
        require(excluded == excluded.distinct().sorted()) { "Excluded scope names must be distinct and deterministic" }
        require(included.intersect(excluded.toSet()).isEmpty()) {
            "A scope name cannot be both included and excluded"
        }
    }
}

@Serializable
data class DocxBuildScopePlan(
    val name: String,
    val projects: NameSelectionPlan,
    val sourceSets: NameSelectionPlan,
    val includeTests: Boolean,
) {
    init {
        require(name.isNotBlank() && name == name.trim()) { "Configured build name must be non-blank and normalized" }
    }
}

@Serializable
data class DocxFeaturePlan(
    val feature: DocxFeature,
    val enabled: Boolean,
    val depth: GraphDepthPlan? = null,
    val includeDisconnected: Boolean? = null,
    val relationshipKinds: List<RelationshipKindSelection> = emptyList(),
    val severities: List<FindingSeveritySelection> = emptyList(),
    val dependencyInjectionFrameworks: List<DependencyInjectionFrameworkSelection> = emptyList(),
    val generatedSources: GeneratedSourcePolicy? = null,
    val includeSourceContent: Boolean? = null,
) {
    init {
        requireDistinctEnumOrder(relationshipKinds, "Relationship kinds")
        requireDistinctEnumOrder(severities, "Finding severities")
        requireDistinctEnumOrder(dependencyInjectionFrameworks, "Dependency-injection frameworks")
    }
}

@Serializable
data class DocxUpdatePlan(
    val mode: DocxUpdateMode,
    val liveReload: Boolean,
    val maxParallelism: Int,
    val debounceMilliseconds: Long,
) {
    init {
        require(maxParallelism > 0) { "DOCX update parallelism must be positive" }
        require(debounceMilliseconds >= 0) { "DOCX update debounce must not be negative" }
    }
}

@Serializable
data class GraphDepthPlan(
    val mode: GraphDepthMode,
    val hops: Int? = null,
) {
    init {
        require(mode == GraphDepthMode.HOPS || hops == null) { "Full graph depth must not declare a hop count" }
        require(mode != GraphDepthMode.HOPS || hops != null && hops > 0) {
            "Hop graph depth must declare a positive hop count"
        }
    }
}

@Serializable
enum class AnalysisCapability {
    WORKSPACE_STRUCTURE,
    SOURCE_METADATA,
    SOURCE_CONTENT,
    SYMBOLS,
    RELATIONSHIPS,
    FINDINGS,
    CYCLES,
    DEPENDENCY_INJECTION,
}

@OptIn(ExperimentalSerializationApi::class)
data object DocxAnalysisPlanJson {
    private val format =
        Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    fun encode(plan: DocxAnalysisPlan): String = format.encodeToString(plan) + "\n"

    fun decode(content: String): DocxAnalysisPlan = format.decodeFromString(content)
}

private fun requireNormalizedRelativePath(
    path: String,
    label: String,
) {
    require(path.isNotBlank()) { "$label must not be blank" }
    require(!path.startsWith('/') && '\\' !in path) { "$label must be a normalized relative path" }
    require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "$label must not contain empty, current, or parent segments"
    }
}

internal fun requireSupportedOutputFormats(formats: List<DocxOutputFormat>) {
    require(formats == listOf(DocxOutputFormat.HTML)) {
        "DOCX output formats must be exactly [HTML]; Markdown output is not implemented"
    }
}

private fun relativePathContains(
    directory: String,
    file: String,
): Boolean {
    val directorySegments = directory.split('/')
    val fileSegments = file.split('/')
    return directorySegments.size <= fileSegments.size &&
        fileSegments.take(directorySegments.size) == directorySegments
}

private fun <T : Enum<T>> requireDistinctEnumOrder(
    values: List<T>,
    label: String,
) {
    require(values == values.distinct().sortedBy(Enum<*>::name)) {
        "$label must be distinct and deterministic"
    }
}
