package zone.clanker.gradle.docx

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

@DslMarker
annotation class DocxDslMarker

enum class DocxPreset {
    FULL,
    FINDINGS,
    ARCHITECTURE,
    DEPENDENCY_INJECTION,
    NONE,
}

enum class DocxOutputFormat {
    HTML,
    MARKDOWN,
}

enum class UnknownSelectionPolicy {
    FAIL,
    WARN,
}

enum class MissingCapabilityPolicy {
    FAIL,
    WARN,
}

enum class FindingSeveritySelection {
    FORBIDDEN,
    WARNING,
    INFO,
}

enum class DependencyInjectionFrameworkSelection {
    DAGGER,
    HILT,
}

enum class GeneratedSourcePolicy {
    EXCLUDE,
    INCLUDE,
    ONLY,
}

enum class RelationshipKindSelection {
    IMPORT,
    EXTENDS,
    IMPLEMENTS,
    CALL,
    CONSTRUCTOR,
    NAME_REFERENCE,
    TYPE_REFERENCE,
    PROPERTY_TYPE,
    PARAMETER_TYPE,
    RETURN_TYPE,
}

enum class DocxFeature {
    WORKSPACE,
    FINDINGS,
    FILES,
    SYMBOLS,
    RELATIONSHIPS,
    CYCLES,
    DEPENDENCY_INJECTION,
    SOURCES,
}

enum class GraphDepthMode {
    FULL,
    HOPS,
}

enum class DocxUpdateMode(
    val label: String,
) {
    ASYNC("Asynchronous"),
    STRICT("Strict"),
}

@DocxDslMarker
abstract class DocxSettingsExtension
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        abstract val preset: Property<DocxPreset>
        abstract val autoGenerate: Property<Boolean>
        abstract val missingCapabilities: Property<MissingCapabilityPolicy>

        val output: DocxOutputOptions = objects.newInstance(DocxOutputOptions::class.java)
        val scope: DocxScopeOptions = objects.newInstance(DocxScopeOptions::class.java)
        val features: DocxFeatureOptions = objects.newInstance(DocxFeatureOptions::class.java)
        val updates: DocxUpdateOptions = objects.newInstance(DocxUpdateOptions::class.java)
        val service: DocxServiceOptions = objects.newInstance(DocxServiceOptions::class.java)

        init {
            preset.convention(DocxPreset.FULL)
            autoGenerate.convention(false)
            missingCapabilities.convention(MissingCapabilityPolicy.WARN)
            features.applyPresetConventions(preset)
        }

        fun output(action: DocxOutputOptions.() -> Unit) {
            output.action()
        }

        fun scope(action: DocxScopeOptions.() -> Unit) {
            scope.action()
        }

        fun features(action: DocxFeatureOptions.() -> Unit) {
            features.action()
        }

        fun updates(action: DocxUpdateOptions.() -> Unit) {
            updates.action()
        }

        fun service(action: DocxServiceOptions.() -> Unit) {
            service.action()
        }

        internal fun analysisPlan(): DocxAnalysisPlan =
            features.plans().let { featurePlans ->
                DocxAnalysisPlan(
                    preset = preset.get(),
                    output = output.plan(),
                    scope = scope.plan(),
                    features = featurePlans,
                    requiredCapabilities = features.requiredCapabilities(featurePlans),
                    missingCapabilities = missingCapabilities.get(),
                    updates = updates.plan(),
                )
            }
    }

@DocxDslMarker
abstract class DocxServiceOptions
    @Inject
    constructor() {
        abstract val url: Property<String>
        abstract val token: Property<String>
        abstract val endpointFile: Property<String>
        abstract val workspaceId: Property<String>

        init {
            url.convention("")
            token.convention("")
            endpointFile.convention(
                "${System.getProperty("user.home")}/.srcx/docx-service/endpoint.json",
            )
        }
    }

@DocxDslMarker
abstract class DocxUpdateOptions
    @Inject
    constructor() {
        abstract val mode: Property<DocxUpdateMode>
        abstract val liveReload: Property<Boolean>
        abstract val maxParallelism: Property<Int>
        abstract val debounceMilliseconds: Property<Long>

        init {
            mode.convention(DocxUpdateMode.ASYNC)
            liveReload.convention(true)
            maxParallelism.convention(
                defaultMaxParallelism(
                    processors = Runtime.getRuntime().availableProcessors(),
                    maxMemoryBytes = Runtime.getRuntime().maxMemory(),
                ),
            )
            debounceMilliseconds.convention(DEFAULT_DEBOUNCE_MILLISECONDS)
        }

        internal fun plan(): DocxUpdatePlan =
            DocxUpdatePlan(
                mode = mode.get(),
                liveReload = liveReload.get(),
                maxParallelism = maxParallelism.get(),
                debounceMilliseconds = debounceMilliseconds.get(),
            )

        private companion object {
            const val DEFAULT_DEBOUNCE_MILLISECONDS: Long = 250
        }
    }

