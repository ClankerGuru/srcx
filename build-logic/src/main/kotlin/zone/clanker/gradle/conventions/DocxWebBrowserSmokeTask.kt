package zone.clanker.gradle.conventions

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Exercises the staged DOCX production site and retains native Chrome screenshots/performance evidence. */
@DisableCachingByDefault(because = "Runs an installed browser against a temporary loopback server")
abstract class DocxWebBrowserSmokeTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distributionDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val chromeExecutable: Property<String>

    @get:Input
    @get:Optional
    abstract val externalReportUrl: Property<String>

    @get:Input
    @get:Optional
    abstract val referenceReportUrl: Property<String>

    @get:Input
    abstract val readyCaptureOnly: Property<Boolean>

    @get:Input
    abstract val browserProofEnabled: Property<Boolean>

    @get:Input
    abstract val externalNodeSelectionOnly: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonicalThemeStylesheet: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonicalDashboardStylesheet: RegularFileProperty

    @get:OutputDirectory
    abstract val screenshotDirectory: DirectoryProperty

    init {
        group = "verification"
        description =
            "Exercise DOCX loading, responsive layout, catalog controls, graph transforms, and selection in Chrome"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verifyInBrowser() {
        val distributionRoot = distributionDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val atlasOverview = distributionRoot.resolve(ATLAS_OVERVIEW_PATH)
        val atlasOverviews = distributionRoot.resolve(ATLAS_OVERVIEWS_PATH)
        if (!atlasOverview.toFile().isFile) {
            throw GradleException("DOCX browser smoke requires $ATLAS_OVERVIEW_PATH")
        }
        if (!atlasOverviews.toFile().isFile) {
            throw GradleException("DOCX browser smoke requires $ATLAS_OVERVIEWS_PATH")
        }
        requireWorkerAsset(
            distributionRoot.resolve(ATLAS_LAYOUT_PATH),
            requiredMarkers = listOf("global.docxAtlasLayout", "compute"),
        )
        requireWorkerAsset(
            distributionRoot.resolve(ATLAS_LAYOUT_WORKER_PATH),
            requiredMarkers = listOf("importScripts(\"docx-atlas-layout.js\")", "layout-result"),
        )
        requireCanonicalStylesheet(
            distributionRoot.resolve(ATLAS_THEME_PATH),
            canonicalThemeStylesheet.get().asFile,
        )
        requireCanonicalStylesheet(
            distributionRoot.resolve(ATLAS_DASHBOARD_PATH),
            canonicalDashboardStylesheet.get().asFile,
        )
        val executor =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "docx-web-browser-smoke-server").apply { isDaemon = true }
            }
        val server = HttpServer.create(InetSocketAddress(LOOPBACK_HOST, 0), 0)
        server.executor = executor
        server.createContext("/") { exchange -> serve(exchange, distributionRoot) }
        server.start()

        val chrome = resolveChromeExecutable()
        val browserResult =
            runCatching {
                val proof =
                    DocxWebBrowserProof(
                        chromeExecutable = chrome,
                        baseUrl = "http://$LOOPBACK_HOST:${server.address.port}/",
                        reportDirectory = screenshotDirectory.get().asFile,
                        temporaryDirectory = temporaryDir.resolve("cdp-browser-proof"),
                        externalReportUrl = externalReportUrl.orNull,
                        referenceReportUrl = referenceReportUrl.orNull,
                    )
                if (readyCaptureOnly.get()) {
                    proof.captureReadyDemo()
                } else if (externalNodeSelectionOnly.get()) {
                    proof.verifyExternalNodeSelectionOnly()
                } else {
                    DocxWebSmokeScenario.entries.forEach { scenario ->
                        runChrome(chrome, distributionRoot, server.address.port, scenario)
                    }
                    if (browserProofEnabled.get()) proof.run()
                }
            }
        server.stop(0)
        executor.shutdownNow()
        browserResult.getOrThrow()
    }

    private fun requireCanonicalStylesheet(
        stagedPath: Path,
        canonicalFile: File,
    ) {
        val stagedFile = stagedPath.toFile()
        if (!stagedFile.isFile || !stagedFile.readBytes().contentEquals(canonicalFile.readBytes())) {
            throw GradleException(
                "DOCX browser smoke requires ${stagedPath.fileName} to be byte-identical to " +
                    canonicalFile.invariantSeparatorsPath,
            )
        }
    }

    private fun requireWorkerAsset(
        stagedPath: Path,
        requiredMarkers: List<String>,
    ) {
        val stagedFile = stagedPath.toFile()
        val content = stagedFile.takeIf(File::isFile)?.readText().orEmpty()
        if (content.isBlank() || requiredMarkers.any { marker -> marker !in content }) {
            throw GradleException(
                "DOCX browser smoke requires a complete ${stagedPath.fileName} worker asset",
            )
        }
    }

    private fun runChrome(
        chromeExecutable: String,
        distributionRoot: Path,
        port: Int,
        scenario: DocxWebSmokeScenario,
    ) {
        val standardOutput = temporaryDir.resolve("chrome-${scenario.id}-stdout.txt")
        val standardError = temporaryDir.resolve("chrome-${scenario.id}-stderr.txt")
        val profileDirectory = temporaryDir.resolve("chrome-${scenario.id}-profile").apply { mkdirs() }
        listOf("SingletonLock", "SingletonSocket", "SingletonCookie", "DevToolsActivePort").forEach { name ->
            profileDirectory.resolve(name).delete()
        }
        val reportUrl = "http://127.0.0.1:$port/?docx-smoke=1&scenario=${scenario.id}"
        val process =
            ProcessBuilder(
                chromeExecutable,
                "--headless=new",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-background-networking",
                "--disable-default-apps",
                "--disable-extensions",
                "--disable-sync",
                "--enable-unsafe-swiftshader",
                "--no-first-run",
                "--user-data-dir=${profileDirectory.absolutePath}",
                "--window-size=${scenario.width},${scenario.height}",
                "--virtual-time-budget=$VIRTUAL_TIME_BUDGET_MILLIS",
                "--dump-dom",
                reportUrl,
            ).redirectOutput(standardOutput)
                .redirectError(standardError)
                .start()

        val browserResult =
            runCatching {
                awaitReadyState(process, standardOutput, standardError, distributionRoot, reportUrl, scenario)
            }
        if (process.isAlive) {
            terminate(process)
        }
        browserResult.getOrElse { error ->
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                throw GradleException("DOCX browser smoke was interrupted while loading $reportUrl", error)
            }
            throw error
        }
    }

    private fun awaitReadyState(
        process: Process,
        standardOutput: File,
        standardError: File,
        distributionRoot: Path,
        reportUrl: String,
        scenario: DocxWebSmokeScenario,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val renderedDocument = standardOutput.readText()
            val rootTag = docxRootTag(renderedDocument)
            val state = rootTag?.let { tag -> rootAttribute(tag, STATE_ATTRIBUTE) }
            val smokeResult = rootTag?.let { tag -> rootAttribute(tag, SMOKE_RESULT_ATTRIBUTE) }
            if (state == READY_STATE && smokeResult == SMOKE_PASSED) {
                val violation = readyProbeViolation(requireNotNull(rootTag), scenario)
                if (violation != null) {
                    throw GradleException(
                        "DOCX browser smoke ${scenario.id} reached ready with an invalid viewport: $violation\n" +
                            evidence(standardOutput, standardError),
                    )
                }
                return
            }
            if (smokeResult == SMOKE_FAILED) {
                val smokeError = rootTag?.let { tag -> rootAttribute(tag, SMOKE_ERROR_ATTRIBUTE) }.orEmpty()
                throw GradleException(
                    "DOCX browser smoke ${scenario.id} interaction failed: $smokeError\n" +
                        evidence(standardOutput, standardError),
                )
            }
            if (state in FAILED_STATES) {
                throw GradleException(
                    "DOCX browser smoke ${scenario.id} reached state $state while serving $distributionRoot\n" +
                        evidence(standardOutput, standardError),
                )
            }
            if (!process.isAlive) {
                throw GradleException(
                    "DOCX browser smoke ${scenario.id} exited with Chrome code ${process.exitValue()}, state " +
                        "${state ?: "missing"}, and interaction ${smokeResult ?: "missing"} while serving " +
                        "$distributionRoot\n" +
                        evidence(standardOutput, standardError),
                )
            }
            process.waitFor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        }
        throw GradleException(
            "DOCX browser smoke ${scenario.id} timed out after $PROCESS_TIMEOUT_SECONDS seconds loading $reportUrl " +
                "with state ${docxRootState(standardOutput.readText()) ?: "missing"}\n" +
                evidence(standardOutput, standardError),
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun readyProbeViolation(
        rootTag: String,
        scenario: DocxWebSmokeScenario,
    ): String? {
        val viewportWidth =
            positiveMeasurement(rootTag, VIEWPORT_WIDTH_ATTRIBUTE)
                ?: return "$VIEWPORT_WIDTH_ATTRIBUTE is missing or invalid"
        val viewportHeight =
            positiveMeasurement(rootTag, VIEWPORT_HEIGHT_ATTRIBUTE)
                ?: return "$VIEWPORT_HEIGHT_ATTRIBUTE is missing or invalid"
        val appWidth =
            positiveMeasurement(rootTag, APP_WIDTH_ATTRIBUTE)
                ?: return "$APP_WIDTH_ATTRIBUTE is missing or invalid"
        val appHeight =
            positiveMeasurement(rootTag, APP_HEIGHT_ATTRIBUTE)
                ?: return "$APP_HEIGHT_ATTRIBUTE is missing or invalid"
        val canvasHeight =
            positiveMeasurement(rootTag, CANVAS_HEIGHT_ATTRIBUTE)
                ?: return "$CANVAS_HEIGHT_ATTRIBUTE is missing or invalid"
        val contentHeight =
            positiveMeasurement(rootTag, CONTENT_HEIGHT_ATTRIBUTE)
                ?: return "$CONTENT_HEIGHT_ATTRIBUTE is missing or invalid"
        val graphViewportHeight =
            positiveMeasurement(rootTag, GRAPH_VIEWPORT_HEIGHT_ATTRIBUTE)
                ?: return "$GRAPH_VIEWPORT_HEIGHT_ATTRIBUTE is missing or invalid"
        val catalogContent =
            rootAttribute(rootTag, CATALOG_CONTENT_ATTRIBUTE)
                ?: return "$CATALOG_CONTENT_ATTRIBUTE is missing"
        val selectedProjectContent =
            rootAttribute(rootTag, SELECTED_PROJECT_CONTENT_ATTRIBUTE)
                ?: return "$SELECTED_PROJECT_CONTENT_ATTRIBUTE is missing"
        val layoutMode =
            rootAttribute(rootTag, LAYOUT_MODE_ATTRIBUTE)
                ?: return "$LAYOUT_MODE_ATTRIBUTE is missing"
        val horizontalOverflow =
            rootAttribute(rootTag, HORIZONTAL_OVERFLOW_ATTRIBUTE)
                ?: return "$HORIZONTAL_OVERFLOW_ATTRIBUTE is missing"
        val smokeTrace =
            rootAttribute(rootTag, SMOKE_TRACE_ATTRIBUTE)
                ?: return "$SMOKE_TRACE_ATTRIBUTE is missing"
        val missingTraceStep = scenario.expectedTrace.firstOrNull { step -> step !in smokeTrace.split('|') }

        return when {
            abs(viewportWidth - scenario.width) > GEOMETRY_TOLERANCE_PIXELS ->
                "viewport width $viewportWidth does not match requested ${scenario.width}"
            viewportHeight < scenario.height * MINIMUM_BROWSER_HEIGHT_RATIO ->
                "viewport height $viewportHeight is too small for requested ${scenario.height}"
            viewportHeight > scenario.height + GEOMETRY_TOLERANCE_PIXELS ->
                "viewport height $viewportHeight exceeds requested ${scenario.height}"
            appWidth < viewportWidth * VIEWPORT_FILL_RATIO ->
                "app width $appWidth does not fill viewport width $viewportWidth"
            appWidth > viewportWidth + GEOMETRY_TOLERANCE_PIXELS ->
                "app width $appWidth exceeds viewport width $viewportWidth"
            appHeight < viewportHeight * VIEWPORT_FILL_RATIO ->
                "app height $appHeight does not fill viewport height $viewportHeight"
            appHeight > viewportHeight + GEOMETRY_TOLERANCE_PIXELS ->
                "app height $appHeight exceeds viewport height $viewportHeight"
            abs(canvasHeight - graphViewportHeight) > GEOMETRY_TOLERANCE_PIXELS ->
                "canvas height $canvasHeight and embedded graph height $graphViewportHeight disagree"
            contentHeight < viewportHeight * MINIMUM_CONTENT_RATIO ->
                "content height $contentHeight is not substantial within viewport height $viewportHeight"
            contentHeight > appHeight + GEOMETRY_TOLERANCE_PIXELS ->
                "content height $contentHeight exceeds app height $appHeight"
            graphViewportHeight < scenario.minimumGraphViewportHeight ->
                "graph viewport height $graphViewportHeight is below ${scenario.minimumGraphViewportHeight}"
            catalogContent != EXPECTED_CATALOG_CONTENT ->
                "catalog content '$catalogContent' does not identify the fixture workspace"
            EXPECTED_PROJECT_CONTENT_TOKENS.any { token -> !selectedProjectContent.contains(token) } ->
                "selected-project content '$selectedProjectContent' is incomplete"
            layoutMode != scenario.layoutMode ->
                "layout mode '$layoutMode' does not match expected '${scenario.layoutMode}'"
            horizontalOverflow != "false" ->
                "horizontal overflow was reported as '$horizontalOverflow'"
            missingTraceStep != null ->
                "interaction trace '$smokeTrace' is missing '$missingTraceStep'"
            else -> null
        }
    }

    private fun positiveMeasurement(
        rootTag: String,
        attribute: String,
    ): Double? =
        rootAttribute(rootTag, attribute)
            ?.toDoubleOrNull()
            ?.takeIf { value -> value.isFinite() && value > 0.0 }

    private fun docxRootTag(renderedDocument: String): String? =
        DOCX_ROOT_PATTERN.find(renderedDocument)?.value

    private fun docxRootState(renderedDocument: String): String? =
        docxRootTag(renderedDocument)?.let { rootTag -> rootAttribute(rootTag, STATE_ATTRIBUTE) }

    private fun rootAttribute(
        rootTag: String,
        attribute: String,
    ): String? =
        Regex("""\b${Regex.escape(attribute)}=["']([^"']*)["']""")
            .find(rootTag)
            ?.groupValues
            ?.get(1)

    private fun resolveChromeExecutable(): String {
        val configured = chromeExecutable.orNull?.trim().orEmpty()
        val candidates =
            buildList {
                if (configured.isNotEmpty()) {
                    add(configured)
                }
                addAll(STANDARD_CHROME_LOCATIONS)
                val pathDirectories = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
                pathDirectories.forEach { directory ->
                    CHROME_COMMANDS.forEach { command -> add(File(directory, command).absolutePath) }
                }
            }
        return candidates
            .asSequence()
            .map(::File)
            .firstOrNull { candidate -> candidate.isFile && candidate.canExecute() }
            ?.absolutePath
            ?: throw GradleException(
                "DOCX browser smoke requires Chrome or Chromium; set CHROME_BIN to its executable",
            )
    }

    private fun serve(
        exchange: HttpExchange,
        distributionRoot: Path,
    ) {
        val responseResult =
            runCatching {
                if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                    exchange.sendResponseHeaders(METHOD_NOT_ALLOWED, -1)
                    return@runCatching
                }
                val relativePath = exchange.requestURI.path.removePrefix("/").ifBlank { "index.html" }
                val requestedFile = distributionRoot.resolve(relativePath).normalize()
                if (!requestedFile.startsWith(distributionRoot) || !requestedFile.toFile().isFile) {
                    exchange.sendResponseHeaders(NOT_FOUND, -1)
                    return@runCatching
                }
                val scenario = DocxWebSmokeScenario.fromQuery(exchange.requestURI.rawQuery)
                val bytes =
                    if (requestedFile.fileName.toString() == "index.html" && scenario != null) {
                        val instrumented = DocxWebSmokeDriver.instrument(requestedFile.toFile().readText(), scenario)
                        instrumented
                            .let { html ->
                                if (exchange.requestURI.rawQuery.isCaptureRequest()) {
                                    inlineCaptureStyles(html, distributionRoot)
                                } else {
                                    html
                                }
                            }
                            .toByteArray(Charsets.UTF_8)
                    } else {
                        requestedFile.toFile().readBytes()
                    }
                exchange.responseHeaders.set("Content-Type", contentType(requestedFile))
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.sendResponseHeaders(OK, if (exchange.requestMethod == "HEAD") -1 else bytes.size.toLong())
                if (exchange.requestMethod == "GET") {
                    exchange.responseBody.use { response -> response.write(bytes) }
                }
            }
        val closeResult = runCatching { exchange.close() }
        responseResult.onFailure { error ->
            if (error is Exception) {
                logger.warn("DOCX browser smoke could not serve ${exchange.requestURI}", error)
            }
        }
        closeResult.getOrThrow()
        responseResult.getOrElse { error -> throw error }
    }

    private fun inlineCaptureStyles(
        html: String,
        distributionRoot: Path,
    ): String =
        CAPTURE_STYLESHEET_PATHS.fold(html) { document, path ->
            val link = Regex("""<link rel="stylesheet" href="${Regex.escape(path)}"[^>]*>""")
            val stylesheet = distributionRoot.resolve(path).toFile().readText()
            document.replace(link, "<style data-docx-capture-style=\"$path\">$stylesheet</style>")
        }

    private fun String?.isCaptureRequest(): Boolean =
        orEmpty()
            .split('&')
            .any { parameter -> parameter == "capture=1" }

    private fun contentType(path: Path): String =
        when (path.fileName.toString().substringAfterLast('.', missingDelimiterValue = "")) {
            "css" -> "text/css; charset=utf-8"
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }

    private fun terminate(process: Process) {
        process.descendants().use { descendants -> descendants.forEach { descendant -> descendant.destroy() } }
        process.destroy()
        if (!process.waitFor(TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS)) {
            process.descendants().use { descendants ->
                descendants.forEach { descendant -> descendant.destroyForcibly() }
            }
            process.destroyForcibly()
            process.waitFor(TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun evidence(
        standardOutput: File,
        standardError: File,
    ): String =
        "Chrome DOM:\n${standardOutput.readText().takeLast(EVIDENCE_LIMIT)}\n" +
            "Chrome stderr:\n${standardError.readText().takeLast(EVIDENCE_LIMIT)}"

    private companion object {
        val DOCX_ROOT_PATTERN = Regex("""<[^>]*\bid=["']docx-app["'][^>]*>""")
        val FAILED_STATES = setOf("fatal", "catalog-failed", "project-failed")
        val EXPECTED_PROJECT_CONTENT_TOKENS =
            setOf(":app", "symbols=2", "relationships=1", "findings=1")
        val CHROME_COMMANDS = listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser")
        val STANDARD_CHROME_LOCATIONS =
            listOf(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
            )
        val CAPTURE_STYLESHEET_PATHS = listOf(ATLAS_THEME_PATH, ATLAS_DASHBOARD_PATH, "assets/docx.css")
        const val STATE_ATTRIBUTE = "data-docx-state"
        const val VIEWPORT_WIDTH_ATTRIBUTE = "data-docx-viewport-width"
        const val VIEWPORT_HEIGHT_ATTRIBUTE = "data-docx-viewport-height"
        const val APP_WIDTH_ATTRIBUTE = "data-docx-app-width"
        const val APP_HEIGHT_ATTRIBUTE = "data-docx-app-height"
        const val CANVAS_HEIGHT_ATTRIBUTE = "data-docx-canvas-height"
        const val CONTENT_HEIGHT_ATTRIBUTE = "data-docx-content-height"
        const val GRAPH_VIEWPORT_HEIGHT_ATTRIBUTE = "data-docx-graph-viewport-height"
        const val CATALOG_CONTENT_ATTRIBUTE = "data-docx-catalog-content"
        const val SELECTED_PROJECT_CONTENT_ATTRIBUTE = "data-docx-selected-project-content"
        const val LAYOUT_MODE_ATTRIBUTE = "data-docx-layout-mode"
        const val HORIZONTAL_OVERFLOW_ATTRIBUTE = "data-docx-horizontal-overflow"
        const val SMOKE_RESULT_ATTRIBUTE = "data-docx-smoke-result"
        const val SMOKE_TRACE_ATTRIBUTE = "data-docx-smoke-trace"
        const val SMOKE_ERROR_ATTRIBUTE = "data-docx-smoke-error"
        const val EXPECTED_CATALOG_CONTENT = "Fixture Workspace"
        const val ATLAS_OVERVIEW_PATH = "data/atlas-overview.json"
        const val ATLAS_OVERVIEWS_PATH = "data/atlas-overviews.json"
        const val ATLAS_THEME_PATH = "assets/atlas/theme.css"
        const val ATLAS_DASHBOARD_PATH = "assets/atlas/dashboard.css"
        const val ATLAS_LAYOUT_PATH = "assets/docx-atlas-layout.js"
        const val ATLAS_LAYOUT_WORKER_PATH = "assets/docx-atlas-layout-worker.js"
        const val READY_STATE = "ready"
        const val SMOKE_PASSED = "passed"
        const val SMOKE_FAILED = "failed"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val VIEWPORT_FILL_RATIO = 0.99
        const val MINIMUM_CONTENT_RATIO = 0.5
        const val MINIMUM_BROWSER_HEIGHT_RATIO = 0.7
        const val GEOMETRY_TOLERANCE_PIXELS = 2.0
        const val VIRTUAL_TIME_BUDGET_MILLIS = 10_000_000
        const val PROCESS_TIMEOUT_SECONDS = 60L
        const val POLL_INTERVAL_MILLIS = 100L
        const val TERMINATION_GRACE_SECONDS = 2L
        const val EVIDENCE_LIMIT = 8_000
        const val OK = 200
        const val NOT_FOUND = 404
        const val METHOD_NOT_ALLOWED = 405
    }
}
