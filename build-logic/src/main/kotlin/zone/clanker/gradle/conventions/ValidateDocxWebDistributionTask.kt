package zone.clanker.gradle.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/** Validates the stable static-site boundary consumed by the DOCX Gradle plugin. */
@CacheableTask
abstract class ValidateDocxWebDistributionTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distributionDirectory: DirectoryProperty

    @get:Input
    abstract val forbiddenWorkspacePath: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val canonicalThemeStylesheet: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val canonicalDashboardStylesheet: RegularFileProperty

    init {
        group = "verification"
        description = "Validate the precompiled DOCX viewer distribution"
    }

    @TaskAction
    fun validateDistribution() {
        val directory = distributionDirectory.get().asFile
        val index = directory.resolve("index.html")
        val assets = directory.resolve("assets")
        val manifest = directory.resolve("data/manifest.json")
        val workspace = directory.resolve("data/workspace.json")
        val atlasOverview = directory.resolve("data/atlas-overview.json")
        val atlasOverviews = directory.resolve("data/atlas-overviews.json")
        val projectShards = directory.resolve("data/shards")
        val themeStylesheet = assets.resolve("atlas/theme.css")
        val dashboardStylesheet = assets.resolve("atlas/dashboard.css")
        val d3Runtime = assets.resolve("vendor/d3-7.9.0/d3.min.js")
        val d3License = assets.resolve("vendor/d3-7.9.0/LICENSE")
        val d3Bridge = assets.resolve("docx-atlas-d3.js")
        val layoutCore = assets.resolve("docx-atlas-layout.js")
        val layoutWorker = assets.resolve("docx-atlas-layout-worker.js")
        val canvasRenderer = assets.resolve("docx-atlas-map.js")
        require(index.isFile) { "DOCX web distribution must contain root index.html" }
        require(assets.isDirectory) { "DOCX web distribution must contain assets/" }
        require(manifest.isFile) { "DOCX web distribution must contain data/manifest.json" }
        require(workspace.isFile) { "DOCX web distribution must contain data/workspace.json" }
        require(atlasOverview.isFile) { "DOCX web distribution must contain data/atlas-overview.json" }
        require(atlasOverviews.isFile) { "DOCX web distribution must contain data/atlas-overviews.json" }
        require(projectShards.isDirectory && projectShards.listFiles().orEmpty().any { it.extension == "json" }) {
            "DOCX web distribution must contain a project shard beneath data/shards/"
        }

        val assetFiles = assets.walkTopDown().filter { it.isFile }.toList()
        require(assetFiles.any { it.extension == "wasm" }) { "DOCX web assets must contain a Wasm runtime" }
        require(assetFiles.any { it.extension == "js" || it.extension == "mjs" }) {
            "DOCX web assets must contain a JavaScript bootstrap"
        }
        require(assetFiles.any { it.extension == "css" }) { "DOCX web assets must contain its stylesheet" }
        require(themeStylesheet.isFile) { "DOCX web assets must contain the canonical Atlas theme" }
        require(dashboardStylesheet.isFile) { "DOCX web assets must contain the canonical Atlas dashboard cascade" }
        require(d3Runtime.isFile) { "DOCX web assets must contain the approved local D3 7.9.0 runtime" }
        require(d3License.isFile) { "DOCX web assets must contain the D3 license" }
        require(d3Bridge.isFile) { "DOCX web assets must contain the narrow Kotlin/Wasm-to-D3 bridge" }
        require(layoutCore.isFile) { "DOCX web assets must contain the shared semantic-layout core" }
        require(layoutWorker.isFile) { "DOCX web assets must contain the semantic-layout Web Worker" }
        require(canvasRenderer.isFile) { "DOCX web assets must contain the bounded Canvas semantic-map renderer" }
        require(themeStylesheet.readBytes().contentEquals(canonicalThemeStylesheet.get().asFile.readBytes())) {
            "DOCX Atlas theme must be an exact copy of the canonical SRCX theme.css"
        }
        require(dashboardStylesheet.readBytes().contentEquals(canonicalDashboardStylesheet.get().asFile.readBytes())) {
            "DOCX Atlas dashboard must preserve the complete ordered canonical dashboard.css cascade"
        }
        require(d3Runtime.length() >= MINIMUM_D3_RUNTIME_BYTES) {
            "DOCX D3 runtime is unexpectedly small and may not contain the complete distribution"
        }
        require(d3Runtime.bufferedReader().use { reader -> reader.readLine() } == D3_VERSION_BANNER) {
            "DOCX D3 runtime must be the approved 7.9.0 distribution"
        }
        require(assetFiles.none { it.name == LEGACY_GRAPH_CONTROLLER }) {
            "DOCX must keep graph ownership in Kotlin/Wasm instead of bundling the legacy graph controller"
        }
        validateD3Bridge(d3Bridge)
        validateLayoutWorker(layoutCore, layoutWorker)
        validateCanvasRenderer(canvasRenderer)
        validatePortableFiles(directory)

        val document = index.readText()
        validateAtlasShell(document)
        INDEX_LOCAL_RESOURCE
            .findAll(document)
            .map { match -> match.groupValues[1] }
            .filterNot { path -> path.startsWith("http://") || path.startsWith("https://") }
            .forEach { path ->
                require(path.startsWith("assets/")) { "Viewer runtime resources must live beneath assets/: $path" }
                require(directory.resolve(path).isFile) { "index.html references a missing viewer resource: $path" }
            }
    }

    private fun validateD3Bridge(bridge: File) {
        val source = bridge.readText()
        require(JSON_PARSE.findAll(source).count() == 1) {
            "DOCX D3 bridge may parse only the ephemeral kotlinx-serialized AtlasFrame envelope"
        }
        require(JSON_STRINGIFY.findAll(source).count() == 1) {
            "DOCX D3 bridge may stringify only its ephemeral renderer snapshot"
        }
        require(!FETCH_CALL.containsMatchIn(source)) {
            "DOCX D3 bridge must not load report, manifest, or shard data"
        }
        require(source.contains(D3_BRIDGE_GLOBAL)) {
            "DOCX D3 bridge must expose the narrow lifecycle boundary"
        }
    }

    private fun validateAtlasShell(document: String) {
        ATLAS_INDEX_MARKERS.forEach { marker ->
            require(document.contains(marker)) { "DOCX index is missing the Atlas shell contract marker: $marker" }
        }
        val templateStart = document.indexOf(ATLAS_TEMPLATE_START)
        require(templateStart >= 0) { "DOCX index must contain the Atlas interop template" }
        val templateEnd = document.indexOf(ATLAS_TEMPLATE_END, templateStart)
        require(templateEnd > templateStart) {
            "DOCX index must contain the complete Atlas interop template"
        }
        val template = document.substring(templateStart, templateEnd)
        ATLAS_TEMPLATE_RESOURCE_LINKS.forEach { resource ->
            require(template.contains(resource)) {
                "DOCX Atlas interop template must carry its local stylesheet link: $resource"
            }
        }
        require(document.indexOf(D3_SCRIPT_PATH) < document.indexOf(D3_BRIDGE_SCRIPT_PATH)) {
            "The approved D3 runtime must load before its narrow Kotlin/Wasm bridge"
        }
        require(document.indexOf(D3_BRIDGE_SCRIPT_PATH) < document.indexOf(DOCX_VIEWER_SCRIPT_PATH)) {
            "The D3 bridge must load before the Kotlin/Wasm viewer bootstrap"
        }
        require(document.indexOf(D3_BRIDGE_SCRIPT_PATH) < document.indexOf(CANVAS_RENDERER_SCRIPT_PATH)) {
            "The small-frame D3 bridge must load before the Canvas semantic-map renderer"
        }
        require(document.indexOf(LAYOUT_CORE_SCRIPT_PATH) < document.indexOf(CANVAS_RENDERER_SCRIPT_PATH)) {
            "The shared semantic-layout core must load before the Canvas semantic-map renderer"
        }
        require(document.indexOf(CANVAS_RENDERER_SCRIPT_PATH) < document.indexOf(DOCX_VIEWER_SCRIPT_PATH)) {
            "The Canvas semantic-map renderer must load before the Kotlin/Wasm viewer bootstrap"
        }
    }

    private fun validateLayoutWorker(
        core: File,
        worker: File,
    ) {
        val coreSource = core.readText()
        val workerSource = worker.readText()
        require(coreSource.contains(LAYOUT_CORE_GLOBAL) && JSON_PARSE.findAll(coreSource).count() == 1) {
            "DOCX layout core must expose one bounded-slice parse/compute boundary"
        }
        require(workerSource.contains(LAYOUT_WORKER_IMPORT) && workerSource.contains("layout-result")) {
            "DOCX layout worker must import the shared core and return revisioned layout results"
        }
        require(!FETCH_CALL.containsMatchIn(coreSource) && !FETCH_CALL.containsMatchIn(workerSource)) {
            "DOCX layout assets must receive bounded slices instead of loading report data"
        }
    }

    private fun validateCanvasRenderer(renderer: File) {
        val source = renderer.readText()
        require(source.contains(CANVAS_RENDERER_GLOBAL)) {
            "DOCX Canvas renderer must expose the narrow semantic-map lifecycle boundary"
        }
        require(source.contains("data-docx-atlas-canvas")) {
            "DOCX Canvas renderer must expose its runtime surface marker"
        }
        require(!FETCH_CALL.containsMatchIn(source)) {
            "DOCX Canvas renderer must receive bounded slices instead of loading report data"
        }
    }

    private fun validatePortableFiles(directory: File) {
        val workspacePath = forbiddenWorkspacePath.get()
        val workspacePathBytes = workspacePath.encodeToByteArray()
        directory
            .walkTopDown()
            .filter(File::isFile)
            .forEach { file ->
                val relativePath = file.relativeTo(directory).invariantSeparatorsPath
                val content = file.readBytes()
                require(!content.containsSequence(workspacePathBytes)) {
                    "DOCX web distribution contains the workspace path: $relativePath"
                }
                require(!ABSOLUTE_FILE_URI.containsMatchIn(content.toString(Charsets.ISO_8859_1))) {
                    "DOCX web distribution contains an absolute file URI: $relativePath"
                }
            }
    }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false
        return (0..size - sequence.size).any { offset ->
            sequence.indices.all { index -> this[offset + index] == sequence[index] }
        }
    }

    private companion object {
        const val MINIMUM_D3_RUNTIME_BYTES = 250_000L
        const val D3_VERSION_BANNER = "// https://d3js.org v7.9.0 Copyright 2010-2023 Mike Bostock"
        const val LEGACY_GRAPH_CONTROLLER = "architecture-graph.js"
        const val ATLAS_TEMPLATE_START = "<template id=\"docx-atlas-template\">"
        const val ATLAS_TEMPLATE_END = "</template>"
        const val D3_SCRIPT_PATH = "assets/vendor/d3-7.9.0/d3.min.js"
        const val D3_BRIDGE_SCRIPT_PATH = "assets/docx-atlas-d3.js"
        const val LAYOUT_CORE_SCRIPT_PATH = "assets/docx-atlas-layout.js"
        const val CANVAS_RENDERER_SCRIPT_PATH = "assets/docx-atlas-map.js"
        const val DOCX_VIEWER_SCRIPT_PATH = "assets/docx-viewer.js"
        const val D3_BRIDGE_GLOBAL = "global.docxAtlasD3 = Object.freeze"
        const val LAYOUT_CORE_GLOBAL = "global.docxAtlasLayout = Object.freeze"
        const val LAYOUT_WORKER_IMPORT = "importScripts(\"docx-atlas-layout.js\")"
        const val CANVAS_RENDERER_GLOBAL = "global.docxAtlasMap = Object.freeze"
        val JSON_PARSE = Regex("""\bJSON\.parse\(""")
        val JSON_STRINGIFY = Regex("""\bJSON\.stringify\(""")
        val FETCH_CALL = Regex("""\bfetch\s*\(""")
        val ATLAS_TEMPLATE_RESOURCE_LINKS =
            listOf(
                "assets/atlas/theme.css",
                "assets/atlas/dashboard.css",
                "assets/docx.css",
            )
        val ATLAS_INDEX_MARKERS =
            listOf(
                "id=\"docx-app\"",
                ATLAS_TEMPLATE_START,
                "data-docx-atlas-surface",
                "data-srcx-architecture-graph",
                "data-srcx-graph-lens=\"files\"",
                "data-srcx-graph-density=\"normal\"",
                "data-srcx-graph-controls",
                "data-docx-selected-project",
                "data-docx-selected-build-file",
                "data-srcx-graph-navigator",
                "data-docx-atlas-host",
                "data-srcx-graph-svg",
                "data-docx-atlas-svg",
                "data-srcx-detail-resize",
                "data-srcx-detail",
                "data-srcx-atlas-guide",
                "id=\"builds\"",
                "data-docx-build-matrix",
                "data-docx-build-edge-routes",
                "id=\"health\"",
                "data-docx-symbol-bar",
                "data-docx-severity-grid",
                "data-docx-hub-bars",
                "data-docx-coverage-grid",
                "id=\"findings\"",
                "data-srcx-finding-controls",
                "data-docx-finding-list",
                D3_SCRIPT_PATH,
                D3_BRIDGE_SCRIPT_PATH,
                LAYOUT_CORE_SCRIPT_PATH,
                CANVAS_RENDERER_SCRIPT_PATH,
            )
        val INDEX_LOCAL_RESOURCE = Regex("(?:src|href)=\\\"([^\\\"#?]+)")
        val ABSOLUTE_FILE_URI =
            Regex("""(?i)file://(?:/[^"'\s\\]+|[a-z0-9._-]+/[^"'\s\\]+)""")
    }
}