internal fun defaultMaxParallelism(
    processors: Int,
    maxMemoryBytes: Long,
): Int {
    require(processors > 0) { "Available processors must be positive" }
    require(maxMemoryBytes > 0) { "Maximum memory must be positive" }
    val memoryWorkers = (maxMemoryBytes / BYTES_PER_WORKER).coerceAtLeast(1).coerceAtMost(MAX_PARALLELISM.toLong())
    return minOf(processors, memoryWorkers.toInt(), MAX_PARALLELISM)
}

private const val MAX_PARALLELISM: Int = 4
private const val BYTES_PER_WORKER: Long = 512L * 1024 * 1024

@DocxDslMarker
abstract class DocxOutputOptions
    @Inject
    constructor() {
        abstract val directory: Property<String>
        abstract val snapshotFile: Property<String>
        abstract val formats: SetProperty<DocxOutputFormat>

        init {
            directory.convention(Docx.DEFAULT_OUTPUT_DIRECTORY)
            snapshotFile.convention(Docx.DEFAULT_SNAPSHOT_FILE)
            formats.convention(setOf(DocxOutputFormat.HTML))
        }

        internal fun plan(): DocxOutputPlan =
            DocxOutputPlan(
                directory = directory.get().requireRelativePath("DOCX output directory"),
                snapshotFile = snapshotFile.get().requireRelativePath("SRCX snapshot file"),
                formats = formats.get().sortedBy(Enum<*>::name),
            )
    }

@DocxDslMarker
abstract class DocxScopeOptions
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        val builds: NameSelection = objects.newInstance(NameSelection::class.java)
        val projects: NameSelection = objects.newInstance(NameSelection::class.java)
        val sourceSets: NameSelection = objects.newInstance(NameSelection::class.java)
        abstract val includeTests: Property<Boolean>
        abstract val unknownSelections: Property<UnknownSelectionPolicy>

        val configuredBuilds: NamedDomainObjectContainer<DocxBuildScope> =
            objects.domainObjectContainer(DocxBuildScope::class.java) { name ->
                objects.newInstance(DocxBuildScope::class.java, name)
            }

        init {
            includeTests.convention(true)
            unknownSelections.convention(UnknownSelectionPolicy.FAIL)
        }

        fun build(
            name: String,
            action: DocxBuildScope.() -> Unit,
        ) {
            configuredBuilds.maybeCreate(name.requireSelectionName("Build")).action()
        }

        internal fun plan(): DocxScopePlan =
            DocxScopePlan(
                builds = builds.plan(),
                projects = projects.plan(),
                sourceSets = sourceSets.plan(),
                includeTests = includeTests.get(),
                configuredBuilds = configuredBuilds.map(DocxBuildScope::plan).sortedBy(DocxBuildScopePlan::name),
                unknownSelections = unknownSelections.get(),
            )
    }

@DocxDslMarker
abstract class NameSelection
    @Inject
    constructor() {
        abstract val all: Property<Boolean>
        abstract val included: SetProperty<String>
        abstract val excluded: SetProperty<String>

        init {
            all.convention(true)
            included.convention(emptySet())
            excluded.convention(emptySet())
        }

        fun includeAll() {
            all.set(true)
            included.empty()
        }

        fun include(vararg names: String) {
            val normalized = names.map { it.requireSelectionName("Included selection") }
            all.set(false)
            included.addAll(normalized)
        }

        fun exclude(vararg names: String) {
            excluded.addAll(names.map { it.requireSelectionName("Excluded selection") })
        }

        internal fun plan(): NameSelectionPlan {
            val includedNames = included.get().sorted()
            val excludedNames = excluded.get().sorted()
            require(includedNames.intersect(excludedNames.toSet()).isEmpty()) {
                "A scope name cannot be both included and excluded"
            }
            return NameSelectionPlan(
                all = all.get(),
                included = includedNames,
                excluded = excludedNames,
            )
        }
    }

@DocxDslMarker
abstract class DocxBuildScope
    @Inject
    constructor(
        private val scopeName: String,
        objects: ObjectFactory,
    ) : Named {
        val projects: NameSelection = objects.newInstance(NameSelection::class.java)
        val sourceSets: NameSelection = objects.newInstance(NameSelection::class.java)
        abstract val includeTests: Property<Boolean>

        init {
            includeTests.convention(true)
        }

        override fun getName(): String = scopeName

        internal fun plan(): DocxBuildScopePlan =
            DocxBuildScopePlan(
                name = name,
                projects = projects.plan(),
                sourceSets = sourceSets.plan(),
                includeTests = includeTests.get(),
            )
    }

