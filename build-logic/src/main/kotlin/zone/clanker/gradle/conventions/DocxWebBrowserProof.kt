package zone.clanker.gradle.conventions

import org.gradle.api.GradleException
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/** Captures native browser states and raw browser-performance evidence through Chrome DevTools Protocol. */
internal class DocxWebBrowserProof(
    private val chromeExecutable: String,
    private val baseUrl: String,
    private val reportDirectory: File,
    private val temporaryDirectory: File,
    private val externalReportUrl: String? = null,
    private val referenceReportUrl: String? = null,
) {
    private val report = DocxWebBrowserProofReport()
    private val expectedCaptures =
        linkedMapOf(
            "ultrawide" to CaptureSpec("atlas-ultrawide.png", DocxWebSmokeScenario.WIDE),
            "standard" to CaptureSpec("atlas-standard.png", DocxWebSmokeScenario.STANDARD),
            "tablet" to CaptureSpec("atlas-tablet.png", DocxWebSmokeScenario.TABLET),
            "narrow" to CaptureSpec("atlas-narrow.png", DocxWebSmokeScenario.COMPACT),
            "fullscreen-ultrawide" to
                CaptureSpec("atlas-fullscreen-ultrawide.png", DocxWebSmokeScenario.WIDE),
            "fullscreen-ultrawide-exit" to
                CaptureSpec("atlas-fullscreen-exit-ultrawide.png", DocxWebSmokeScenario.WIDE),
            "fullscreen-standard" to
                CaptureSpec("atlas-fullscreen-standard.png", DocxWebSmokeScenario.STANDARD),
            "fullscreen-standard-exit" to
                CaptureSpec("atlas-fullscreen-exit-standard.png", DocxWebSmokeScenario.STANDARD),
            "evidence-open" to CaptureSpec("atlas-evidence-open.png", DocxWebSmokeScenario.WIDE),
        )
    private val externalExpectedCaptures =
        linkedMapOf(
            "external-live-wide" to CaptureSpec("atlas-live-wide.png", DocxWebSmokeScenario.WIDE),
            "external-live-compact" to CaptureSpec("atlas-live-compact.png", DocxWebSmokeScenario.COMPACT),
            "external-live-fullscreen" to CaptureSpec("atlas-live-fullscreen.png", DocxWebSmokeScenario.WIDE),
            "external-live-fullscreen-exit" to
                CaptureSpec("atlas-live-fullscreen-exit.png", DocxWebSmokeScenario.WIDE),
            "external-live-evidence-open" to
                CaptureSpec("atlas-live-evidence-open.png", DocxWebSmokeScenario.WIDE),
            "external-live-ultrawide-interaction" to
                CaptureSpec("atlas-live-ultrawide-interaction.png", DocxWebSmokeScenario.WIDE),
            "external-live-standard-interaction" to
                CaptureSpec("atlas-live-standard-interaction.png", DocxWebSmokeScenario.STANDARD),
            "external-live-tablet-interaction" to
                CaptureSpec("atlas-live-tablet-interaction.png", DocxWebSmokeScenario.TABLET),
            "external-live-narrow-interaction" to
                CaptureSpec("atlas-live-narrow-interaction.png", DocxWebSmokeScenario.COMPACT),
        )
    private val referenceExpectedCaptures =
        linkedMapOf(
            "dev001-ultrawide" to CaptureSpec("atlas-dev001-ultrawide.png", DocxWebSmokeScenario.WIDE),
            "dev001-standard" to CaptureSpec("atlas-dev001-standard.png", DocxWebSmokeScenario.STANDARD),
            "dev001-tablet" to CaptureSpec("atlas-dev001-tablet.png", DocxWebSmokeScenario.TABLET),
            "dev001-narrow" to CaptureSpec("atlas-dev001-narrow.png", DocxWebSmokeScenario.COMPACT),
        )
    private val checkpointExpectedCaptures =
        linkedMapOf(
            "checkpoint-ultrawide" to CaptureSpec("atlas-checkpoint-ultrawide.png", DocxWebSmokeScenario.WIDE),
            "checkpoint-standard" to CaptureSpec("atlas-checkpoint-standard.png", DocxWebSmokeScenario.STANDARD),
            "checkpoint-tablet" to CaptureSpec("atlas-checkpoint-tablet.png", DocxWebSmokeScenario.TABLET),
            "checkpoint-narrow" to CaptureSpec("atlas-checkpoint-narrow.png", DocxWebSmokeScenario.COMPACT),
        )

    fun run() {
        reportDirectory.mkdirs()
        val startedAtMillis = System.currentTimeMillis()
        expectedArtifactFiles().forEach { artifact ->
            if (artifact.exists() && !artifact.delete()) {
                throw GradleException("Could not replace browser-proof artifact ${artifact.invariantSeparatorsPath}")
            }
        }
        var proofFailure: Throwable? = null
        val initialViewport = DocxWebSmokeScenario.WIDE.viewport()
        runCatching {
            DocxWebCdpSession.start(
                chromeExecutable = chromeExecutable,
                viewport = initialViewport,
                profileDirectory = temporaryDirectory.resolve("chrome-cdp-profile"),
                standardOutput = temporaryDirectory.resolve("chrome-cdp-stdout.txt"),
                standardError = temporaryDirectory.resolve("chrome-cdp-stderr.txt"),
            ).use { session ->
                session.addScriptToEvaluateOnNewDocument(PERFORMANCE_PROBE_SCRIPT)
                captureReadyState(session, "ultrawide", DocxWebSmokeScenario.WIDE)
                captureReadyState(session, "standard", DocxWebSmokeScenario.STANDARD)
                captureReadyState(session, "tablet", DocxWebSmokeScenario.TABLET)
                captureReadyState(session, "narrow", DocxWebSmokeScenario.COMPACT)
                captureEvidenceState(session)
                captureNativeFullscreenState(session, "fullscreen-ultrawide", DocxWebSmokeScenario.WIDE)
                captureNativeFullscreenState(session, "fullscreen-standard", DocxWebSmokeScenario.STANDARD)
                measureRendererLifecycle(session)
                val liveUrl = externalReportUrl?.trim().orEmpty()
                if (liveUrl.isNotEmpty()) {
                    exerciseExternalReport(session, liveUrl)
                } else {
                    report.addGate(
                        DocxWebProofGate(
                            name = "external-live-report-sanity",
                            status = DocxWebProofGateStatus.NOT_MEASURED,
                            target = "Regenerated FooBar ready/HUD/Canvas/worker/fullscreen/search/evidence sanity",
                            observed = "No external URL supplied",
                            detail = "Run with -PdocxWebBrowserExternalUrl=<mounted FooBar URL> after service registration.",
                        ),
                    )
                }
            }
        }.onFailure { error -> proofFailure = error }

        validateCaptures(startedAtMillis)
        report.addTimingGates()
        report.write(reportDirectory)
        val requiredFailures = report.requiredFailures()
        if (proofFailure != null) throw proofFailure as Throwable
        if (requiredFailures.isNotEmpty()) {
            throw GradleException(
                requiredFailures.joinToString(
                    prefix = "DOCX browser proof failed required gates:\n",
                    separator = "\n",
                ) { gate -> "- ${gate.name}: ${gate.observed} (${gate.target})" },
            )
        }
    }

    /** Retains a current-production visual checkpoint without running the deeper functional scenario. */
    fun captureReadyDemo() {
        reportDirectory.mkdirs()
        val captures =
            expectedCaptures.filterKeys { state ->
                state == "ultrawide" || state == "standard" || state == "tablet" || state == "narrow"
            }
        captures.values.forEach { spec -> reportDirectory.resolve(spec.fileName).delete() }
        val startedAtMillis = System.currentTimeMillis()
        DocxWebCdpSession.start(
            chromeExecutable = chromeExecutable,
            viewport = DocxWebSmokeScenario.WIDE.viewport(),
            profileDirectory = temporaryDirectory.resolve("chrome-cdp-ready-demo-profile"),
            standardOutput = temporaryDirectory.resolve("chrome-cdp-ready-demo-stdout.txt"),
            standardError = temporaryDirectory.resolve("chrome-cdp-ready-demo-stderr.txt"),
        ).use { session ->
            session.addScriptToEvaluateOnNewDocument(PERFORMANCE_PROBE_SCRIPT)
            captureReadyState(session, "ultrawide", DocxWebSmokeScenario.WIDE)
            captureReadyState(session, "standard", DocxWebSmokeScenario.STANDARD)
            captureReadyState(session, "tablet", DocxWebSmokeScenario.TABLET)
            captureReadyState(session, "narrow", DocxWebSmokeScenario.COMPACT)
            referenceReportUrl?.trim()?.takeIf(String::isNotEmpty)?.let { referenceUrl ->
                referenceExpectedCaptures.forEach { (state, spec) ->
                    captureReferenceReadyState(session, referenceUrl, state, spec)
                }
            }
            externalReportUrl?.trim()?.takeIf(String::isNotEmpty)?.let { checkpointUrl ->
                checkpointExpectedCaptures.forEach { (state, spec) ->
                    captureReferenceReadyState(session, checkpointUrl, state, spec)
                }
            }
        }
        val referenceFailures =
            referenceExpectedCaptures
                .takeIf { referenceReportUrl?.isNotBlank() == true }
                ?.let { expected -> captureFailures(expected, startedAtMillis) }
                .orEmpty()
        val checkpointFailures =
            checkpointExpectedCaptures
                .takeIf { externalReportUrl?.isNotBlank() == true }
                ?.let { expected -> captureFailures(expected, startedAtMillis) }
                .orEmpty()
        val failures = captureFailures(captures, startedAtMillis) + referenceFailures + checkpointFailures
        if (failures.isNotEmpty()) {
            throw GradleException(
                failures.joinToString(
                    prefix = "DOCX ready-capture demo failed:\n",
                    separator = "\n",
                ) { failure -> "- $failure" },
            )
        }
    }

    /** Reproduces the embedded-report scroll and trusted Canvas click without running unrelated proof phases. */
    fun verifyExternalNodeSelectionOnly() {
        val targetUrl = externalReportUrl?.trim().orEmpty().ifBlank { baseUrl }
        val scenario = DocxWebSmokeScenario.COMPACT
        DocxWebCdpSession.start(
            chromeExecutable = chromeExecutable,
            viewport = scenario.viewport(),
            profileDirectory = temporaryDirectory.resolve("chrome-cdp-node-selection-profile"),
            standardOutput = temporaryDirectory.resolve("chrome-cdp-node-selection-stdout.txt"),
            standardError = temporaryDirectory.resolve("chrome-cdp-node-selection-stderr.txt"),
        ).use { session ->
            session.addScriptToEvaluateOnNewDocument(PERFORMANCE_PROBE_SCRIPT)
            navigateExternalReport(session, targetUrl, "external-live-node-selection", scenario)
            verifyExternalDetailedOverviewInteractions(session, "external-live-node-selection")
            session.capturePng(reportDirectory.resolve("atlas-live-node-selected.png"))
        }
    }

    private fun captureReadyState(
        session: DocxWebCdpSession,
        state: String,
        scenario: DocxWebSmokeScenario,
    ) {
        navigateForCapture(session, state, scenario, captureMode = "ready")
        capture(session, state)
        collectPageMetrics(session, state)
    }

    private fun captureReferenceReadyState(
        session: DocxWebCdpSession,
        url: String,
        state: String,
        spec: CaptureSpec,
    ) {
        session.setViewport(spec.scenario.viewport())
        session.navigate(url)
        session.waitUntil("$state published dev-001 detailed Atlas") {
            session.evaluate(REFERENCE_ATLAS_READY_EXPRESSION) == true
        }
        positionAtlasForCapture(session)
        session.waitUntil("$state positioned detailed Atlas") {
            session.evaluate(REFERENCE_ATLAS_READY_EXPRESSION) == true
        }
        session.capturePng(reportDirectory.resolve(spec.fileName))
    }

    private fun exerciseExternalReport(
        session: DocxWebCdpSession,
        url: String,
    ) {
        navigateExternalReport(session, url, "external-live-wide", DocxWebSmokeScenario.WIDE)
        captureExternal(session, "external-live-wide")
        collectPageMetrics(session, "external-live-wide")
        resizeExternalReport(session, "external-live-compact", DocxWebSmokeScenario.COMPACT)
        captureExternal(session, "external-live-compact")
        collectPageMetrics(session, "external-live-compact")
        val cpuProfile = System.getProperty("docx.browser.cpuProfile") == "true"
        if (cpuProfile) session.startCpuProfile()
        runCatching {
            verifyExternalInteractionsAtEveryViewport(session, url)
        }.also {
            if (cpuProfile) {
                session.stopCpuProfile(reportDirectory.resolve("external-live-detailed-interactions.cpuprofile"))
            }
        }.getOrThrow()

        resizeExternalReport(session, "external-live-fullscreen", DocxWebSmokeScenario.WIDE)
        prepareFullscreenRestorationProbe(session)
        session.trustedClick(FULLSCREEN_BUTTON_SELECTOR)
        session.waitUntil("external native fullscreen") { nativeFullscreenState(session).isEntered }
        captureExternal(session, "external-live-fullscreen")
        session.pressEscape()
        session.waitUntil("external native fullscreen exit") {
            val state = nativeFullscreenState(session)
            !state.documentFullscreen && !state.graphFullscreen
        }
        verifyFullscreenRestoration(session, "external live fullscreen")
        captureExternal(session, "external-live-fullscreen-exit")
        collectPageMetrics(session, "external-live-fullscreen")

        if (cpuProfile) session.startCpuProfile()
        val interaction =
            runCatching {
                session.evaluateJsonObject(EXTERNAL_REPORT_INTERACTION_EXPRESSION, awaitPromise = true)
            }.also {
                if (cpuProfile) {
                    session.stopCpuProfile(reportDirectory.resolve("external-live-interaction.cpuprofile"))
                }
            }.getOrElse { error ->
                val graphRequests =
                    session
                        .networkSamplesSince(0)
                        .filter { sample -> sample.url.endsWith("/api/graph") }
                        .takeLast(1)
                graphRequests.forEach { sample ->
                    report.addSample(
                        DocxWebProofSample(
                            phase = "external-live-evidence-open",
                            source = "cdp-network",
                            metric = "failed-graph-request-body",
                            value = sample.encodedBytes.toDouble(),
                            unit = "bytes",
                            detail = sample.requestBody.orEmpty(),
                        ),
                    )
                }
                val graphRequestSummary =
                    graphRequests.joinToString(separator = "\n") { sample ->
                        "${sample.encodedBytes} bytes; request retained in browser-proof.json"
                        }
                throw GradleException("${error.message}\nCompleted graph requests:\n$graphRequestSummary", error)
            }
        val searchTerm = interaction["searchTerm"]?.toString().orEmpty()
        if (searchTerm.isBlank() || interaction["evidenceOpen"] != true) {
            throw GradleException("External Atlas did not expose a generic project search term and source evidence")
        }
        interaction.list("checkpoints").forEach { checkpoint ->
            val values = checkpoint.stringMap()
            val checkpointName = values["name"].toString()
            val runtimeBreakdown = values["runtimeBreakdown"].toString()
            report.addSample(
                DocxWebProofSample(
                    phase = "external-live-evidence-open",
                    source = "browser-proof-checkpoint",
                    metric = checkpointName,
                    value = values.number("time"),
                    unit = "navigation-ms",
                    detail = runtimeBreakdown,
                ),
            )
            listOf(
                "model-update" to "modelUpdateMs",
                "surface-update" to "surfaceUpdateMs",
                "runtime-update" to "runtimeUpdateMs",
            ).forEach { (metric, key) ->
                report.addSample(
                    DocxWebProofSample(
                        phase = "external-live-$checkpointName",
                        source = "atlas-runtime-timing",
                        metric = metric,
                        value = values.number(key),
                        unit = "ms",
                        detail = runtimeBreakdown,
                    ),
                )
            }
        }
        captureExternal(session, "external-live-evidence-open")
        session.trustedType(GLOBAL_SEARCH_INPUT_SELECTOR, searchTerm)
        session.waitUntil("external indexed global-search results") {
            session.evaluate(
                """
                (() => {
                    return (window.__docxBrowserProofQueryRoots || [document])
                        .some((queryRoot) => queryRoot.querySelector("[data-srcx-global-search-result]"));
                })()
                """.trimIndent(),
            ) == true
        }
        collectPageMetrics(session, "external-live-evidence-open")
        report.addGate(
            DocxWebProofGate(
                name = "external-live-report-sanity",
                status = DocxWebProofGateStatus.PASS,
                target = "Regenerated FooBar detailed overview, semantic drill, fullscreen, search, and evidence sanity",
                observed = "D3 stayed authoritative until deliberate scope drill; Canvas/worker served semantic evidence",
                detail = url,
                required = true,
            ),
        )
    }

    private fun verifyExternalInteractionsAtEveryViewport(
        session: DocxWebCdpSession,
        url: String,
    ) {
        val scenarios =
            listOf(
                "external-live-ultrawide-interaction" to DocxWebSmokeScenario.WIDE,
                "external-live-standard-interaction" to DocxWebSmokeScenario.STANDARD,
                "external-live-tablet-interaction" to DocxWebSmokeScenario.TABLET,
                "external-live-narrow-interaction" to DocxWebSmokeScenario.COMPACT,
        )
        scenarios.forEach { (phase, scenario) ->
            navigateExternalReport(session, url, phase, scenario)
            verifyExternalDetailedOverviewInteractions(session, phase)
            captureExternal(session, phase)
            collectPageMetrics(session, phase)
        }
    }

    private fun verifyExternalDetailedOverviewInteractions(
        session: DocxWebCdpSession,
        phase: String,
    ) {
        recordExternalInteractionCheckpoint(session, phase, "start")
        positionAtlasForCapture(session)
        recordExternalInteractionCheckpoint(session, phase, "positioned")
        verifyExternalDetailedOverviewLenses(session, phase)
        val before = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
        session.trustedScrollBy(before.number("scrollDistance"))
        session.trustedScrollBy(-before.number("scrollDistance"))
        session.waitUntil("$phase detailed Atlas survives document scrolling") {
            val current = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
            current["stable"] == true &&
                current.number("nodes") == before.number("nodes") &&
                kotlin.math.abs(current.number("scale") - before.number("scale")) < 0.0001 &&
                kotlin.math.abs(current.number("graphTop") - before.number("graphTop")) < 2
        }
        recordExternalInteractionCheckpoint(session, phase, "scrolled")
        val selectionTarget = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_CLICK_TARGET_EXPRESSION)
        val selectionTargetId = selectionTarget["id"]?.toString().orEmpty()
        if (selectionTargetId.isBlank()) {
            throw GradleException("$phase detailed Atlas has no visible selectable file node: $selectionTarget")
        }
        recordExternalInteractionCheckpoint(session, phase, "selection-start")
        session.trustedClickAtViewport(selectionTarget.number("x"), selectionTarget.number("y"))
        runCatching {
            session.waitUntil("$phase detailed Atlas node selection") {
                val current = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
                current["selectedNodeId"] == selectionTargetId &&
                    current.number("nodes") == before.number("nodes") &&
                    kotlin.math.abs(current.number("graphTop") - before.number("graphTop")) < 2
            }
        }.getOrElse { error ->
            val after = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
            val pointer = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_CLICK_DIAGNOSTIC_EXPRESSION)
            throw GradleException(
                "$phase did not retain detailed Atlas selection $selectionTargetId; after=$after; pointer=$pointer",
                error,
            )
        }
        recordExternalInteractionCheckpoint(session, phase, "selection-observed")
        val stability = measureExternalDetailedOverviewSelectionStability(session)
        if (stability["stable"] != true) {
            throw GradleException("$phase moved or restyled the detailed Atlas during node selection: $stability")
        }
        report.addSample(
            DocxWebProofSample(
                phase = phase,
                source = "d3-overview",
                metric = "selection-layout-drift",
                value = stability.number("maximumDrift"),
                unit = "css-px",
                detail = "${stability.number("mutationBatches")} detail mutation batches",
            ),
        )
        waitForExternalDetailedOverviewSettlement(session, before.number("graphTop"))
        recordExternalInteractionCheckpoint(session, phase, "selection-settled")
        session.evaluate(
            "new Promise((resolve) => setTimeout(() => resolve(true), 2500))",
            awaitPromise = true,
        )
        recordExternalInteractionCheckpoint(session, phase, "async-settled")
        val delayed = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
        if (
            delayed["stable"] != true ||
            delayed.number("nodes") != before.number("nodes") ||
            kotlin.math.abs(delayed.number("graphTop") - before.number("graphTop")) >= 2
        ) {
            throw GradleException(
                "$phase replaced or moved the detailed Atlas after asynchronous settlement: " +
                "before=$before; delayed=$delayed",
            )
        }
        verifyExternalDetailedOverviewHistory(session, phase, selectionTargetId)
        val selected = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
        recordExternalInteractionCheckpoint(session, phase, "zoom-start")
        clickExternalD3Control(session, "zoom-in")
        runCatching {
            session.waitUntil("$phase detailed Atlas zoom") {
                val current = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
                    current["stable"] == true &&
                    current.number("nodes") == before.number("nodes") &&
                    current.number("scale") > selected.number("scale") * 1.05 &&
                    kotlin.math.abs(current.number("graphTop") - before.number("graphTop")) < 2
            }
        }.getOrElse { error ->
            val after = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
            val pointer = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_CLICK_DIAGNOSTIC_EXPRESSION)
            throw GradleException("$phase did not zoom the detailed Atlas; before=$selected; after=$after; pointer=$pointer", error)
        }
        recordExternalInteractionCheckpoint(session, phase, "zoom-observed")
        clickExternalD3Control(session, "fit")
        session.waitUntil("$phase detailed Atlas fit") {
            val current = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
            current["stable"] == true &&
                current["viewportMode"] == "fit" &&
                current.number("nodes") == before.number("nodes") &&
                kotlin.math.abs(current.number("graphTop") - before.number("graphTop")) < 2
        }
        recordExternalInteractionCheckpoint(session, phase, "fit-observed")
        val fitted = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
        report.addSample(
            DocxWebProofSample(
                phase = phase,
                source = "d3-overview",
                metric = "trusted-interaction-preservation",
                value = fitted.number("nodes"),
                unit = "nodes",
                detail = "scroll, node selection, zoom, and fit retained the published detailed Atlas",
            ),
        )
    }

    private fun verifyExternalDetailedOverviewLenses(
        session: DocxWebCdpSession,
        phase: String,
    ) {
        listOf("symbols", "problems", "cycles", "files").forEach { lens ->
            ensureExternalFiltersOpen(session, phase)
            val expected = externalDetailedLensExpectation(session, lens)
            clickExternalVisibleElement(
                session,
                "[data-srcx-graph-view=\"$lens\"]",
                "detailed Atlas $lens lens",
            )
            session.waitUntil("$phase visible detailed $lens lens") {
                val current = session.evaluateJsonObject(EXTERNAL_DETAILED_LENS_STATE_EXPRESSION)
                current["renderer"] == "d3" &&
                    current["lens"] == lens &&
                    current["surfaceLens"] == lens &&
                    current["pressedLens"] == lens &&
                    current.number("nodes") == expected.number("nodes") &&
                    current.number("edges") == expected.number("edges")
            }
            val current = session.evaluateJsonObject(EXTERNAL_DETAILED_LENS_STATE_EXPRESSION)
            if (current["nodeIds"] != expected["nodeIds"]) {
                throw GradleException(
                    "$phase rendered the wrong nodes for the $lens lens: expected=${expected["nodeIds"]}; " +
                        "actual=${current["nodeIds"]}",
                )
            }
            if (phase == "external-live-node-selection") {
                session.capturePng(reportDirectory.resolve("atlas-live-lens-$lens.png"))
            }
            report.addSample(
                DocxWebProofSample(
                    phase = phase,
                    source = "d3-overview",
                    metric = "trusted-$lens-lens",
                    value = current.number("nodes"),
                    unit = "nodes",
                    detail = "${current.number("edges")} rendered routes",
                ),
            )
        }
        verifyExternalDetailedOverviewFilters(session, phase)
        if (session.evaluateJsonObject(EXTERNAL_DETAILED_LENS_STATE_EXPRESSION)["filtersOpen"] == true) {
            clickExternalVisibleElement(session, "[data-srcx-filters-close]", "Filters close")
            session.waitUntil("$phase detailed Atlas Filters close") {
                session.evaluateJsonObject(EXTERNAL_DETAILED_LENS_STATE_EXPRESSION)["filtersOpen"] == false
            }
        }
    }

    private fun verifyExternalDetailedOverviewFilters(
        session: DocxWebCdpSession,
        phase: String,
    ) {
        selectExternalLens(session, phase, "symbols")
        ensureExternalFiltersOpen(session, phase)
        val interfaceExpectation = externalInterfaceFilterExpectation(session)
        clickExternalVisibleElement(session, "[data-srcx-declaration-kind=\"interfaces\"]", "Interfaces")
        waitForExternalFilterExpectation(session, phase, "interface declaration", interfaceExpectation)
        closeExternalFilters(session, phase)
        captureExternalFilter(session, phase, "interfaces")

        ensureExternalFiltersOpen(session, phase)
        clickExternalVisibleElement(session, "[data-srcx-declaration-kind=\"all\"]", "All declarations")
        selectExternalLens(session, phase, "files")
        ensureExternalFiltersOpen(session, phase)
        val callExpectation = externalCallFilterExpectation(session)
        clickExternalVisibleElement(session, "[data-srcx-relationship-kind=\"calls\"]", "Call relationships")
        waitForExternalFilterExpectation(session, phase, "call relationship", callExpectation)
        closeExternalFilters(session, phase)
        captureExternalFilter(session, phase, "calls")

        ensureExternalFiltersOpen(session, phase)
        clickExternalVisibleElement(session, "[data-srcx-relationship-kind=\"all\"]", "All relationships")
        val searchExpectation = externalFileSearchExpectation(session)
        val encodedQuery = searchExpectation["query"].toString().replace("\\", "\\\\").replace("\"", "\\\"")
        session.evaluate(
            """
            (() => {
                const roots = window.__docxBrowserProofQueryRoots || [document];
                const input = roots.map((root) => root.querySelector("[data-srcx-graph-search]")).find(Boolean);
                input.value = "$encodedQuery";
                input.dispatchEvent(new Event("input", { bubbles: true }));
            })()
            """.trimIndent(),
        )
        waitForExternalFilterExpectation(session, phase, "typed file search", searchExpectation)
        closeExternalFilters(session, phase)
        captureExternalFilter(session, phase, "search")
        clearExternalOverviewSearch(session)
        waitForExternalFilterExpectation(
            session,
            phase,
            "cleared file search",
            externalDetailedLensExpectation(session, "files"),
        )
    }

    private fun ensureExternalFiltersOpen(
        session: DocxWebCdpSession,
        phase: String,
    ) {
        if (session.evaluateJsonObject(EXTERNAL_DETAILED_LENS_STATE_EXPRESSION)["filtersOpen"] == true) return
        clickExternalVisibleElement(session, "[data-srcx-filters-toggle]", "Filters")
        session.waitUntil("$phase detailed Atlas Filters sheet") {
            session.evaluateJsonObject(EXTERNAL_DETAILED_LENS_STATE_EXPRESSION)["filtersOpen"] == true
        }
    }

    private fun closeExternalFilters(
        session: DocxWebCdpSession,
        phase: String,
    ) {
        if (session.evaluateJsonObject(EXTERNAL_DETAILED_LENS_STATE_EXPRESSION)["filtersOpen"] != true) return
        clickExternalVisibleElement(session, "[data-srcx-filters-close]", "Filters close")
        session.waitUntil("$phase detailed Atlas Filters close") {
            session.evaluateJsonObject(EXTERNAL_DETAILED_LENS_STATE_EXPRESSION)["filtersOpen"] == false
        }
    }

    private fun selectExternalLens(
        session: DocxWebCdpSession,
        phase: String,
        lens: String,
    ) {
        ensureExternalFiltersOpen(session, phase)
        val expected = externalDetailedLensExpectation(session, lens)
        clickExternalVisibleElement(session, "[data-srcx-graph-view=\"$lens\"]", "$lens lens")
        waitForExternalFilterExpectation(session, phase, "$lens lens", expected)
    }

    private fun waitForExternalFilterExpectation(
        session: DocxWebCdpSession,
        phase: String,
        label: String,
        expected: Map<String, Any?>,
    ) {
        session.waitUntil("$phase detailed Atlas $label filter") {
            val current = session.evaluateJsonObject(EXTERNAL_DETAILED_LENS_STATE_EXPRESSION)
            current["nodeIds"] == expected["nodeIds"] && current["edgeIds"] == expected["edgeIds"]
        }
    }

    private fun captureExternalFilter(
        session: DocxWebCdpSession,
        phase: String,
        name: String,
    ) {
        if (phase == "external-live-node-selection") {
            session.capturePng(reportDirectory.resolve("atlas-live-filter-$name.png"))
        }
    }

    private fun clearExternalOverviewSearch(session: DocxWebCdpSession) {
        session.evaluate(
            """
            (() => {
                const roots = window.__docxBrowserProofQueryRoots || [document];
                const input = roots.map((root) => root.querySelector("[data-srcx-graph-search]")).find(Boolean);
                input.value = "";
                input.dispatchEvent(new Event("input", { bubbles: true }));
            })()
            """.trimIndent(),
        )
    }

    private fun externalDetailedLensExpectation(
        session: DocxWebCdpSession,
        lens: String,
    ): Map<String, Any?> =
        session.evaluateJsonObject(
            """
            (async () => {
                const response = await fetch(new URL("data/atlas-overviews.json", location.href));
                if (!response.ok) throw new Error("Could not load Atlas overview frames: " + response.status);
                const payload = await response.json();
                const frame = payload.frames.find((candidate) => candidate.lens === "$lens");
                if (!frame) throw new Error("Missing Atlas overview frame for $lens");
                return {
                    nodes: frame.nodes.length,
                    edges: frame.edges.length,
                    nodeIds: frame.nodes.map((node) => node.id).filter(Boolean).sort(),
                    edgeIds: frame.edges.map((edge) => edge.id).filter(Boolean).sort(),
                };
            })()
            """.trimIndent(),
            awaitPromise = true,
        )

    private fun externalInterfaceFilterExpectation(session: DocxWebCdpSession): Map<String, Any?> =
        session.evaluateJsonObject(
            """
            (async () => {
                const payload = await (await fetch(new URL("data/atlas-overviews.json", location.href))).json();
                const frame = payload.frames.find((candidate) => candidate.lens === "symbols");
                const primary = new Set(frame.nodes.filter((node) => node.kind === "INTERFACE").map((node) => node.id));
                const edges = frame.edges.filter((edge) => primary.has(edge.sourceId) || primary.has(edge.targetId));
                const nodeIds = new Set(primary);
                edges.forEach((edge) => { nodeIds.add(edge.sourceId); nodeIds.add(edge.targetId); });
                return {
                    nodeIds: Array.from(nodeIds).sort(),
                    edgeIds: edges.map((edge) => edge.id).sort(),
                };
            })()
            """.trimIndent(),
            awaitPromise = true,
        )

    private fun externalCallFilterExpectation(session: DocxWebCdpSession): Map<String, Any?> =
        session.evaluateJsonObject(
            """
            (async () => {
                const payload = await (await fetch(new URL("data/atlas-overviews.json", location.href))).json();
                const frame = payload.frames.find((candidate) => candidate.lens === "files");
                const allowed = new Set(["CALL", "CONSTRUCTOR"]);
                const edges = frame.edges.filter((edge) =>
                    edge.kindCounts.some((count) => allowed.has(count.key) && Number(count.count || 0) > 0));
                return {
                    nodeIds: frame.nodes.map((node) => node.id).sort(),
                    edgeIds: edges.map((edge) => edge.id).sort(),
                };
            })()
            """.trimIndent(),
            awaitPromise = true,
        )

    private fun externalFileSearchExpectation(session: DocxWebCdpSession): Map<String, Any?> =
        session.evaluateJsonObject(
            """
            (async () => {
                const payload = await (await fetch(new URL("data/atlas-overviews.json", location.href))).json();
                const frame = payload.frames.find((candidate) => candidate.lens === "files");
                const degree = new Map(frame.nodes.map((node) => [node.id, 0]));
                frame.edges.forEach((edge) => {
                    degree.set(edge.sourceId, (degree.get(edge.sourceId) || 0) + 1);
                    degree.set(edge.targetId, (degree.get(edge.targetId) || 0) + 1);
                });
                const candidates = frame.nodes.filter((node) => node.type === "file");
                const selected = candidates.find((node) => degree.get(node.id) === 0) || candidates[0];
                const query = selected.name;
                const lower = query.toLowerCase();
                const primary = new Set(frame.nodes.filter((node) =>
                    [node.name, node.path].filter(Boolean).some((value) => value.toLowerCase().includes(lower)))
                    .map((node) => node.id));
                const edges = frame.edges.filter((edge) => primary.has(edge.sourceId) || primary.has(edge.targetId));
                const nodeIds = new Set(primary);
                edges.forEach((edge) => { nodeIds.add(edge.sourceId); nodeIds.add(edge.targetId); });
                return {
                    query,
                    nodeIds: Array.from(nodeIds).sort(),
                    edgeIds: edges.map((edge) => edge.id).sort(),
                };
            })()
            """.trimIndent(),
            awaitPromise = true,
        )

    private fun verifyExternalDetailedOverviewHistory(
        session: DocxWebCdpSession,
        phase: String,
        selectedNodeId: String,
    ) {
        session.waitUntil("$phase detailed Atlas Back enabled") {
            session.evaluateJsonObject(EXTERNAL_DETAILED_HISTORY_STATE_EXPRESSION)["canBack"] == true
        }
        clickExternalVisibleElement(session, "[data-srcx-history-back]", "Back")
        runCatching {
            session.waitUntil("$phase detailed Atlas Back navigation") {
                val state = session.evaluateJsonObject(EXTERNAL_DETAILED_HISTORY_STATE_EXPRESSION)
                state["selectedNodeId"] == "" && state["canForward"] == true
            }
        }.getOrElse { error ->
            val state = session.evaluateJsonObject(EXTERNAL_DETAILED_HISTORY_STATE_EXPRESSION)
            throw GradleException("$phase Back did not restore the visible Atlas selection: $state", error)
        }
        clickExternalVisibleElement(session, "[data-srcx-history-forward]", "Forward")
        session.waitUntil("$phase detailed Atlas Forward navigation") {
            val state = session.evaluateJsonObject(EXTERNAL_DETAILED_HISTORY_STATE_EXPRESSION)
            state["selectedNodeId"] == selectedNodeId && state["canBack"] == true
        }
    }

    private fun recordExternalInteractionCheckpoint(
        session: DocxWebCdpSession,
        phase: String,
        checkpoint: String,
    ) {
        report.addSample(
            DocxWebProofSample(
                phase = phase,
                source = "browser-proof-checkpoint",
                metric = "interaction-$checkpoint",
                value = session.evaluate("performance.now()").toDoubleValue(),
                unit = "navigation-ms",
            ),
        )
    }

    private fun waitForExternalDetailedOverviewSettlement(
        session: DocxWebCdpSession,
        expectedGraphTop: Double,
    ) {
        runCatching {
            session.waitUntil("detailed Atlas asynchronous evidence and scroll settlement") {
                session.evaluate(
                    """
                new Promise((resolve) => {
                    const roots = window.__docxBrowserProofQueryRoots || [document];
                    const one = (selector) => roots.map((root) => root.querySelector(selector)).find(Boolean);
                    const surface = one("[data-docx-atlas-surface]");
                    const host = one("[data-docx-atlas-host]");
                    const sample = () => ({
                        scrollTop: Number(surface?.scrollTop || 0),
                        renderer: host?.getAttribute("data-docx-atlas-renderer"),
                        nodes: Number(host?.getAttribute("data-docx-atlas-node-count") || 0),
                        graphTop: Number(host?.closest("[data-srcx-architecture-graph]")
                            ?.getBoundingClientRect().top || 0),
                    });
                    const before = sample();
                    let remainingFrames = 12;
                    const verifyFrame = () => {
                        const current = sample();
                        const stable =
                            before.renderer === "d3" && current.renderer === "d3" &&
                            before.nodes > 0 && current.nodes === before.nodes &&
                            Math.abs(before.graphTop - $expectedGraphTop) < 2 &&
                            Math.abs(current.graphTop - $expectedGraphTop) < 2;
                        if (!stable || remainingFrames <= 1) {
                            resolve(stable);
                            return;
                        }
                        remainingFrames -= 1;
                        requestAnimationFrame(verifyFrame);
                    };
                    requestAnimationFrame(verifyFrame);
                })
                    """.trimIndent(),
                    awaitPromise = true,
                ) == true
            }
        }.getOrElse { error ->
            val current = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
            throw GradleException(
                "Detailed Atlas did not settle at viewport top $expectedGraphTop; current=$current",
                error,
            )
        }
    }

    private fun measureExternalDetailedOverviewSelectionStability(
        session: DocxWebCdpSession,
    ): Map<String, Any?> =
        session.evaluateJsonObject(
            """
            new Promise((resolve) => {
                const roots = window.__docxBrowserProofQueryRoots || [document];
                const one = (selector) => roots.map((root) => root.querySelector(selector)).find(Boolean);
                const host = one("[data-docx-atlas-host]");
                const graph = host?.closest("[data-srcx-architecture-graph]");
                const surface = one("[data-docx-atlas-surface]");
                const detail = one("[data-srcx-detail]");
                const back = one("[data-srcx-history-back]");
                const samples = [];
                let mutationBatches = 0;
                const observer = new MutationObserver(() => { mutationBatches += 1; });
                if (detail) observer.observe(detail, { childList: true, subtree: true, attributes: true });
                const sample = () => {
                    const graphRect = graph?.getBoundingClientRect();
                    const backRect = back?.getBoundingClientRect();
                    const detailRect = detail?.getBoundingClientRect();
                    samples.push({
                        graphTop: Number(graphRect?.top || 0),
                        graphLeft: Number(graphRect?.left || 0),
                        backTop: Number(backRect?.top || 0),
                        backLeft: Number(backRect?.left || 0),
                        detailTop: Number(detailRect?.top || 0),
                        detailRight: Number(detailRect?.right || 0),
                        documentScrollY: Number(window.scrollY || 0),
                        surfaceScrollTop: Number(surface?.scrollTop || 0),
                        pointerScrollTop: Number(host?.getAttribute("data-docx-atlas-pointer-scroll-top") || 0),
                        selectionScrollTop: Number(host?.getAttribute("data-docx-atlas-selection-scroll-top") || 0),
                        preservedScrollTop: Number(host?.getAttribute("data-docx-atlas-preserved-scroll-top") || 0),
                        restoreRequest: Number(surface?.getAttribute("data-docx-atlas-scroll-restore-request") || 0),
                        restoreReason: surface?.getAttribute("data-docx-atlas-scroll-restore-reason") || "",
                        stylesheetCount: Number(surface?.getRootNode()?.styleSheets?.length || 0),
                        activeStyles: Number(surface?.getAttribute("data-docx-atlas-active-styles") || 0),
                        runtimeOwner: host?.getAttribute("data-docx-atlas-runtime-owner") || "",
                    });
                };
                let remainingFrames = 60;
                const next = () => {
                    sample();
                    remainingFrames -= 1;
                    if (remainingFrames > 0) {
                        requestAnimationFrame(next);
                        return;
                    }
                    observer.disconnect();
                    const range = (key) => {
                        const values = samples.map((candidate) => candidate[key]);
                        return Math.max(...values) - Math.min(...values);
                    };
                    const drifts = {
                        graphTop: range("graphTop"),
                        graphLeft: range("graphLeft"),
                        backTop: range("backTop"),
                        backLeft: range("backLeft"),
                        detailTop: range("detailTop"),
                        detailRight: range("detailRight"),
                        documentScrollY: range("documentScrollY"),
                        surfaceScrollTop: range("surfaceScrollTop"),
                    };
                    const maximumDrift = Math.max(...Object.values(drifts));
                    const owners = new Set(samples.map((candidate) => candidate.runtimeOwner));
                    const minimumStylesheets = Math.min(...samples.map((candidate) => candidate.stylesheetCount));
                    const minimumActiveStyles = Math.min(...samples.map((candidate) => candidate.activeStyles));
                    resolve({
                        stable: maximumDrift < 2 && owners.size === 1 && minimumActiveStyles === 3,
                        maximumDrift,
                        mutationBatches,
                        minimumStylesheets,
                        minimumActiveStyles,
                        runtimeOwners: Array.from(owners),
                        drifts,
                        first: samples[0],
                        last: samples[samples.length - 1],
                    });
                };
                requestAnimationFrame(next);
            })
            """.trimIndent(),
            awaitPromise = true,
        )

    private fun clickExternalD3Control(
        session: DocxWebCdpSession,
        action: String,
    ) {
        clickExternalVisibleElement(session, "[data-srcx-graph-action=\"$action\"]", "detailed Atlas $action")
    }

    private fun clickExternalVisibleElement(
        session: DocxWebCdpSession,
        selector: String,
        label: String,
    ) {
        val encodedSelector = selector.replace("\\", "\\\\").replace("\"", "\\\"")
        val target =
            session.evaluateJsonObject(
                """
                (() => {
                    const roots = window.__docxBrowserProofQueryRoots || [document];
                    const selector = "$encodedSelector";
                    const candidates = roots.flatMap((root, rootIndex) =>
                        Array.from(root.querySelectorAll(selector)).map((node) => ({ root, rootIndex, node })));
                    const visible = candidates.find(({ root, node }) => {
                        const rect = node.getBoundingClientRect();
                        const style = getComputedStyle(node);
                        const x = rect.left + rect.width / 2;
                        const y = rect.top + rect.height / 2;
                        const hit = root.elementFromPoint?.(x, y);
                        return rect.width > 0 && rect.height > 0 &&
                            x >= 0 && x < window.innerWidth && y >= 0 && y < window.innerHeight &&
                            style.display !== "none" && style.visibility !== "hidden" &&
                            style.pointerEvents !== "none" && node.contains(hit);
                    });
                    if (!visible) return {
                        available: false,
                        candidates: candidates.map(({ root, rootIndex, node }) => {
                            const rect = node.getBoundingClientRect();
                            const style = getComputedStyle(node);
                            const x = rect.left + rect.width / 2;
                            const y = rect.top + rect.height / 2;
                            const hit = root.elementFromPoint?.(x, y);
                            const parent = node.parentElement;
                            const offsetParent = node.offsetParent;
                            return {
                                rootIndex,
                                rect: rect.toJSON(),
                                hitTag: hit?.tagName || null,
                                hitClass: hit?.className || null,
                                display: style.display,
                                visibility: style.visibility,
                                pointerEvents: style.pointerEvents,
                                position: style.position,
                                insetBlockStart: style.insetBlockStart,
                                insetInlineEnd: style.insetInlineEnd,
                                transform: style.transform,
                                marginBlockStart: style.marginBlockStart,
                                zIndex: style.zIndex,
                                parentClass: parent?.className || null,
                                parentRect: parent?.getBoundingClientRect().toJSON() || null,
                                offsetParentClass: offsetParent?.className || null,
                                offsetParentRect: offsetParent?.getBoundingClientRect().toJSON() || null,
                            };
                        }),
                    };
                    const rect = visible.node.getBoundingClientRect();
                    return {
                        available: true,
                        x: rect.left + rect.width / 2,
                        y: rect.top + rect.height / 2,
                    };
                })()
                """.trimIndent(),
            )
        if (target["available"] != true) {
            val snapshot = session.evaluateJsonObject(EXTERNAL_DETAILED_OVERVIEW_EXPRESSION)
            throw GradleException("$label is not visibly clickable: $target; snapshot=$snapshot")
        }
        session.trustedClickAtViewport(target.number("x"), target.number("y"))
    }

    private fun verifyExternalNodeSelection(session: DocxWebCdpSession) {
        val target = session.evaluateJsonObject(EXTERNAL_NODE_SELECTION_TARGET_EXPRESSION)
        session.trustedScrollBy(target.number("scrollDelta"))
        session.waitUntil("embedded Atlas scroll settlement") {
            session.evaluateJsonObject(EXTERNAL_NODE_SELECTION_TARGET_EXPRESSION)["targetVisible"] == true
        }
        val settledTarget = session.evaluateJsonObject(EXTERNAL_NODE_SELECTION_TARGET_EXPRESSION)
        session.trustedClickAt(
            ATLAS_CANVAS_SELECTOR,
            settledTarget.number("x"),
            settledTarget.number("y"),
        )
        val selectedId = settledTarget["selectedId"].toString()
        runCatching {
            session.waitUntil("trusted Canvas node selection", timeoutSeconds = 10) {
                val selection = session.evaluateJsonObject(EXTERNAL_NODE_SELECTION_RESULT_EXPRESSION)
                selection["selectedId"] == selectedId &&
                    selection["preserved"] == true &&
                    selection.long("layoutRevision") == settledTarget.long("layoutRevision") &&
                    selection.long("requestRevision") == settledTarget.long("requestRevision") &&
                    selection.number("cameraX") == settledTarget.number("cameraX") &&
                    selection.number("cameraY") == settledTarget.number("cameraY") &&
                    selection.number("scale") == settledTarget.number("scale")
            }
        }.getOrElse { error ->
            val result = session.evaluateJsonObject(EXTERNAL_NODE_SELECTION_RESULT_EXPRESSION)
            throw GradleException(
                "Trusted Canvas click did not preserve its node; target=$settledTarget; result=$result",
                error,
            )
        }
        val result = session.evaluateJsonObject(EXTERNAL_NODE_SELECTION_RESULT_EXPRESSION)
        if (result["preserved"] != true || result.number("afterNodes") != settledTarget.number("beforeNodes")) {
            throw GradleException("Live Atlas node selection lost its semantic slice: $result")
        }
        report.addSample(
            DocxWebProofSample(
                phase = "external-live-node-selection",
                source = "canvas-selection",
                metric = "settlement-latency",
                value = result.number("paintedAt") - settledTarget.number("startedAt"),
                unit = "ms",
                detail =
                    "trusted pointer ${result["selectedId"]}; " +
                        "${settledTarget["beforeNodes"]} -> ${result["afterNodes"]} nodes",
            ),
        )
    }

    private fun verifyExternalPanAndZoom(
        session: DocxWebCdpSession,
        phase: String,
    ) {
        session.trustedClick("[data-srcx-detail-close]")
        session.waitUntil("Atlas detail close before navigation") {
            session.evaluateJsonObject(EXTERNAL_NAVIGATION_RESULT_EXPRESSION)["detailOpen"] == false
        }
        val before = session.evaluateJsonObject(EXTERNAL_NAVIGATION_TARGET_EXPRESSION)
        val startX = before.number("x")
        val startY = before.number("y")
        session.trustedDragAt(
            selector = ATLAS_CANVAS_SELECTOR,
            startOffsetX = startX,
            startOffsetY = startY,
            endOffsetX = startX + 96,
            endOffsetY = startY + 64,
        )
        session.waitUntil("trusted Canvas pan") {
            val current = session.evaluateJsonObject(EXTERNAL_NAVIGATION_RESULT_EXPRESSION)
            current["preserved"] == true &&
                current.long("layoutRevision") == before.long("layoutRevision") &&
                current.long("requestRevision") == before.long("requestRevision") &&
                (kotlin.math.abs(current.number("cameraX") - before.number("cameraX")) > 40 ||
                    kotlin.math.abs(current.number("cameraY") - before.number("cameraY")) > 30)
        }
        val panned = session.evaluateJsonObject(EXTERNAL_NAVIGATION_RESULT_EXPRESSION)
        session.trustedWheelAt(
            selector = ATLAS_CANVAS_SELECTOR,
            offsetX = startX,
            offsetY = startY,
            deltaY = -180.0,
        )
        session.waitUntil("trusted Canvas wheel zoom") {
            val current = session.evaluateJsonObject(EXTERNAL_NAVIGATION_RESULT_EXPRESSION)
            current["preserved"] == true && current.number("scale") > panned.number("scale") * 1.1
        }
        val zoomed = session.evaluateJsonObject(EXTERNAL_NAVIGATION_RESULT_EXPRESSION)
        session.trustedClick("[data-srcx-graph-action=\"zoom-in\"]")
        session.waitUntil("trusted Canvas button zoom") {
            val current = session.evaluateJsonObject(EXTERNAL_NAVIGATION_RESULT_EXPRESSION)
            current["preserved"] == true && current.number("scale") > zoomed.number("scale") * 1.05
        }
        val buttonZoomed = session.evaluateJsonObject(EXTERNAL_NAVIGATION_RESULT_EXPRESSION)
        if (before.number("nodes") != buttonZoomed.number("nodes")) {
            throw GradleException("Live Atlas navigation changed its bounded node set: $before -> $buttonZoomed")
        }
        report.addSample(
            DocxWebProofSample(
                phase = phase,
                source = "canvas-navigation",
                metric = "trusted-gesture-preservation",
                value = buttonZoomed.number("nodes"),
                unit = "nodes",
                detail =
                    "camera ${before["cameraX"]},${before["cameraY"]} -> " +
                        "${panned["cameraX"]},${panned["cameraY"]}; " +
                        "scale ${panned["scale"]} -> ${zoomed["scale"]} -> ${buttonZoomed["scale"]}",
            ),
        )
    }

    private fun verifyExternalFitCoverage(
        session: DocxWebCdpSession,
        phase: String,
    ) {
        session.trustedClick("[data-srcx-graph-action=\"fit\"]")
        session.waitUntil("$phase meaningful Fit coverage") {
            val current = session.evaluateJsonObject(EXTERNAL_NAVIGATION_RESULT_EXPRESSION)
            current["preserved"] == true && current.number("coverage") >= MINIMUM_FIT_COVERAGE
        }
        val fitted = session.evaluateJsonObject(EXTERNAL_NAVIGATION_RESULT_EXPRESSION)
        report.addSample(
            DocxWebProofSample(
                phase = phase,
                source = "canvas-navigation",
                metric = "fit-viewport-coverage",
                value = fitted.number("coverage") * 100.0,
                unit = "percent",
                detail = "trusted Fit command at ${fitted["canvasWidth"]}x${fitted["canvasHeight"]}",
            ),
        )
    }

    private fun resizeExternalReport(
        session: DocxWebCdpSession,
        phase: String,
        scenario: DocxWebSmokeScenario,
    ) {
        session.setViewport(scenario.viewport())
        session.waitUntil("$phase responsive Atlas") {
            session.evaluate(
                """
                (() => {
                    const visit = (selector, current = document) => {
                        const direct = current.querySelector(selector);
                        if (direct) return direct;
                        for (const element of current.querySelectorAll("*")) {
                            if (element.shadowRoot) {
                                const nested = visit(selector, element.shadowRoot);
                                if (nested) return nested;
                            }
                        }
                        return null;
                    };
                    const root = document.getElementById("docx-app");
                    const host = visit("[data-docx-atlas-host]");
                    const mode = (root?.getBoundingClientRect().width || 0) >= 980 ? "wide" : "compact";
                    return mode === "${scenario.layoutMode}" &&
                        host?.getAttribute("data-docx-atlas-state") === "ready" &&
                        host?.getAttribute("data-docx-atlas-renderer") === "d3" &&
                        Boolean(visit("[data-docx-atlas-svg]")) &&
                        !visit("[data-docx-atlas-canvas]");
                })()
                """.trimIndent(),
            ) == true
        }
        session.evaluate(
            "new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))",
            awaitPromise = true,
        )
    }

    private fun navigateExternalReport(
        session: DocxWebCdpSession,
        url: String,
        phase: String,
        scenario: DocxWebSmokeScenario,
    ) {
        session.setViewport(scenario.viewport())
        val networkStart = session.networkSampleCount()
        val started = System.nanoTime()
        session.navigate(url)
        session.waitUntil("$phase Atlas ready") {
            session.evaluate(
                """
                (() => {
                    const root = document.getElementById("docx-app");
                    const visit = (selector, current = document) => {
                        const direct = current.querySelector(selector);
                        if (direct) return direct;
                        for (const element of current.querySelectorAll("*")) {
                            if (element.shadowRoot) {
                                const nested = visit(selector, element.shadowRoot);
                                if (nested) return nested;
                            }
                        }
                        return null;
                    };
                    const host = visit("[data-docx-atlas-host]");
                    const surface = visit("[data-docx-atlas-surface]");
                    const svg = visit("[data-docx-atlas-svg]");
                    const actualLayoutMode = (root?.getBoundingClientRect().width || 0) >= 980 ? "wide" : "compact";
                    const ready = root?.getAttribute("data-docx-state") === "ready" &&
                        actualLayoutMode === "${scenario.layoutMode}" &&
                        host?.getAttribute("data-docx-atlas-state") === "ready" &&
                        surface?.getAttribute("data-docx-atlas-styles-ready") === "true" &&
                        surface?.getAttribute("data-docx-atlas-active-styles") === "3" &&
                        host?.getAttribute("data-docx-atlas-renderer") === "d3" &&
                        Number(host?.getAttribute("data-docx-atlas-node-count") || 0) > 0 &&
                        Boolean(visit("[data-srcx-chart-controls]")) &&
                        Boolean(visit("[data-srcx-global-search-input]")) &&
                        Boolean(svg) &&
                        !visit("[data-docx-atlas-canvas]");
                    if (!ready) {
                        throw new Error(JSON.stringify({
                            rootState: root?.getAttribute("data-docx-state"),
                            layoutMode: actualLayoutMode,
                            publishedLayoutMode: root?.getAttribute("data-docx-layout-mode"),
                            atlasState: host?.getAttribute("data-docx-atlas-state"),
                            stylesReady: surface?.getAttribute("data-docx-atlas-styles-ready"),
                            activeStyles: surface?.getAttribute("data-docx-atlas-active-styles"),
                            renderer: host?.getAttribute("data-docx-atlas-renderer"),
                            runtime: host?.getAttribute("data-docx-atlas-layout-runtime"),
                            revision: host?.getAttribute("data-docx-atlas-layout-revision"),
                            hasControls: Boolean(visit("[data-srcx-chart-controls]")),
                            hasSearch: Boolean(visit("[data-srcx-global-search-input]")),
                            hasSvg: Boolean(svg),
                            hasCanvas: Boolean(visit("[data-docx-atlas-canvas]")),
                        }));
                    }
                    return true;
                })()
                """.trimIndent(),
            ) == true
        }
        session.evaluate(
            "new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))",
            awaitPromise = true,
        )
        report.addSample(
            DocxWebProofSample(
                phase = phase,
                source = "cdp-navigation",
                metric = "ready-latency",
                value = (System.nanoTime() - started) / 1_000_000.0,
                unit = "ms",
            ),
        )
        session.networkSamplesSince(networkStart).forEach { sample ->
            report.addSample(
                DocxWebProofSample(
                    phase = phase,
                    source = "cdp-network",
                    metric = "encoded-transfer-bytes",
                    value = sample.encodedBytes.toDouble(),
                    unit = "bytes",
                    detail =
                        "${sample.url.ifBlank { "request=${sample.requestId}" }};" +
                            "cdpTimestampSeconds=${sample.timestampSeconds}",
                ),
            )
        }
        session.evaluate(CACHE_QUERY_ROOTS_EXPRESSION)
        collectPageMetrics(session, "$phase-cold-start")
        session.evaluate("window.__docxBrowserProofMetrics.reset()")
    }

    private fun captureEvidenceState(session: DocxWebCdpSession) {
        val scenario = DocxWebSmokeScenario.WIDE
        navigateForCapture(session, "evidence-open", scenario, captureMode = "evidence")
        session.waitUntil("open relationship source evidence") {
            val state =
                session.evaluateJsonObject(
                    """
                    (() => {
                        const root = document.getElementById("docx-app");
                        const deepQuery = (selector, current = document) => {
                            const direct = current.querySelector(selector);
                            if (direct) return direct;
                            for (const element of current.querySelectorAll("*")) {
                                if (element.shadowRoot) {
                                    const nested = deepQuery(selector, element.shadowRoot);
                                    if (nested) return nested;
                                }
                            }
                            return null;
                        };
                        const detail = deepQuery("[data-srcx-detail]");
                        return {
                            edgeId: root?.getAttribute("data-docx-selected-edge-id") || "",
                            detailVisible: Boolean(detail) && getComputedStyle(detail).display !== "none",
                            highlightedLines: detail?.querySelectorAll('[aria-current="true"]').length || 0,
                        };
                    })()
                    """.trimIndent(),
                )
            state["edgeId"].toString().isNotBlank() &&
                state["detailVisible"] == true &&
                state.long("highlightedLines") > 0
        }
        capture(session, "evidence-open")
        collectPageMetrics(session, "evidence-open")
    }

    private fun captureNativeFullscreenState(
        session: DocxWebCdpSession,
        state: String,
        scenario: DocxWebSmokeScenario,
    ) {
        navigateForCapture(session, state, scenario, captureMode = "ready")
        prepareFullscreenRestorationProbe(session)
        session.trustedClick(FULLSCREEN_BUTTON_SELECTOR)
        session.waitUntil("native Document and ShadowRoot fullscreen state") {
            nativeFullscreenState(session).isEntered
        }
        session.trustedClick(CHART_CONTROLS_TOGGLE_SELECTOR)
        session.waitUntil("collapsed native-fullscreen controls") {
            session.evaluate(
                """
                (() => {
                    const visit = (root) => {
                        const graph = root.querySelector("[data-srcx-architecture-graph]");
                        if (graph) return graph;
                        for (const element of root.querySelectorAll("*")) {
                            if (element.shadowRoot) {
                                const nested = visit(element.shadowRoot);
                                if (nested) return nested;
                            }
                        }
                        return null;
                    };
                    return visit(document)?.getAttribute("data-srcx-chart-controls-state") === "collapsed";
                })()
                """.trimIndent(),
            ) == true
        }
        capture(session, state)
        report.addGate(
            DocxWebProofGate(
                name = "native-fullscreen-semantics",
                status = DocxWebProofGateStatus.PASS,
                target = "Trusted CDP input enters Document and ShadowRoot fullscreen and fills the viewport",
                observed = "Native fullscreen entered with collapsed map chrome",
                detail = "The fresh capture page never invokes the functional smoke's synthetic fullscreen harness.",
                required = true,
            ),
        )
        session.trustedClick(CHART_CONTROLS_TOGGLE_SELECTOR)
        session.waitUntil("expanded native-fullscreen controls") {
            session.evaluate(
                """
                (() => {
                    const visit = (root) => {
                        const graph = root.querySelector("[data-srcx-architecture-graph]");
                        if (graph) return graph;
                        for (const element of root.querySelectorAll("*")) {
                            if (element.shadowRoot) {
                                const nested = visit(element.shadowRoot);
                                if (nested) return nested;
                            }
                        }
                        return null;
                    };
                    return visit(document)?.getAttribute("data-srcx-chart-controls-state") === "expanded";
                })()
                """.trimIndent(),
            ) == true
        }
        session.pressEscape()
        session.waitUntil("native fullscreen exit") {
            val state = nativeFullscreenState(session)
            !state.documentFullscreen && !state.graphFullscreen
        }
        verifyFullscreenRestoration(session, state)
        capture(session, "$state-exit")
        collectPageMetrics(session, state)
    }

    private fun prepareFullscreenRestorationProbe(session: DocxWebCdpSession) {
        session.evaluateJsonObject(FULLSCREEN_RESTORATION_CAPTURE_EXPRESSION)
    }

    private fun verifyFullscreenRestoration(
        session: DocxWebCdpSession,
        phase: String,
    ) {
        runCatching {
            session.waitUntil("$phase embedded position, camera, dimensions, and focus restoration") {
                val state = fullscreenRestorationState(session)
                FULLSCREEN_RESTORATION_KEYS.all { key -> state[key] == true }
            }
        }.getOrElse { failure ->
            val result = fullscreenRestorationState(session)
            throw GradleException("Fullscreen did not restore the embedded Atlas session: $result", failure)
        }
        report.addGate(
            DocxWebProofGate(
                name = "$phase-restoration",
                status = DocxWebProofGateStatus.PASS,
                target = "Exact embedded scroll, graph dimensions, camera, and fullscreen-button focus",
                observed = "All fullscreen return invariants restored",
                detail = "Verified after native Escape using the same live Atlas instance.",
                required = true,
            ),
        )
    }

    private fun fullscreenRestorationState(session: DocxWebCdpSession): Map<String, Any?> =
        session.evaluateJsonObject(FULLSCREEN_RESTORATION_RESULT_EXPRESSION)

    private fun nativeFullscreenState(session: DocxWebCdpSession): NativeFullscreenState {
        val state =
            session.evaluateJsonObject(
                """
                (() => {
                    const visit = (root) => {
                        const graph = root.querySelector("[data-srcx-architecture-graph]");
                        if (graph) return graph;
                        for (const element of root.querySelectorAll("*")) {
                            if (element.shadowRoot) {
                                const nested = visit(element.shadowRoot);
                                if (nested) return nested;
                            }
                        }
                        return null;
                    };
                    const graph = visit(document);
                    if (!graph) return {};
                    const graphRoot = graph.getRootNode();
                    const shadowApplicable = graphRoot instanceof ShadowRoot;
                    const documentMatches = document.fullscreenElement === graph ||
                        (shadowApplicable && document.fullscreenElement === graphRoot.host);
                    const shadowMatches = !shadowApplicable || graphRoot.fullscreenElement === graph;
                    const rect = graph.getBoundingClientRect();
                    return {
                        documentFullscreen: Boolean(document.fullscreenElement),
                        documentMatches,
                        shadowApplicable,
                        shadowMatches,
                        graphFullscreen: graph.getAttribute("data-srcx-fullscreen") === "true",
                        viewportFilled: Math.abs(rect.left) <= 3 && Math.abs(rect.top) <= 3 &&
                            Math.abs(rect.width - innerWidth) <= 3 && Math.abs(rect.height - innerHeight) <= 3,
                    };
                })()
                """.trimIndent(),
            )
        return NativeFullscreenState(
            documentFullscreen = state["documentFullscreen"] == true,
            documentMatches = state["documentMatches"] == true,
            shadowApplicable = state["shadowApplicable"] == true,
            shadowMatches = state["shadowMatches"] == true,
            graphFullscreen = state["graphFullscreen"] == true,
            viewportFilled = state["viewportFilled"] == true,
        )
    }

    private fun measureRendererLifecycle(session: DocxWebCdpSession) {
        navigateForCapture(session, "lifecycle", DocxWebSmokeScenario.WIDE, captureMode = "evidence")
        session.evaluate("window.__docxBrowserProofMetrics.reset()")
        session.collectGarbage()
        val before = session.heapUsage()
        val lifecycle = session.evaluateJsonObject(RENDERER_LIFECYCLE_EXPRESSION, awaitPromise = true)
        collectPageMetrics(session, "lifecycle")
        session.evaluate("window.__docxBrowserProofMetrics.reset()")
        session.collectGarbage()
        val after = session.heapUsage()
        addHeapSamples("lifecycle", "before", before)
        addHeapSamples("lifecycle", "after", after)
        lifecycle.list("durations").forEachIndexed { index, duration ->
            report.addSample(
                DocxWebProofSample(
                    phase = "lifecycle",
                    source = "application-smoke-command",
                    metric = "destroy-remount-cycle",
                    value = duration.toDoubleValue(),
                    unit = "ms",
                    detail = "cycle=${index + 1}",
                ),
            )
        }
        val retainedBytes = after.usedBytes - before.usedBytes
        val retainedPercent = retainedBytes.toDouble() / max(1L, before.usedBytes).toDouble() * 100.0
        report.addSample(
            DocxWebProofSample(
                phase = "lifecycle",
                source = "cdp-runtime-heap",
                metric = "retained-heap-growth",
                value = retainedPercent,
                unit = "percent",
                detail = "before=${before.usedBytes};after=${after.usedBytes};delta=$retainedBytes",
            ),
        )
        val completedCycles = lifecycle.long("cycles").toInt()
        val restored = lifecycle["restored"] == true
        report.addGate(
            DocxWebProofGate(
                name = "renderer-destroy-remount-cycles",
                status = if (completedCycles == LIFECYCLE_CYCLES && restored) {
                    DocxWebProofGateStatus.PASS
                } else {
                    DocxWebProofGateStatus.FAIL
                },
                target = "$LIFECYCLE_CYCLES actual atlas-destroy/atlas-remount command cycles with restored bounded map",
                observed = "$completedCycles cycles; restored=$restored",
                detail = "Each command awaited revision settlement plus Canvas teardown/remount and the original node count.",
                required = true,
            ),
        )
        report.addGate(
            DocxWebProofGate(
                name = "browser-heap-retention",
                status = if (retainedPercent < MAXIMUM_RETAINED_HEAP_PERCENT) {
                    DocxWebProofGateStatus.PASS
                } else {
                    DocxWebProofGateStatus.FAIL
                },
                target = "< $MAXIMUM_RETAINED_HEAP_PERCENT% additional browser JS heap after forced GC",
                observed = "${"%.3f".format(java.util.Locale.ROOT, retainedPercent)}% ($retainedBytes bytes)",
                detail = "Runtime.getHeapUsage immediately before and after 100 real renderer lifecycle cycles.",
                required = true,
            ),
        )
    }

    private fun navigateForCapture(
        session: DocxWebCdpSession,
        phase: String,
        scenario: DocxWebSmokeScenario,
        captureMode: String,
    ) {
        session.setViewport(scenario.viewport())
        val networkStart = session.networkSampleCount()
        val started = System.nanoTime()
        session.navigate(
            "$baseUrl?docx-smoke=1&scenario=${scenario.id}&capture=1&capture-mode=$captureMode&proof=$phase",
        )
        waitForCaptureReady(session, phase, captureMode)
        session.evaluate(
            "new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))",
            awaitPromise = true,
        )
        positionAtlasForCapture(session)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000.0
        report.addSample(
            DocxWebProofSample(
                phase = phase,
                source = "cdp-navigation",
                metric = "capture-ready-latency",
                value = elapsedMillis,
                unit = "ms",
            ),
        )
        session.networkSamplesSince(networkStart).forEach { sample ->
            report.addSample(
                DocxWebProofSample(
                    phase = phase,
                    source = "cdp-network",
                    metric = "encoded-transfer-bytes",
                    value = sample.encodedBytes.toDouble(),
                    unit = "bytes",
                    detail =
                        "${sample.url.ifBlank { "request=${sample.requestId}" }};" +
                            "cdpTimestampSeconds=${sample.timestampSeconds}",
                ),
            )
        }
        collectPageMetrics(session, "$phase-cold-start")
        session.evaluate("window.__docxBrowserProofMetrics.reset()")
    }

    private fun waitForCaptureReady(
        session: DocxWebCdpSession,
        phase: String,
        captureMode: String,
    ) {
        session.waitUntil("$phase capture-ready Atlas") {
            session.evaluate(
                """
                (() => {
                    const root = document.getElementById("docx-app");
                    if (!root) return false;
                    if (root.getAttribute("data-docx-smoke-result") === "failed") {
                        throw new Error(root.getAttribute("data-docx-smoke-error") || "capture failed");
                    }
                    return root.getAttribute("data-docx-state") === "ready" &&
                        root.getAttribute("data-docx-smoke-result") === "passed";
                })()
                """.trimIndent(),
            ) == true
        }
        val expectedRenderer = if (captureMode == "evidence") CANVAS_RENDERER else DETAILED_RENDERER
        session.waitUntil("$phase $expectedRenderer paint") {
            session.evaluate(
                """
                (() => {
                    const visit = (selector, root = document) => {
                        const match = root.querySelector(selector);
                        if (match) return match;
                        for (const element of root.querySelectorAll("*")) {
                            if (element.shadowRoot) {
                                const nested = visit(selector, element.shadowRoot);
                                if (nested) return nested;
                            }
                        }
                        return null;
                    };
                    const host = visit("[data-docx-atlas-host]");
                    const svg = visit("[data-docx-atlas-svg]");
                    const canvas = visit("[data-docx-atlas-canvas]");
                    const visible = (element) => {
                        if (!element || getComputedStyle(element).display === "none") return false;
                        const rect = element.getBoundingClientRect();
                        return rect.width > 0 && rect.height > 0;
                    };
                    const nodeCount = Number(host?.getAttribute("data-docx-atlas-node-count") || 0);
                    const nodes = host ? Array.from(host.querySelectorAll("[data-docx-atlas-node-id]")) : [];
                    const rendererReady = "$expectedRenderer" === "canvas" ?
                        host?.getAttribute("data-docx-atlas-renderer") === "canvas" && visible(canvas) :
                        host?.getAttribute("data-docx-atlas-renderer") === "d3" &&
                            nodes.length === nodeCount && visible(svg) && nodes.some(visible);
                    return rendererReady &&
                        host?.getAttribute("data-docx-atlas-state") === "ready" &&
                        host?.getAttribute("data-docx-atlas-session-request-state") === "settled" &&
                        nodeCount > 0 &&
                        visible(visit("[data-srcx-chart-controls]")) &&
                        visible(visit("[data-srcx-global-search-input]"));
                })()
                """.trimIndent(),
            ) == true
        }
        if (expectedRenderer == CANVAS_RENDERER) waitForSemanticNodePaint(session, phase)
        if (phase == "ultrawide") {
            report.addGate(
                DocxWebProofGate(
                    name = "stable-detailed-overview",
                    status = DocxWebProofGateStatus.PASS,
                    target = "The published file-first Atlas remains visible after semantic request settlement",
                    observed = "D3 renderer stayed ready with one visible DOM node per bounded AtlasFrame node",
                    detail = "Canvas remains available only for deliberate semantic drill-down scopes.",
                    required = true,
                ),
            )
        }
    }

    private fun positionAtlasForCapture(session: DocxWebCdpSession) {
        session.evaluate(
            """
            (() => {
                const visible = (element) => {
                    const rect = element.getBoundingClientRect();
                    const style = getComputedStyle(element);
                    return rect.width > 0 && rect.height > 0 &&
                        style.display !== "none" && style.visibility !== "hidden";
                };
                const visit = (selector, root = document) => {
                    const match = Array.from(root.querySelectorAll(selector)).find(visible);
                    if (match) return match;
                    for (const element of root.querySelectorAll("*")) {
                        if (element.shadowRoot) {
                            const nested = visit(selector, element.shadowRoot);
                            if (nested) return nested;
                        }
                    }
                    return null;
                };
                const host = visit('[data-docx-atlas-host][data-docx-atlas-renderer="d3"]');
                const surface = host?.closest("[data-docx-atlas-surface]");
                const graph = host?.closest("[data-srcx-architecture-graph]");
                if (surface && graph) {
                    const distance = graph.getBoundingClientRect().top - surface.getBoundingClientRect().top;
                    surface.scrollTop = Math.max(0, surface.scrollTop + distance - 8);
                }
                return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            })()
            """.trimIndent(),
            awaitPromise = true,
        )
    }

    private fun waitForSemanticNodePaint(
        session: DocxWebCdpSession,
        phase: String,
    ) {
        runCatching {
            session.waitUntil("$phase semantic-node Canvas paint") {
                session.evaluate(SEMANTIC_NODE_PAINT_EXPRESSION) == true
            }
        }.getOrElse { failure ->
            val diagnostic =
                runCatching { session.evaluateJsonObject(SEMANTIC_NODE_PAINT_DIAGNOSTIC_EXPRESSION) }
                    .fold(
                        onSuccess = { evidence -> evidence.toString() },
                        onFailure = { error -> "unavailable (${error.message})" },
                    )
            throw GradleException(
                "${failure.message}\nSemantic-node paint diagnostics: $diagnostic",
                failure,
            )
        }
    }

    private fun capture(
        session: DocxWebCdpSession,
        state: String,
    ) {
        val spec = requireNotNull(expectedCaptures[state]) { "Unknown browser-proof capture state $state" }
        val file = reportDirectory.resolve(spec.fileName)
        val renderer = session.evaluate(RENDERER_EXPRESSION)?.toString()
        if (renderer == CANVAS_RENDERER) waitForSemanticNodePaint(session, state)
        session.capturePng(file)
        val dimensions = pngDimensions(file) ?: 0 to 0
        report.addCapture(
            DocxWebProofCapture(
                state = state,
                file = spec.fileName,
                bytes = file.length(),
                width = dimensions.first,
                height = dimensions.second,
            ),
        )
    }

    private fun captureExternal(
        session: DocxWebCdpSession,
        state: String,
    ) {
        val spec = requireNotNull(externalExpectedCaptures[state]) { "Unknown external capture state $state" }
        val file = reportDirectory.resolve(spec.fileName)
        val renderer = session.evaluate(RENDERER_EXPRESSION)?.toString()
        if (renderer == CANVAS_RENDERER) {
            waitForSemanticNodePaint(session, state)
        } else if (renderer != DETAILED_RENDERER) {
            throw GradleException("External capture $state has unsupported Atlas renderer $renderer")
        }
        session.capturePng(file)
        val dimensions = pngDimensions(file) ?: 0 to 0
        report.addCapture(
            DocxWebProofCapture(
                state = state,
                file = spec.fileName,
                bytes = file.length(),
                width = dimensions.first,
                height = dimensions.second,
            ),
        )
    }

    private fun collectPageMetrics(
        session: DocxWebCdpSession,
        phase: String,
    ) {
        val snapshot =
            session.evaluateJsonObject(
                "window.__docxBrowserProofMetrics.snapshot()",
            )
        snapshot.list("longTasks").forEach { entry ->
            val task = entry.stringMap()
            report.addSample(
                DocxWebProofSample(
                    phase = phase,
                    source = "performance-observer",
                    metric = "long-task-duration",
                    value = task.number("duration"),
                    unit = "ms",
                    detail = "start=${task.number("startTime")}",
                ),
            )
        }
        snapshot.list("frameIntervals").forEachIndexed { index, interval ->
            report.addSample(
                DocxWebProofSample(
                    phase = phase,
                    source = "request-animation-frame",
                    metric = "frame-interval",
                    value = interval.toDoubleValue(),
                    unit = "ms",
                    detail = "sample=$index",
                ),
            )
        }
        snapshot.list("resources").forEach { entry ->
            val resource = entry.stringMap()
            listOf("transferSize", "encodedBodySize", "decodedBodySize").forEach { metric ->
                report.addSample(
                    DocxWebProofSample(
                        phase = phase,
                        source = "resource-timing",
                        metric = metric,
                        value = resource.number(metric),
                        unit = "bytes",
                        detail = resource["name"].toString(),
                    ),
                )
            }
        }
        snapshot["navigation"]?.stringMap()?.forEach { (metric, value) ->
            if (value is Number) {
                report.addSample(
                    DocxWebProofSample(
                        phase = phase,
                        source = "navigation-timing",
                        metric = metric,
                        value = value.toDouble(),
                        unit = "ms",
                    ),
                )
            }
        }
    }

    private fun addHeapSamples(
        phase: String,
        point: String,
        usage: DocxWebCdpHeapUsage,
    ) {
        mapOf(
            "used-js-heap" to usage.usedBytes,
            "total-js-heap" to usage.totalBytes,
            "embedder-used-heap" to usage.embedderUsedBytes,
            "backing-storage" to usage.backingStorageBytes,
        ).forEach { (metric, bytes) ->
            report.addSample(
                DocxWebProofSample(
                    phase = phase,
                    source = "cdp-runtime-heap",
                    metric = metric,
                    value = bytes.toDouble(),
                    unit = "bytes",
                    detail = point,
                ),
            )
        }
    }

    private fun validateCaptures(startedAtMillis: Long) {
        val failures = captureFailures(expectedCaptures, startedAtMillis)
        report.addGate(
            DocxWebProofGate(
                name = "fresh-browser-captures",
                status = if (failures.isEmpty()) DocxWebProofGateStatus.PASS else DocxWebProofGateStatus.FAIL,
                target = "Fresh nontrivial wide, compact, fullscreen, and evidence-open PNG files",
                observed = failures.ifEmpty { listOf("All four captures are fresh and >= $MINIMUM_CAPTURE_BYTES bytes") }
                    .joinToString("; "),
                detail = "Page.captureScreenshot output retained under the task report directory.",
                required = true,
            ),
        )
        if (externalReportUrl?.isNotBlank() == true) {
            val externalFailures = captureFailures(externalExpectedCaptures, startedAtMillis)
            report.addGate(
                DocxWebProofGate(
                    name = "fresh-external-live-captures",
                    status =
                        if (externalFailures.isEmpty()) {
                            DocxWebProofGateStatus.PASS
                        } else {
                            DocxWebProofGateStatus.FAIL
                        },
                    target = "Fresh nontrivial regenerated FooBar wide, compact, fullscreen, and evidence-open PNG files",
                    observed =
                        externalFailures
                            .ifEmpty {
                                listOf("All four external captures are fresh and >= $MINIMUM_CAPTURE_BYTES bytes")
                            }.joinToString("; "),
                    detail = "IHDR dimensions and creation timestamps are verified from retained files.",
                    required = true,
                ),
            )
        }
    }

    private fun captureFailures(
        captures: Map<String, CaptureSpec>,
        startedAtMillis: Long,
    ): List<String> =
        captures.mapNotNull { (state, spec) ->
            val file = reportDirectory.resolve(spec.fileName)
            when {
                !file.isFile -> "$state is missing ${spec.fileName}"
                file.length() < MINIMUM_CAPTURE_BYTES ->
                    "$state ${spec.fileName} is only ${file.length()} bytes"
                pngDimensions(file) != (spec.scenario.width to spec.scenario.height) ->
                    "$state ${spec.fileName} IHDR is ${pngDimensions(file)}; " +
                        "expected ${spec.scenario.width}x${spec.scenario.height}"
                file.lastModified() + FILE_TIMESTAMP_TOLERANCE_MILLIS < startedAtMillis ->
                    "$state ${spec.fileName} is stale"
                else -> null
            }
        }

    private fun pngDimensions(file: File): Pair<Int, Int>? =
        runCatching {
            val header = ByteArray(PNG_IHDR_BYTES)
            val count = file.inputStream().use { input -> input.read(header) }
            if (count != PNG_IHDR_BYTES || !header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
            if (header.copyOfRange(12, 16).toString(Charsets.US_ASCII) != "IHDR") return null
            val dimensions = ByteBuffer.wrap(header, 16, 8)
            dimensions.int to dimensions.int
        }.getOrNull()

    private fun expectedArtifactFiles(): List<File> =
        expectedCaptures.values.map { spec -> reportDirectory.resolve(spec.fileName) } +
            listOf(
                reportDirectory.resolve(DocxWebBrowserProofReport.JSON_FILE),
                reportDirectory.resolve(DocxWebBrowserProofReport.CSV_FILE),
            ) +
            externalExpectedCaptures.values
                .takeIf { externalReportUrl?.isNotBlank() == true }
                .orEmpty()
                .map { spec -> reportDirectory.resolve(spec.fileName) }

    private data class CaptureSpec(
        val fileName: String,
        val scenario: DocxWebSmokeScenario,
    )

    private data class NativeFullscreenState(
        val documentFullscreen: Boolean,
        val documentMatches: Boolean,
        val shadowApplicable: Boolean,
        val shadowMatches: Boolean,
        val graphFullscreen: Boolean,
        val viewportFilled: Boolean,
    ) {
        val isEntered: Boolean
            get() =
                documentFullscreen &&
                    documentMatches &&
                    (!shadowApplicable || shadowMatches) &&
                    graphFullscreen &&
                    viewportFilled
    }

    private companion object {
        const val DETAILED_RENDERER = "d3"
        const val LIFECYCLE_CYCLES = 100
        const val MAXIMUM_RETAINED_HEAP_PERCENT = 10.0
        const val MINIMUM_FIT_COVERAGE = 0.6
        const val MINIMUM_CAPTURE_BYTES = 10_000L
        const val FILE_TIMESTAMP_TOLERANCE_MILLIS = 2_000L
        const val PNG_IHDR_BYTES = 24
        const val FULLSCREEN_BUTTON_SELECTOR = "[data-srcx-graph-fullscreen]"
        const val CHART_CONTROLS_TOGGLE_SELECTOR = "[data-srcx-chart-controls-toggle]"
        const val GLOBAL_SEARCH_INPUT_SELECTOR = "[data-srcx-global-search-input]"
        const val CANVAS_RENDERER = "canvas"
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        val RENDERER_EXPRESSION =
            """
            (() => {
                const visit = (selector, root = document) => {
                    const match = root.querySelector(selector);
                    if (match) return match;
                    for (const element of root.querySelectorAll("*")) {
                        if (element.shadowRoot) {
                            const nested = visit(selector, element.shadowRoot);
                            if (nested) return nested;
                        }
                    }
                    return null;
                };
                return visit("[data-docx-atlas-host]")?.getAttribute("data-docx-atlas-renderer") || "";
            })()
            """.trimIndent()

        val REFERENCE_ATLAS_READY_EXPRESSION =
            """
            (() => {
                const visit = (selector, root = document) => {
                    const match = root.querySelector(selector);
                    if (match) return match;
                    for (const element of root.querySelectorAll("*")) {
                        if (element.shadowRoot) {
                            const nested = visit(selector, element.shadowRoot);
                            if (nested) return nested;
                        }
                    }
                    return null;
                };
                const root = document.getElementById("docx-app");
                const host = visit("[data-docx-atlas-host]");
                const surface = visit("[data-docx-atlas-surface]");
                const svg = visit("[data-docx-atlas-svg]");
                const visible = (element) => {
                    if (!element) return false;
                    const style = getComputedStyle(element);
                    if (style.display === "none" || style.visibility === "hidden") return false;
                    const rect = element.getBoundingClientRect();
                    return rect.width > 0 && rect.height > 0;
                };
                const nodeCount = Number(host?.getAttribute("data-docx-atlas-node-count") || 0);
                const nodes = host ? Array.from(host.querySelectorAll("[data-docx-atlas-node-id]")) : [];
                const stylesReady = !surface?.hasAttribute("data-docx-atlas-styles-ready") ||
                    surface.getAttribute("data-docx-atlas-styles-ready") === "true";
                return root?.getAttribute("data-docx-state") === "ready" &&
                    host?.getAttribute("data-docx-atlas-state") === "ready" &&
                    stylesReady && visible(surface) &&
                    nodeCount > 0 && nodes.length === nodeCount &&
                    visible(svg) && nodes.some(visible) && !visit("[data-docx-atlas-canvas]");
            })()
            """.trimIndent()

        val FULLSCREEN_RESTORATION_CAPTURE_EXPRESSION =
            """
            (() => {
                const one = (selector, current = document) => {
                    const direct = current.querySelector(selector);
                    if (direct) return direct;
                    for (const element of current.querySelectorAll("*")) {
                        if (element.shadowRoot) {
                            const nested = one(selector, element.shadowRoot);
                            if (nested) return nested;
                        }
                    }
                    return null;
                };
                const graph = one("[data-srcx-architecture-graph]");
                const host = one("[data-docx-atlas-host]");
                const surface = one("[data-docx-atlas-surface]");
                const renderer = host?.getAttribute("data-docx-atlas-renderer");
                const api = renderer === "d3" ? window.docxAtlasD3 : window.docxAtlasMap;
                if (!graph || !host || !surface || !api) {
                    throw new Error("Atlas fullscreen restoration boundary is unavailable");
                }
                const rect = graph.getBoundingClientRect();
                const surfaceRect = surface.getBoundingClientRect();
                const snapshot = JSON.parse(api.snapshot(host));
                window.__docxFullscreenReturnProof = {
                    scrollX: window.scrollX,
                    scrollY: window.scrollY,
                    left: rect.left,
                    top: rect.top,
                    width: rect.width,
                    height: rect.height,
                    surfaceScrollLeft: surface.scrollLeft,
                    surfaceScrollTop: surface.scrollTop,
                    surfaceLeft: surfaceRect.left,
                    surfaceTop: surfaceRect.top,
                    cameraX: snapshot.panX,
                    cameraY: snapshot.panY,
                    scale: snapshot.scale,
                };
                return window.__docxFullscreenReturnProof;
            })()
            """.trimIndent()

        val FULLSCREEN_RESTORATION_RESULT_EXPRESSION =
            """
            (() => {
                const one = (selector, current = document) => {
                    const direct = current.querySelector(selector);
                    if (direct) return direct;
                    for (const element of current.querySelectorAll("*")) {
                        if (element.shadowRoot) {
                            const nested = one(selector, element.shadowRoot);
                            if (nested) return nested;
                        }
                    }
                    return null;
                };
                const graph = one("[data-srcx-architecture-graph]");
                const host = one("[data-docx-atlas-host]");
                const surface = one("[data-docx-atlas-surface]");
                const button = one("[data-srcx-graph-fullscreen]");
                const before = window.__docxFullscreenReturnProof;
                const renderer = host?.getAttribute("data-docx-atlas-renderer");
                const api = renderer === "d3" ? window.docxAtlasD3 : window.docxAtlasMap;
                if (!graph || !host || !surface || !button || !before || !api) {
                    return { boundaryRestored: false };
                }
                const rect = graph.getBoundingClientRect();
                const surfaceRect = surface.getBoundingClientRect();
                const snapshot = JSON.parse(api.snapshot(host));
                let active = document.activeElement;
                while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
                return {
                    boundaryRestored: true,
                    scrollRestored: Math.abs(window.scrollX - before.scrollX) <= 2 &&
                        Math.abs(window.scrollY - before.scrollY) <= 2,
                    dimensionsRestored: Math.abs(rect.left - before.left) <= 2 &&
                        Math.abs(rect.top - before.top) <= 2 &&
                        Math.abs(rect.width - before.width) <= 2 &&
                        Math.abs(rect.height - before.height) <= 2,
                    beforeRect: {
                        left: before.left,
                        top: before.top,
                        width: before.width,
                        height: before.height,
                    },
                    currentRect: rect.toJSON(),
                    beforeSurface: {
                        scrollLeft: before.surfaceScrollLeft,
                        scrollTop: before.surfaceScrollTop,
                        left: before.surfaceLeft,
                        top: before.surfaceTop,
                    },
                    currentSurface: {
                        scrollLeft: surface.scrollLeft,
                        scrollTop: surface.scrollTop,
                        left: surfaceRect.left,
                        top: surfaceRect.top,
                    },
                    savedSurfaceFound: Boolean(graph.__docxFullscreenScrollContainer),
                    savedSurfaceScrollTop: graph.__docxFullscreenReturnScrollTop,
                    savedGraphTop: graph.__docxFullscreenReturnGraphTop,
                    restoreState: graph.getAttribute("data-srcx-fullscreen-restore-state"),
                    restoreCancelEvent: graph.getAttribute("data-srcx-fullscreen-restore-cancel-event"),
                    restoreFrame: graph.getAttribute("data-srcx-fullscreen-restore-frame"),
                    restoreScrollTop: graph.getAttribute("data-srcx-fullscreen-restore-scroll-top"),
                    restoreGraphTop: graph.getAttribute("data-srcx-fullscreen-restore-graph-top"),
                    cameraRestored: Math.abs(snapshot.panX - before.cameraX) <= 0.01 &&
                        Math.abs(snapshot.panY - before.cameraY) <= 0.01 &&
                        Math.abs(snapshot.scale - before.scale) <= 0.0001,
                    focusRestored: active === button,
                };
            })()
            """.trimIndent()

        val CACHE_QUERY_ROOTS_EXPRESSION =
            """
            (() => {
                let roots = [document];
                let dirty = true;
                const collectRoots = () => {
                    if (!dirty && roots.every((root) => root === document || root.host?.isConnected)) {
                        return roots;
                    }
                    roots = [document];
                    for (let index = 0; index < roots.length; index += 1) {
                        for (const element of roots[index].querySelectorAll("*")) {
                            if (element.shadowRoot && !roots.includes(element.shadowRoot)) roots.push(element.shadowRoot);
                        }
                    }
                    dirty = false;
                    return roots;
                };
                new MutationObserver(() => {
                    dirty = true;
                }).observe(document.documentElement, { childList: true, subtree: true });
                Object.defineProperty(window, "__docxBrowserProofQueryRoots", {
                    configurable: true,
                    get: collectRoots,
                });
                return collectRoots().length;
            })()
            """.trimIndent()

        val PERFORMANCE_PROBE_SCRIPT =
            """
            (() => {
                const state = {
                    longTasks: [],
                    frameIntervals: [],
                    previousFrame: null,
                };
                if (typeof PerformanceObserver === "function" &&
                    PerformanceObserver.supportedEntryTypes?.includes("longtask")) {
                    const observer = new PerformanceObserver((list) => {
                        for (const entry of list.getEntries()) {
                            state.longTasks.push({ startTime: entry.startTime, duration: entry.duration });
                        }
                    });
                    observer.observe({ type: "longtask", buffered: true });
                }
                const frame = (timestamp) => {
                    if (state.previousFrame !== null && state.frameIntervals.length < 5000) {
                        state.frameIntervals.push(timestamp - state.previousFrame);
                    }
                    state.previousFrame = timestamp;
                    requestAnimationFrame(frame);
                };
                requestAnimationFrame(frame);
                window.__docxBrowserProofMetrics = Object.freeze({
                    reset() {
                        state.longTasks.length = 0;
                        state.frameIntervals.length = 0;
                        state.previousFrame = null;
                    },
                    snapshot() {
                        const navigation = performance.getEntriesByType("navigation")[0];
                        return {
                            longTasks: state.longTasks.splice(0),
                            frameIntervals: state.frameIntervals.splice(0),
                            resources: performance.getEntriesByType("resource").map((entry) => ({
                                name: entry.name,
                                transferSize: entry.transferSize,
                                encodedBodySize: entry.encodedBodySize,
                                decodedBodySize: entry.decodedBodySize,
                            })),
                            navigation: navigation ? {
                                duration: navigation.duration,
                                responseEnd: navigation.responseEnd,
                                domContentLoadedEventEnd: navigation.domContentLoadedEventEnd,
                                loadEventEnd: navigation.loadEventEnd,
                            } : {},
                        };
                    },
                });
            })();
            """.trimIndent()

        val SEMANTIC_NODE_PAINT_EXPRESSION =
            """
            (() => {
                const deepQuery = (selector, current = document) => {
                    const direct = current.querySelector(selector);
                    if (direct) return direct;
                    for (const element of current.querySelectorAll("*")) {
                        if (element.shadowRoot) {
                            const nested = deepQuery(selector, element.shadowRoot);
                            if (nested) return nested;
                        }
                    }
                    return null;
                };
                const host = deepQuery("[data-docx-atlas-host]");
                const canvas = deepQuery("[data-docx-atlas-canvas]");
                const api = window.docxAtlasMap;
                if (!host || !canvas || !api) return false;
                const layoutRevision = Number(host.getAttribute("data-docx-atlas-layout-revision") || 0);
                const paintRevision = Number(host.getAttribute("data-docx-atlas-paint-revision") || 0);
                const paintedNodeId = host.getAttribute("data-docx-atlas-painted-node-id") || "";
                const pointX = Number(host.getAttribute("data-docx-atlas-painted-node-x"));
                const pointY = Number(host.getAttribute("data-docx-atlas-painted-node-y"));
                if (layoutRevision <= 0 || paintRevision !== layoutRevision || !paintedNodeId ||
                    !Number.isFinite(pointX) || !Number.isFinite(pointY)) return false;
                let snapshot;
                try {
                    snapshot = JSON.parse(api.snapshot(host));
                } catch (_) {
                    return false;
                }
                if (snapshot.paintRevision !== paintRevision || snapshot.paintedNodeId !== paintedNodeId ||
                    snapshot.nodes <= 0 || !snapshot.paintedNodePoint ||
                    Math.abs(snapshot.paintedNodePoint.x - pointX) > 0.01 ||
                    Math.abs(snapshot.paintedNodePoint.y - pointY) > 0.01) return false;
                const rect = canvas.getBoundingClientRect();
                if (rect.width <= 0 || rect.height <= 0 || pointX < 0 || pointY < 0 ||
                    pointX >= rect.width || pointY >= rect.height) return false;
                try {
                    const pixelX = Math.min(canvas.width - 1, Math.max(0, Math.floor(pointX * canvas.width / rect.width)));
                    const pixelY = Math.min(canvas.height - 1, Math.max(0, Math.floor(pointY * canvas.height / rect.height)));
                    const pixel = canvas.getContext("2d", { alpha: false }).getImageData(pixelX, pixelY, 1, 1).data;
                    return Math.abs(pixel[0] - 246) > 2 ||
                        Math.abs(pixel[1] - 241) > 2 ||
                        Math.abs(pixel[2] - 230) > 2;
                } catch (_) {
                    return false;
                }
            })()
            """.trimIndent()

        val SEMANTIC_NODE_PAINT_DIAGNOSTIC_EXPRESSION =
            """
            (() => {
                const deepQuery = (selector, current = document) => {
                    const direct = current.querySelector(selector);
                    if (direct) return direct;
                    for (const element of current.querySelectorAll("*")) {
                        if (element.shadowRoot) {
                            const nested = deepQuery(selector, element.shadowRoot);
                            if (nested) return nested;
                        }
                    }
                    return null;
                };
                const host = deepQuery("[data-docx-atlas-host]");
                const canvas = deepQuery("[data-docx-atlas-canvas]");
                const attribute = (name) => host?.getAttribute(name) || "";
                let snapshot = {};
                try {
                    snapshot = host && window.docxAtlasMap ? JSON.parse(window.docxAtlasMap.snapshot(host)) : {};
                } catch (error) {
                    snapshot = { error: String(error) };
                }
                const rect = canvas?.getBoundingClientRect();
                const readPixel = (x, y) => {
                    if (!canvas || !rect || rect.width <= 0 || rect.height <= 0 ||
                        !Number.isFinite(x) || !Number.isFinite(y) ||
                        x < 0 || y < 0 || x >= rect.width || y >= rect.height) return null;
                    try {
                        const pixelX = Math.min(canvas.width - 1,
                            Math.max(0, Math.floor(x * canvas.width / rect.width)));
                        const pixelY = Math.min(canvas.height - 1,
                            Math.max(0, Math.floor(y * canvas.height / rect.height)));
                        return Array.from(
                            canvas.getContext("2d", { alpha: false }).getImageData(pixelX, pixelY, 1, 1).data,
                        );
                    } catch (error) {
                        return [String(error)];
                    }
                };
                const pointX = Number(attribute("data-docx-atlas-painted-node-x"));
                const pointY = Number(attribute("data-docx-atlas-painted-node-y"));
                const sampleNodePixels = (snapshot.sampleNodes || []).map((node) => ({
                    id: node.id,
                    centerX: node.x + node.width / 2,
                    centerY: node.y + node.height / 2,
                    pixel: readPixel(node.x + node.width / 2, node.y + node.height / 2),
                }));
                return {
                    hostFound: Boolean(host),
                    canvasFound: Boolean(canvas),
                    state: attribute("data-docx-atlas-state"),
                    runtime: attribute("data-docx-atlas-layout-runtime"),
                    layoutRevision: attribute("data-docx-atlas-layout-revision"),
                    paintRevision: attribute("data-docx-atlas-paint-revision"),
                    paintedNodeId: attribute("data-docx-atlas-painted-node-id"),
                    paintedNodeX: attribute("data-docx-atlas-painted-node-x"),
                    paintedNodeY: attribute("data-docx-atlas-painted-node-y"),
                    drawError: attribute("data-docx-atlas-draw-error"),
                    geometryDiagnostic: attribute("data-docx-atlas-geometry-diagnostic"),
                    canvas: rect ? {
                        cssX: rect.x,
                        cssY: rect.y,
                        cssWidth: rect.width,
                        cssHeight: rect.height,
                        backingWidth: canvas.width,
                        backingHeight: canvas.height,
                    } : {},
                    paintedPixel: readPixel(pointX, pointY),
                    sampleNodePixels,
                    cameraCommands: window.__docxAtlasCameraCommands || [],
                    snapshot,
                };
            })()
            """.trimIndent()

        val RENDERER_LIFECYCLE_EXPRESSION =
            """
            (async () => {
                const root = document.getElementById("docx-app");
                if (!root || typeof window.docxSmokeCommand !== "function") {
                    throw new Error("Lifecycle smoke command boundary is unavailable");
                }
                const deepQuery = (selector, current = document) => {
                    const direct = current.querySelector(selector);
                    if (direct) return direct;
                    for (const element of current.querySelectorAll("*")) {
                        if (element.shadowRoot) {
                            const nested = deepQuery(selector, element.shadowRoot);
                            if (nested) return nested;
                        }
                    }
                    return null;
                };
                const host = deepQuery("[data-docx-atlas-host]");
                if (!host) throw new Error("Atlas host is unavailable for lifecycle proof");
                const baselineNodeCount = Number(host.getAttribute("data-docx-atlas-node-count") || 0);
                if (baselineNodeCount <= 0 ||
                    host.getAttribute("data-docx-atlas-state") !== "ready" ||
                    host.getAttribute("data-docx-atlas-renderer") !== "canvas") {
                    throw new Error("Lifecycle proof requires a bounded ready map");
                }
                const wait = (predicate, label) => new Promise((resolve, reject) => {
                    const started = performance.now();
                    const poll = () => {
                        if (predicate()) return resolve();
                        if (performance.now() - started > 5000) {
                            return reject(new Error("Timed out waiting for " + label));
                        }
                        requestAnimationFrame(poll);
                    };
                    poll();
                });
                const issue = async (name) => {
                    const priorRevision = Number(root.getAttribute("data-docx-command-revision") || 0);
                    window.docxSmokeCommand(name, "");
                    await wait(() =>
                        root.getAttribute("data-docx-state") === "ready" &&
                        Number(root.getAttribute("data-docx-command-revision") || 0) > priorRevision,
                        name + " command settlement");
                    const error = root.getAttribute("data-docx-command-error") || "";
                    if (error) throw new Error(name + " failed: " + error);
                };
                const durations = [];
                for (let index = 0; index < 100; index += 1) {
                    const started = performance.now();
                    await issue("atlas-destroy");
                    await wait(() => !deepQuery("[data-docx-atlas-canvas]"), "Canvas teardown");
                    await issue("atlas-remount");
                    await wait(() => {
                        const restoredHost = deepQuery("[data-docx-atlas-host]");
                        return Boolean(deepQuery("[data-docx-atlas-canvas]")) &&
                            restoredHost?.getAttribute("data-docx-atlas-state") === "ready" &&
                            restoredHost?.getAttribute("data-docx-atlas-renderer") === "canvas" &&
                            restoredHost?.getAttribute("data-docx-atlas-layout-runtime") === "worker" &&
                            Number(restoredHost?.getAttribute("data-docx-atlas-layout-revision") || 0) > 0 &&
                            Number(restoredHost?.getAttribute("data-docx-atlas-node-count") || 0) ===
                                baselineNodeCount;
                    }, "Canvas remount");
                    durations.push(performance.now() - started);
                }
                const restoredHost = deepQuery("[data-docx-atlas-host]");
                return {
                    cycles: durations.length,
                    durations,
                    restored: Boolean(deepQuery("[data-docx-atlas-canvas]")) &&
                        restoredHost?.getAttribute("data-docx-atlas-state") === "ready" &&
                        restoredHost?.getAttribute("data-docx-atlas-renderer") === "canvas" &&
                        restoredHost?.getAttribute("data-docx-atlas-layout-runtime") === "worker" &&
                        Number(restoredHost?.getAttribute("data-docx-atlas-layout-revision") || 0) > 0 &&
                        Number(restoredHost?.getAttribute("data-docx-atlas-node-count") || 0) === baselineNodeCount,
                };
            })()
            """.trimIndent()

        val EXTERNAL_REPORT_INTERACTION_EXPRESSION =
            """
            (async () => {
                const root = document.getElementById("docx-app");
                const checkpoints = [];
                const deepAll = (selector) =>
                    (window.__docxBrowserProofQueryRoots || [document])
                        .flatMap((queryRoot) => Array.from(queryRoot.querySelectorAll(selector)));
                const deepOne = (selector) => deepAll(selector)[0] || null;
                const checkpoint = (name) => {
                    const surface = deepOne("[data-docx-atlas-surface]");
                    const host = deepOne("[data-docx-atlas-host]");
                    checkpoints.push({
                        name,
                        time: performance.now(),
                        modelUpdateMs: Number(surface?.getAttribute("data-docx-atlas-model-update-ms") || 0),
                        surfaceUpdateMs: Number(surface?.getAttribute("data-docx-atlas-surface-update-ms") || 0),
                        runtimeUpdateMs: Number(host?.getAttribute("data-docx-atlas-runtime-update-ms") || 0),
                        runtimeBreakdown: host?.getAttribute("data-docx-atlas-runtime-update-breakdown") || "",
                    });
                };
                checkpoint("interaction-start");
                const visible = (element) => element && getComputedStyle(element).display !== "none";
                const wait = (predicate, label) => new Promise((resolve, reject) => {
                    const started = performance.now();
                    const poll = () => {
                        if (predicate()) return resolve();
                        if (performance.now() - started > 15000) {
                            return reject(new Error("Timed out waiting for external " + label));
                        }
                        requestAnimationFrame(poll);
                    };
                    poll();
                });
                const choose = async (kind) => {
                    const trigger = deepOne('[data-srcx-scope-level-button="' + kind + '"]');
                    if (!trigger) throw new Error("Missing external " + kind + " scope trigger");
                    if (trigger.getAttribute("aria-expanded") !== "true") trigger.click();
                    await wait(() => trigger.getAttribute("aria-expanded") === "true", kind + " sheet");
                    const choices = deepAll('[data-srcx-filter-' + kind + ']')
                        .filter((choice) => visible(choice) &&
                            choice.getAttribute('data-srcx-filter-' + kind) !== "all");
                    if (!choices.length) throw new Error("No external " + kind + " choices");
                    const choice = choices[0];
                    const value = choice.getAttribute('data-srcx-filter-' + kind) || choice.textContent.trim();
                    choice.click();
                    return value;
                };
                let scopeHost = deepOne("[data-docx-atlas-host]");
                const buildRequestRevision = Number(
                    scopeHost?.getAttribute("data-docx-atlas-session-request-revision") || 0,
                );
                await choose("build");
                try {
                    await wait(() => {
                        scopeHost = deepOne("[data-docx-atlas-host]");
                        return scopeHost?.getAttribute("data-docx-atlas-selected-build-ids") !== "" &&
                            Number(scopeHost?.getAttribute("data-docx-atlas-session-request-revision") || 0) >
                                buildRequestRevision &&
                            scopeHost?.getAttribute("data-docx-atlas-session-request-state") === "settled" &&
                            scopeHost?.getAttribute("data-docx-atlas-state") === "ready";
                    }, "build semantic slice");
                } catch (error) {
                    const scopeHost = deepOne("[data-docx-atlas-host]");
                    throw new Error("External build selection failed: " + JSON.stringify({
                        selectedBuildId: root.getAttribute("data-docx-selected-build-id"),
                        selectedBuildIds: scopeHost?.getAttribute("data-docx-atlas-selected-build-ids"),
                        requestState: scopeHost?.getAttribute("data-docx-atlas-session-request-state"),
                        requestRevision: scopeHost?.getAttribute("data-docx-atlas-session-request-revision"),
                        atlasState: scopeHost?.getAttribute("data-docx-atlas-state"),
                        frameSettled: root.getAttribute("data-docx-graph-frame-settled"),
                        nodeCount: scopeHost?.getAttribute("data-docx-atlas-node-count"),
                        cause: String(error),
                    }));
                }
                checkpoint("build-settled");
                scopeHost = deepOne("[data-docx-atlas-host]");
                const projectRequestRevision = Number(
                    scopeHost?.getAttribute("data-docx-atlas-session-request-revision") || 0,
                );
                const project = await choose("project");
                await wait(() => {
                    scopeHost = deepOne("[data-docx-atlas-host]");
                    return scopeHost?.getAttribute("data-docx-atlas-selected-project-ids") !== "" &&
                        Number(scopeHost?.getAttribute("data-docx-atlas-session-request-revision") || 0) >
                            projectRequestRevision &&
                        scopeHost?.getAttribute("data-docx-atlas-session-request-state") === "settled" &&
                        scopeHost?.getAttribute("data-docx-atlas-state") === "ready";
                }, "project semantic slice");
                checkpoint("project-settled");
                await wait(() => {
                    const sourceChoices = deepAll('[data-srcx-filter-source-set]')
                        .filter((choice) => visible(choice) &&
                            choice.getAttribute('data-srcx-filter-source-set') !== "all");
                    return sourceChoices.length > 0 && sourceChoices.every((choice) =>
                        Number(choice.getAttribute("data-srcx-source-set-id-count") || 0) === 1);
                }, "project-scoped source-set choices");
                scopeHost = deepOne("[data-docx-atlas-host]");
                const sourceSetRequestRevision = Number(
                    scopeHost?.getAttribute("data-docx-atlas-session-request-revision") || 0,
                );
                const sourceSet = await choose("source-set");
                try {
                    await wait(() => {
                        const sourceHost = deepOne("[data-docx-atlas-host]");
                        return sourceHost?.getAttribute("data-docx-atlas-state") === "ready" &&
                            Number(sourceHost?.getAttribute("data-docx-atlas-session-request-revision") || 0) >
                                sourceSetRequestRevision &&
                            sourceHost?.getAttribute("data-docx-atlas-session-request-state") === "settled" &&
                            deepAll('[data-srcx-filter-source-set][aria-pressed="true"]').some(visible);
                    }, "source-set semantic slice");
                } catch (error) {
                    const sourceHost = deepOne("[data-docx-atlas-host]");
                    throw new Error("External source-set selection failed: " + JSON.stringify({
                        chosenSourceSet: sourceSet,
                        selectedSourceSetIds:
                            sourceHost?.getAttribute("data-docx-atlas-selected-source-set-ids"),
                        requestState: sourceHost?.getAttribute("data-docx-atlas-session-request-state"),
                        requestRevision: sourceHost?.getAttribute("data-docx-atlas-session-request-revision"),
                        priorRequestRevision: sourceSetRequestRevision,
                        requestSourceSetIds:
                            sourceHost?.getAttribute("data-docx-atlas-session-request-source-set-ids"),
                        atlasState: sourceHost?.getAttribute("data-docx-atlas-state"),
                        nodeCount: sourceHost?.getAttribute("data-docx-atlas-node-count"),
                        selectedChoices: deepAll('[data-srcx-filter-source-set][aria-pressed="true"]')
                            .filter(visible)
                            .map((choice) => ({
                                value: choice.getAttribute("data-srcx-filter-source-set"),
                                state: choice.getAttribute("data-srcx-selection-state"),
                            })),
                        graphResources: performance.getEntriesByType("resource")
                            .filter((entry) => entry.name.endsWith("/api/graph"))
                            .map((entry) => ({
                                startTime: entry.startTime,
                                duration: entry.duration,
                                transferSize: entry.transferSize,
                                encodedBodySize: entry.encodedBodySize,
                                decodedBodySize: entry.decodedBodySize,
                            })),
                        cause: String(error),
                    }));
                }
                checkpoint("source-set-settled");
                const openScope = deepOne('[data-srcx-scope-level-button][aria-expanded="true"]');
                if (openScope) {
                    openScope.click();
                    await wait(() => !deepOne('[data-srcx-scope-level-button][aria-expanded="true"]'),
                        "scope sheet dismissal");
                }
                let host = deepOne("[data-docx-atlas-host]");
                const api = window.docxAtlasMap;
                if (!host || !api) throw new Error("External Canvas API is unavailable");
                const initialSnapshot = JSON.parse(api.snapshot(host));
                const visibleRelation = initialSnapshot.sampleRelations[0];
                if (visibleRelation) {
                    api.command(host, "select-edge:" + visibleRelation.id);
                    try {
                        await wait(() => {
                            const detail = deepOne("[data-srcx-detail]");
                            const sourceState = deepOne("[data-docx-atlas-surface]")
                                ?.getAttribute("data-docx-atlas-source-state");
                            const occurrenceRows =
                                detail?.querySelectorAll(".srcx-dashboard__architecture-occurrence-row").length || 0;
                            const sourceLines = detail?.querySelectorAll("[data-srcx-source-line]").length || 0;
                            return deepOne("[data-docx-atlas-host]")
                                    ?.getAttribute("data-docx-atlas-selected-edge-id") === visibleRelation.id &&
                                Boolean(detail) && visible(detail) &&
                                (sourceState === "ready" || sourceState === "idle") &&
                                (occurrenceRows > 0 || sourceLines > 0);
                        }, "relationship source evidence");
                    } catch (error) {
                        const detail = deepOne("[data-srcx-detail]");
                        const surface = deepOne("[data-docx-atlas-surface]");
                        const currentHost = deepOne("[data-docx-atlas-host]");
                        throw new Error("External relationship evidence failed: " + JSON.stringify({
                            relationId: visibleRelation.id,
                            rootSelection: root.getAttribute("data-docx-selected-edge-id"),
                            hostSelection: currentHost?.getAttribute("data-docx-atlas-selected-edge-id"),
                            detailVisible: Boolean(detail) && visible(detail),
                            detailText: detail?.textContent?.slice(0, 1000),
                            sourceState: surface?.getAttribute("data-docx-atlas-source-state"),
                            evidenceState: surface?.getAttribute("data-docx-atlas-evidence-state"),
                            occurrenceRows:
                                detail?.querySelectorAll(".srcx-dashboard__architecture-occurrence-row").length || 0,
                            sourceLines: detail?.querySelectorAll("[data-srcx-source-line]").length || 0,
                            cause: String(error),
                        }));
                    }
                    const normalizedProject = project.replace(/^:/, "").split(":").pop();
                    checkpoint("evidence-open");
                    return {
                        evidenceOpen: true,
                        relationId: visibleRelation.id,
                        searchTerm: normalizedProject || project,
                        checkpoints,
                    };
                }
                const opened = new Set();
                let fileNode = null;
                for (let attempt = 0; attempt < 16 && !fileNode; attempt += 1) {
                    const snapshot = JSON.parse(api.snapshot(host));
                    fileNode = snapshot.sampleNodes.find((node) => node.id.startsWith("file:"));
                    if (fileNode) break;
                    const expandablePrefixes = ["source-package:", "source-set:", "project:", "build:", "workspace:"];
                    const paintedContainer = snapshot.paintedNodeId
                        ? { id: snapshot.paintedNodeId, container: true }
                        : null;
                    const expansionCandidates = paintedContainer
                        ? [paintedContainer, ...snapshot.sampleNodes]
                        : snapshot.sampleNodes;
                    const container = expandablePrefixes
                        .map((prefix) => expansionCandidates.find((node) =>
                            node.container && node.id.startsWith(prefix) && !opened.has(node.id)))
                        .find(Boolean);
                    if (!container) break;
                    opened.add(container.id);
                    const previousRevision = Number(host.getAttribute("data-docx-atlas-layout-revision") || 0);
                    const previousNodeCount = snapshot.nodes;
                    api.command(host, "open-node:" + container.id);
                    try {
                        await wait(() => {
                            const currentHost = deepOne("[data-docx-atlas-host]");
                            if (!currentHost || currentHost.getAttribute("data-docx-atlas-state") !== "ready") {
                                return false;
                            }
                            const currentSnapshot = JSON.parse(api.snapshot(currentHost));
                            return currentHost !== host ||
                                Number(currentHost.getAttribute("data-docx-atlas-layout-revision") || 0) >
                                    previousRevision ||
                                currentSnapshot.nodes !== previousNodeCount;
                        }, "semantic expansion");
                    } catch (error) {
                        const currentHost = deepOne("[data-docx-atlas-host]");
                        const currentSnapshot = currentHost ? JSON.parse(api.snapshot(currentHost)) : {};
                        throw new Error("External semantic expansion failed: " + JSON.stringify({
                            containerId: container.id,
                            opened: Array.from(opened),
                            previousRevision,
                            currentRevision: Number(
                                currentHost?.getAttribute("data-docx-atlas-layout-revision") || 0,
                            ),
                            previousNodeCount,
                            currentNodeCount: currentSnapshot.nodes,
                            atlasState: currentHost?.getAttribute("data-docx-atlas-state"),
                            requestState: currentHost?.getAttribute("data-docx-atlas-session-request-state"),
                            requestRevision: currentHost?.getAttribute("data-docx-atlas-session-request-revision"),
                            requestExpandedIds:
                                currentHost?.getAttribute("data-docx-atlas-session-request-expanded-ids"),
                            focusExpandedIds:
                                currentHost?.getAttribute("data-docx-atlas-session-focus-expanded-ids"),
                            snapshot: currentSnapshot,
                            cause: String(error),
                        }));
                    }
                    host = deepOne("[data-docx-atlas-host]");
                }
                if (!fileNode) {
                    throw new Error(
                        "External bounded drill did not expose a file node in 16 bounded eight-sample windows",
                    );
                }
                api.command(host, "select-node:" + fileNode.id);
                await wait(() => {
                    const detail = deepOne("[data-srcx-detail]");
                    const sourceState = deepOne("[data-docx-atlas-surface]")
                        ?.getAttribute("data-docx-atlas-source-state");
                    return root.getAttribute("data-docx-selected-node-id") === fileNode.id &&
                        Boolean(detail) && visible(detail) &&
                        (sourceState === "ready" || sourceState === "idle") &&
                        detail.querySelectorAll("[data-srcx-source-line]").length > 0;
                }, "source evidence");
                const normalizedProject = project.replace(/^:/, "").split(":").pop();
                return {
                    evidenceOpen: true,
                    fileNodeId: fileNode.id,
                    searchTerm: normalizedProject || project,
                };
            })()
            """.trimIndent()

        const val ATLAS_CANVAS_SELECTOR = "[data-docx-atlas-canvas]"

        val FULLSCREEN_RESTORATION_KEYS =
            listOf("boundaryRestored", "scrollRestored", "dimensionsRestored", "cameraRestored", "focusRestored")

        val EXTERNAL_DETAILED_OVERVIEW_EXPRESSION =
            """
            (() => {
                const roots = window.__docxBrowserProofQueryRoots || [document];
                const one = (selector) => roots.map((root) => root.querySelector(selector)).find(Boolean);
                const host = one("[data-docx-atlas-host]");
                const svg = one("[data-docx-atlas-svg]");
                const canvas = one("[data-docx-atlas-canvas]");
                const surface = one("[data-docx-atlas-surface]");
                const graph = host?.closest("[data-srcx-architecture-graph]");
                const styleRoot = surface?.getRootNode();
                const stylesheetLinks = surface ? Array.from(surface.querySelectorAll("link[rel=stylesheet]")) : [];
                const allSurfaces = roots.flatMap((root, rootIndex) =>
                    Array.from(root.querySelectorAll("[data-docx-atlas-surface]")).map((candidate) => ({
                        rootIndex,
                        candidate,
                    })));
                const allHosts = roots.flatMap((root, rootIndex) =>
                    Array.from(root.querySelectorAll("[data-docx-atlas-host]")).map((candidate) => ({
                        rootIndex,
                        candidate,
                    })));
                const api = window.docxAtlasD3;
                let snapshot = {};
                try {
                    snapshot = host && api ? JSON.parse(api.snapshot(host)) : {};
                } catch (_) {
                    snapshot = {};
                }
                const renderedNodeCount = host?.querySelectorAll("[data-docx-atlas-node-id]").length || 0;
                const nodeCount = Number(host?.getAttribute("data-docx-atlas-node-count") || 0);
                return {
                    stable: host?.getAttribute("data-docx-atlas-state") === "ready" &&
                        surface?.getAttribute("data-docx-atlas-styles-ready") === "true" &&
                        surface?.getAttribute("data-docx-atlas-active-styles") === "3" &&
                        host?.getAttribute("data-docx-atlas-renderer") === "d3" &&
                        Boolean(svg) && !canvas && nodeCount > 0 && renderedNodeCount === nodeCount &&
                        snapshot.state === "ready" && snapshot.nodeCount === nodeCount,
                    nodes: nodeCount,
                    renderedNodes: renderedNodeCount,
                    edges: Number(host?.getAttribute("data-docx-atlas-edge-count") || 0),
                    selectedNodeId: snapshot.selectedNodeId || null,
                    scale: Number(snapshot.scale || 0),
                    viewportMode: snapshot.viewportMode || "",
                    documentScrollY: window.scrollY,
                    surfaceScrollTop: Number(surface?.scrollTop || 0),
                    pointerScrollTop: Number(host?.getAttribute("data-docx-atlas-pointer-scroll-top") || 0),
                    selectionScrollTop: Number(host?.getAttribute("data-docx-atlas-selection-scroll-top") || 0),
                    preservedScrollTop: Number(host?.getAttribute("data-docx-atlas-preserved-scroll-top") || 0),
                    restoreRequest: Number(surface?.getAttribute("data-docx-atlas-scroll-restore-request") || 0),
                    restoreSync: Number(surface?.getAttribute("data-docx-atlas-scroll-restore-sync") || 0),
                    restoreFrame: Number(surface?.getAttribute("data-docx-atlas-scroll-restore-frame") || 0),
                    restoreReason: surface?.getAttribute("data-docx-atlas-scroll-restore-reason") || null,
                    d3SelectionScroll: Number(surface?.getAttribute("data-docx-atlas-d3-selection-scroll") || 0),
                    d3RestoreSync: Number(surface?.getAttribute("data-docx-atlas-d3-restore-sync") || 0),
                    d3RestoreFrame: Number(surface?.getAttribute("data-docx-atlas-d3-restore-frame") || 0),
                    surfaceConnected: Boolean(surface?.isConnected),
                    hostConnected: Boolean(host?.isConnected),
                    surfaceClientHeight: Number(surface?.clientHeight || 0),
                    surfaceScrollHeight: Number(surface?.scrollHeight || 0),
                    graphTop: Number(graph?.getBoundingClientRect().top || 0),
                    stylesheetCount: Number(styleRoot?.styleSheets?.length || 0),
                    stylesheetLinks: stylesheetLinks.map((link) => ({
                        connected: link.isConnected,
                        href: link.href,
                        loaded: Boolean(link.sheet),
                        disabled: Boolean(link.sheet?.disabled),
                    })),
                    surfaceOverflowY: surface ? getComputedStyle(surface).overflowY : null,
                    surfaceFontFamily: surface ? getComputedStyle(surface).fontFamily : null,
                    surfaces: allSurfaces.map(({ rootIndex, candidate }) => ({
                        rootIndex,
                        rect: candidate.getBoundingClientRect().toJSON(),
                        display: getComputedStyle(candidate).display,
                        fontFamily: getComputedStyle(candidate).fontFamily,
                        stylesheetCount: Number(candidate.getRootNode()?.styleSheets?.length || 0),
                    })),
                    hosts: allHosts.map(({ rootIndex, candidate }) => ({
                        rootIndex,
                        rect: candidate.getBoundingClientRect().toJSON(),
                        renderer: candidate.getAttribute("data-docx-atlas-renderer"),
                        nodes: candidate.getAttribute("data-docx-atlas-node-count"),
                        runtimeOwner: candidate.getAttribute("data-docx-atlas-runtime-owner"),
                    })),
                    scrollDistance: Math.max(120, Math.min(window.innerHeight * 0.45, 420)),
                };
            })()
            """.trimIndent()

        val EXTERNAL_DETAILED_LENS_STATE_EXPRESSION =
            """
            (() => {
                const roots = window.__docxBrowserProofQueryRoots || [document];
                const one = (selector) => roots.map((root) => root.querySelector(selector)).find(Boolean);
                const host = one("[data-docx-atlas-host]");
                const surface = one("[data-docx-atlas-surface]");
                const sheet = one("[data-srcx-filters-sheet]");
                const pressed = sheet?.querySelector('[data-srcx-graph-view][aria-pressed="true"]');
                return {
                    filtersOpen: Boolean(sheet) && !sheet.hidden,
                    renderer: host?.getAttribute("data-docx-atlas-renderer") || "",
                    lens: host?.getAttribute("data-docx-atlas-lens") || "",
                    surfaceLens: surface?.getAttribute("data-srcx-graph-lens") || "",
                    pressedLens: pressed?.getAttribute("data-srcx-graph-view") || "",
                    nodes: Number(host?.getAttribute("data-docx-atlas-node-count") || 0),
                    edges: Number(host?.getAttribute("data-docx-atlas-edge-count") || 0),
                    nodeIds: Array.from(host?.querySelectorAll("[data-docx-atlas-node-id]") || [])
                        .map((node) => node.getAttribute("data-docx-atlas-node-id"))
                        .filter(Boolean)
                        .sort(),
                    edgeIds: Array.from(host?.querySelectorAll("[data-docx-atlas-edge-id]") || [])
                        .map((edge) => edge.getAttribute("data-docx-atlas-edge-id"))
                        .filter(Boolean)
                        .sort(),
                };
            })()
            """.trimIndent()

        val EXTERNAL_DETAILED_HISTORY_STATE_EXPRESSION =
            """
            (() => {
                const roots = window.__docxBrowserProofQueryRoots || [document];
                const one = (selector) => roots.map((root) => root.querySelector(selector)).find(Boolean);
                const host = one("[data-docx-atlas-host]");
                const surface = one("[data-docx-atlas-surface]");
                const back = one("[data-srcx-history-back]");
                const forward = one("[data-srcx-history-forward]");
                return {
                    selectedNodeId: host?.getAttribute("data-docx-atlas-selected-node-id") || "",
                    canonicalNodeId: document.getElementById("docx-app")
                        ?.getAttribute("data-docx-selected-node-id") || "",
                    canBack: Boolean(back) && !back.disabled,
                    canForward: Boolean(forward) && !forward.disabled,
                    publishedCanBack: surface?.getAttribute("data-docx-atlas-history-can-back") || "",
                    publishedCanForward: surface?.getAttribute("data-docx-atlas-history-can-forward") || "",
                    backDisabled: Boolean(back?.disabled),
                    forwardDisabled: Boolean(forward?.disabled),
                    trustedClickTarget: window.__docxCdpTrustedClickTarget || null,
                };
            })()
            """.trimIndent()

        val EXTERNAL_DETAILED_OVERVIEW_CLICK_TARGET_EXPRESSION =
            """
            (() => {
                const roots = window.__docxBrowserProofQueryRoots || [document];
                const host = roots.map((root) => root.querySelector("[data-docx-atlas-host]")).find(Boolean);
                const candidates = host ? Array.from(host.querySelectorAll(
                    '[data-docx-atlas-node-id][data-docx-atlas-entity-type="file"]')) : [];
                const visible = candidates.find((node) => {
                    const hit = node.querySelector(".srcx-dashboard__architecture-svg-node-hit");
                    const rect = hit?.getBoundingClientRect();
                    if (!rect) return false;
                    const x = rect.left + rect.width / 2;
                    const y = rect.top + rect.height / 2;
                    const root = node.getRootNode();
                    const hitAtCenter = root.elementFromPoint?.(x, y);
                    return rect.width > 0 && rect.height > 0 &&
                        x >= 0 && x < window.innerWidth && y >= 0 && y < window.innerHeight &&
                        getComputedStyle(node).pointerEvents !== "none" && node.contains(hitAtCenter);
                });
                return visible ? {
                    id: visible.getAttribute("data-docx-atlas-node-id"),
                    ariaLabel: visible.getAttribute("aria-label"),
                    x: (() => {
                        const hit = visible.querySelector(".srcx-dashboard__architecture-svg-node-hit");
                        const matrix = hit?.getScreenCTM?.();
                        return matrix ? new DOMPoint(0, 0).matrixTransform(matrix).x : 0;
                    })(),
                    y: (() => {
                        const hit = visible.querySelector(".srcx-dashboard__architecture-svg-node-hit");
                        const matrix = hit?.getScreenCTM?.();
                        return matrix ? new DOMPoint(0, 0).matrixTransform(matrix).y : 0;
                    })(),
                } : { id: null, candidateCount: candidates.length };
            })()
            """.trimIndent()

        val EXTERNAL_DETAILED_OVERVIEW_CLICK_DIAGNOSTIC_EXPRESSION =
            """
            (() => ({
                trusted: window.__docxCdpTrustedClick === true,
                target: window.__docxCdpTrustedClickTarget || null,
                hitTest: window.__docxCdpTrustedClickHitTest || null,
            }))()
            """.trimIndent()

        val EXTERNAL_NODE_SELECTION_TARGET_EXPRESSION =
            """
            (() => {
                const roots = window.__docxBrowserProofQueryRoots || [document];
                const one = (selector) => roots.map((root) => root.querySelector(selector)).find(Boolean);
                const host = one("[data-docx-atlas-host]");
                const canvas = one("[data-docx-atlas-canvas]");
                const api = window.docxAtlasMap;
                if (!host || !canvas || !api) {
                    throw new Error("Live Canvas selection boundary is unavailable");
                }
                const before = JSON.parse(api.snapshot(host));
                const node = before.sampleNodes.find((candidate) => candidate.id.startsWith("build:")) ||
                    before.sampleNodes.find((candidate) => candidate.id !== before.paintedNodeId);
                if (!node) throw new Error("Live Canvas exposes no selectable semantic node");
                const canvasRect = canvas.getBoundingClientRect();
                const targetY = canvasRect.top + node.y + node.height / 2;
                return {
                    selectedId: node.id,
                    beforeNodes: before.nodes,
                    layoutRevision: Number(host.getAttribute("data-docx-atlas-layout-revision") || 0),
                    requestRevision: Number(host.getAttribute("data-docx-atlas-session-request-revision") || 0),
                    cameraX: before.panX,
                    cameraY: before.panY,
                    scale: before.scale,
                    x: node.x + node.width / 2,
                    y: node.y + node.height / 2,
                    scrollDelta: targetY - window.innerHeight / 2,
                    scrollY: window.scrollY,
                    canvasTop: canvasRect.top,
                    targetViewportY: targetY,
                    targetVisible: targetY >= 24 && targetY <= window.innerHeight - 24,
                    startedAt: performance.now(),
                };
            })()
            """.trimIndent()

        val EXTERNAL_NODE_SELECTION_RESULT_EXPRESSION =
            """
            (() => {
                const roots = window.__docxBrowserProofQueryRoots || [document];
                const one = (selector) => roots.map((root) => root.querySelector(selector)).find(Boolean);
                const host = one("[data-docx-atlas-host]");
                const api = window.docxAtlasMap;
                const after = host && api && JSON.parse(api.snapshot(host));
                return {
                    preserved: Boolean(after && after.nodes > 0 && after.paintedNodeId &&
                        after.layoutState === "ready"),
                    selectedId: after?.selectedNodeId || null,
                    previewNodeId: after?.previewNodeId || null,
                    afterNodes: after?.nodes || 0,
                    layoutRevision: Number(host?.getAttribute("data-docx-atlas-layout-revision") || 0),
                    requestRevision: Number(host?.getAttribute("data-docx-atlas-session-request-revision") || 0),
                    cameraX: after?.panX || 0,
                    cameraY: after?.panY || 0,
                    scale: after?.scale || 0,
                    paintedAt: performance.now(),
                    hostSelection: host?.getAttribute("data-docx-atlas-selected-node-id"),
                    state: host?.getAttribute("data-docx-atlas-state"),
                    requestState: host?.getAttribute("data-docx-atlas-session-request-state"),
                    requestError: host?.getAttribute("data-docx-atlas-session-request-error"),
                    clickTarget: window.__docxCdpTrustedClickTarget || null,
                    clickHitTest: window.__docxCdpTrustedClickHitTest || null,
                    scrollY: window.scrollY,
                    canvasRect: one("[data-docx-atlas-canvas]")?.getBoundingClientRect().toJSON(),
                };
            })()
            """.trimIndent()

        val EXTERNAL_NAVIGATION_TARGET_EXPRESSION =
            """
            (() => {
                const roots = window.__docxBrowserProofQueryRoots || [document];
                const one = (selector) => roots.map((root) => root.querySelector(selector)).find(Boolean);
                const host = one("[data-docx-atlas-host]");
                const canvas = one("[data-docx-atlas-canvas]");
                const api = window.docxAtlasMap;
                if (!host || !canvas || !api) throw new Error("Live Canvas navigation boundary is unavailable");
                const snapshot = JSON.parse(api.snapshot(host));
                const rect = canvas.getBoundingClientRect();
                const node = snapshot.sampleNodes.find((candidate) =>
                    candidate.width >= 24 && candidate.height >= 24);
                if (!node) throw new Error("Live Canvas exposes no node surface for direct grab-to-pan");
                const point = { x: node.x + node.width / 2, y: node.y + node.height / 2 };
                return {
                    x: point.x,
                    y: point.y,
                    nodes: snapshot.nodes,
                    layoutRevision: Number(host.getAttribute("data-docx-atlas-layout-revision") || 0),
                    requestRevision: Number(host.getAttribute("data-docx-atlas-session-request-revision") || 0),
                    cameraX: snapshot.panX,
                    cameraY: snapshot.panY,
                    scale: snapshot.scale,
                };
            })()
            """.trimIndent()

        val EXTERNAL_NAVIGATION_RESULT_EXPRESSION =
            """
            (() => {
                const roots = window.__docxBrowserProofQueryRoots || [document];
                const one = (selector) => roots.map((root) => root.querySelector(selector)).find(Boolean);
                const host = one("[data-docx-atlas-host]");
                const detail = one("[data-srcx-detail]");
                const api = window.docxAtlasMap;
                const snapshot = host && api && JSON.parse(api.snapshot(host));
                return {
                    detailOpen: Boolean(detail && !detail.hidden),
                    preserved: Boolean(snapshot && snapshot.nodes > 0 && snapshot.paintedNodeId &&
                        snapshot.layoutState === "ready"),
                    nodes: snapshot?.nodes || 0,
                    layoutRevision: Number(host?.getAttribute("data-docx-atlas-layout-revision") || 0),
                    requestRevision: Number(host?.getAttribute("data-docx-atlas-session-request-revision") || 0),
                    paintedNodeId: snapshot?.paintedNodeId || null,
                    cameraX: snapshot?.panX || 0,
                    cameraY: snapshot?.panY || 0,
                    scale: snapshot?.scale || 0,
                    canvasWidth: snapshot?.canvas?.width || 0,
                    canvasHeight: snapshot?.canvas?.height || 0,
                    coverage: snapshot ? Math.max(
                        snapshot.worldBounds.width * snapshot.scale / Math.max(1, snapshot.canvas.width),
                        snapshot.worldBounds.height * snapshot.scale / Math.max(1, snapshot.canvas.height),
                    ) : 0,
                };
            })()
            """.trimIndent()
    }
}

private fun DocxWebSmokeScenario.viewport(): DocxWebCdpViewport = DocxWebCdpViewport(width, height)

@Suppress("UNCHECKED_CAST")
private fun Any?.stringMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

private fun Map<String, Any?>.number(key: String): Double =
    (get(key) as? Number)?.toDouble() ?: 0.0

private fun Map<String, Any?>.long(key: String): Long = number(key).toLong()

private fun Map<String, Any?>.list(key: String): List<Any?> = get(key) as? List<Any?> ?: emptyList()

private fun Any?.toDoubleValue(): Double = (this as? Number)?.toDouble() ?: 0.0