@DocxDslMarker
abstract class DocxFeatureOptions
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        val workspace: FeatureToggle = objects.newInstance(FeatureToggle::class.java)
        val findings: FindingFeatureOptions = objects.newInstance(FindingFeatureOptions::class.java)
        val files: FeatureToggle = objects.newInstance(FeatureToggle::class.java)
        val symbols: GraphFeatureOptions = objects.newInstance(GraphFeatureOptions::class.java)
        val relationships: GraphFeatureOptions = objects.newInstance(GraphFeatureOptions::class.java)
        val cycles: GraphFeatureOptions = objects.newInstance(GraphFeatureOptions::class.java)
        val dependencyInjection: DependencyInjectionFeatureOptions =
            objects.newInstance(DependencyInjectionFeatureOptions::class.java)
        val sources: SourceFeatureOptions = objects.newInstance(SourceFeatureOptions::class.java)

        fun workspace(action: FeatureToggle.() -> Unit) = workspace.action()

        fun findings(action: FindingFeatureOptions.() -> Unit) = findings.action()

        fun files(action: FeatureToggle.() -> Unit) = files.action()

        fun symbols(action: GraphFeatureOptions.() -> Unit) = symbols.action()

        fun relationships(action: GraphFeatureOptions.() -> Unit) = relationships.action()

        fun cycles(action: GraphFeatureOptions.() -> Unit) = cycles.action()

        fun dependencyInjection(action: DependencyInjectionFeatureOptions.() -> Unit) = dependencyInjection.action()

        fun sources(action: SourceFeatureOptions.() -> Unit) = sources.action()

        internal fun applyPresetConventions(preset: Property<DocxPreset>) {
            featureOptions().forEach { (feature, options) ->
                options.enabled.convention(preset.map { feature in it.defaultFeatures() })
            }
        }

        internal fun plans(): List<DocxFeaturePlan> =
            featureOptions()
                .map { (feature, options) -> options.plan(feature) }
                .sortedBy { it.feature.name }

        internal fun requiredCapabilities(plans: List<DocxFeaturePlan>): List<AnalysisCapability> =
            plans
                .filter(DocxFeaturePlan::enabled)
                .flatMap { plan ->
                    plan.feature.requiredCapabilities(
                        includeSourceContent = plan.includeSourceContent != false,
                    )
                }.distinct()
                .sortedBy(Enum<*>::name)

        private fun featureOptions(): List<Pair<DocxFeature, FeatureToggle>> =
            listOf(
                DocxFeature.WORKSPACE to workspace,
                DocxFeature.FINDINGS to findings,
                DocxFeature.FILES to files,
                DocxFeature.SYMBOLS to symbols,
                DocxFeature.RELATIONSHIPS to relationships,
                DocxFeature.CYCLES to cycles,
                DocxFeature.DEPENDENCY_INJECTION to dependencyInjection,
                DocxFeature.SOURCES to sources,
            )
    }

@DocxDslMarker
abstract class FeatureToggle
    @Inject
    constructor() {
        abstract val enabled: Property<Boolean>

        internal open fun plan(feature: DocxFeature): DocxFeaturePlan =
            DocxFeaturePlan(
                feature = feature,
                enabled = enabled.get(),
            )
    }

@DocxDslMarker
abstract class FindingFeatureOptions
    @Inject
    constructor() : FeatureToggle() {
        abstract val severities: SetProperty<FindingSeveritySelection>

        init {
            severities.convention(FindingSeveritySelection.entries.toSet())
        }

        override fun plan(feature: DocxFeature): DocxFeaturePlan =
            super.plan(feature).copy(
                severities = severities.get().sortedBy(Enum<*>::name),
            )
    }

@DocxDslMarker
abstract class GraphFeatureOptions
    @Inject
    constructor() : FeatureToggle() {
        abstract val depthMode: Property<GraphDepthMode>
        abstract val hopCount: Property<Int>
        abstract val includeDisconnected: Property<Boolean>
        abstract val relationshipKinds: SetProperty<RelationshipKindSelection>

        init {
            depthMode.convention(GraphDepthMode.FULL)
            hopCount.convention(1)
            includeDisconnected.convention(true)
            relationshipKinds.convention(RelationshipKindSelection.entries.toSet())
        }

        fun full() {
            depthMode.set(GraphDepthMode.FULL)
        }

        fun hops(count: Int) {
            require(count > 0) { "Graph hop count must be positive" }
            depthMode.set(GraphDepthMode.HOPS)
            hopCount.set(count)
        }

        override fun plan(feature: DocxFeature): DocxFeaturePlan {
            val mode = depthMode.get()
            val count = hopCount.get()
            require(mode != GraphDepthMode.HOPS || count > 0) { "Graph hop count must be positive" }
            return super.plan(feature).copy(
                depth = GraphDepthPlan(mode = mode, hops = count.takeIf { mode == GraphDepthMode.HOPS }),
                includeDisconnected = includeDisconnected.get(),
                relationshipKinds = relationshipKinds.get().sortedBy(Enum<*>::name),
            )
        }
    }

@DocxDslMarker
abstract class DependencyInjectionFeatureOptions
    @Inject
    constructor() : GraphFeatureOptions() {
        abstract val frameworks: SetProperty<DependencyInjectionFrameworkSelection>
        abstract val generatedSources: Property<GeneratedSourcePolicy>

        init {
            frameworks.convention(DependencyInjectionFrameworkSelection.entries.toSet())
            generatedSources.convention(GeneratedSourcePolicy.INCLUDE)
        }

        override fun plan(feature: DocxFeature): DocxFeaturePlan =
            super.plan(feature).copy(
                dependencyInjectionFrameworks = frameworks.get().sortedBy(Enum<*>::name),
                generatedSources = generatedSources.get(),
            )
    }

@DocxDslMarker
abstract class SourceFeatureOptions
    @Inject
    constructor() : FeatureToggle() {
        abstract val includeContent: Property<Boolean>

        init {
            includeContent.convention(true)
        }

        override fun plan(feature: DocxFeature): DocxFeaturePlan =
            super.plan(feature).copy(includeSourceContent = includeContent.get())
    }

private fun DocxPreset.defaultFeatures(): Set<DocxFeature> =
    when (this) {
        DocxPreset.FULL -> DocxFeature.entries.toSet()
        DocxPreset.FINDINGS ->
            setOf(
                DocxFeature.WORKSPACE,
                DocxFeature.FINDINGS,
                DocxFeature.FILES,
                DocxFeature.SOURCES,
            )
        DocxPreset.ARCHITECTURE ->
            setOf(
                DocxFeature.WORKSPACE,
                DocxFeature.FILES,
                DocxFeature.SYMBOLS,
                DocxFeature.RELATIONSHIPS,
                DocxFeature.CYCLES,
                DocxFeature.SOURCES,
            )
        DocxPreset.DEPENDENCY_INJECTION ->
            setOf(
                DocxFeature.WORKSPACE,
                DocxFeature.FILES,
                DocxFeature.SYMBOLS,
                DocxFeature.RELATIONSHIPS,
                DocxFeature.DEPENDENCY_INJECTION,
                DocxFeature.SOURCES,
            )
        DocxPreset.NONE -> emptySet()
    }

private fun DocxFeature.requiredCapabilities(includeSourceContent: Boolean): List<AnalysisCapability> =
    when (this) {
        DocxFeature.WORKSPACE -> listOf(AnalysisCapability.WORKSPACE_STRUCTURE)
        DocxFeature.FINDINGS -> listOf(AnalysisCapability.SOURCE_METADATA, AnalysisCapability.FINDINGS)
        DocxFeature.FILES -> listOf(AnalysisCapability.SOURCE_METADATA)
        DocxFeature.SYMBOLS -> listOf(AnalysisCapability.SYMBOLS)
        DocxFeature.RELATIONSHIPS -> listOf(AnalysisCapability.SYMBOLS, AnalysisCapability.RELATIONSHIPS)
        DocxFeature.CYCLES ->
            listOf(
                AnalysisCapability.SYMBOLS,
                AnalysisCapability.RELATIONSHIPS,
                AnalysisCapability.CYCLES,
            )
        DocxFeature.DEPENDENCY_INJECTION ->
            listOf(AnalysisCapability.SYMBOLS, AnalysisCapability.DEPENDENCY_INJECTION)
        DocxFeature.SOURCES ->
            if (includeSourceContent) {
                listOf(AnalysisCapability.SOURCE_CONTENT)
            } else {
                listOf(AnalysisCapability.SOURCE_METADATA)
            }
    }

private fun String.requireSelectionName(label: String): String =
    trim().also { require(it.isNotEmpty()) { "$label name must not be blank" } }

private fun String.requireRelativePath(label: String): String =
    trim().also { path ->
        require(path.isNotEmpty()) { "$label must not be blank" }
        require(!path.startsWith('/') && '\\' !in path) { "$label must be a normalized relative path" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "$label must not contain empty, current, or parent segments"
        }
    }
