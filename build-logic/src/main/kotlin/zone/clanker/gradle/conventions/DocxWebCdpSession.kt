package zone.clanker.gradle.conventions

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

internal data class DocxWebCdpViewport(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "CDP viewport dimensions must be positive" }
    }
}

internal data class DocxWebCdpNetworkSample(
    val requestId: String,
    val url: String,
    val requestBody: String?,
    val encodedBytes: Long,
    val timestampSeconds: Double,
)

internal data class DocxWebCdpHeapUsage(
    val usedBytes: Long,
    val totalBytes: Long,
    val embedderUsedBytes: Long,
    val backingStorageBytes: Long,
)

/** Small JDK-only Chrome DevTools Protocol client for the browser acceptance proof. */
internal class DocxWebCdpSession private constructor(
    private val process: Process,
    private val standardOutput: File,
    private val standardError: File,
    private val httpExecutor: java.util.concurrent.ExecutorService,
    private val httpClient: HttpClient,
) : WebSocket.Listener,
    AutoCloseable {
    private val commandIds = AtomicLong()
    private val pendingCommands = ConcurrentHashMap<Long, CompletableFuture<Map<String, Any?>>>()
    private val responseUrls = ConcurrentHashMap<String, String>()
    private val requestBodies = ConcurrentHashMap<String, String>()
    private val networkSamples = CopyOnWriteArrayList<DocxWebCdpNetworkSample>()
    private val messageBuffer = StringBuilder()
    private lateinit var socket: WebSocket

    fun setViewport(viewport: DocxWebCdpViewport) {
        command(
            "Emulation.setDeviceMetricsOverride",
            mapOf(
                "width" to viewport.width,
                "height" to viewport.height,
                "screenWidth" to viewport.width,
                "screenHeight" to viewport.height,
                "deviceScaleFactor" to 1,
                "mobile" to false,
            ),
        )
    }

    fun addScriptToEvaluateOnNewDocument(script: String) {
        command("Page.addScriptToEvaluateOnNewDocument", mapOf("source" to script))
    }

    fun navigate(url: String) {
        val response = command("Page.navigate", mapOf("url" to url))
        response["errorText"]?.toString()?.takeIf(String::isNotBlank)?.let { error ->
            throw failure("CDP could not navigate to $url: $error")
        }
        waitUntil("document load for $url") {
            evaluate("document.readyState === 'complete'") == true
        }
    }

    fun evaluate(
        expression: String,
        awaitPromise: Boolean = false,
    ): Any? {
        val response =
            command(
                "Runtime.evaluate",
                mapOf(
                    "expression" to expression,
                    "returnByValue" to true,
                    "awaitPromise" to awaitPromise,
                    "userGesture" to false,
                ),
            )
        response["exceptionDetails"]?.let { details ->
            throw failure("CDP JavaScript evaluation failed: ${JsonOutput.toJson(details)}")
        }
        return response.stringMap("result")["value"]
    }

    fun evaluateJsonObject(
        expression: String,
        awaitPromise: Boolean = false,
    ): Map<String, Any?> {
        val wrappedExpression =
            if (awaitPromise) {
                "(async () => JSON.stringify(await ($expression)))()"
            } else {
                "JSON.stringify($expression)"
            }
        val json = evaluate(wrappedExpression, awaitPromise)?.toString().orEmpty()
        if (json.isBlank() || json == "undefined") {
            throw failure("CDP JavaScript expression did not return a JSON object")
        }
        return JsonSlurper().parseText(json).stringMap()
    }

    fun waitUntil(
        description: String,
        timeoutSeconds: Long = DEFAULT_WAIT_SECONDS,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline && process.isAlive) {
            val satisfied =
                runCatching(condition)
                    .onFailure { failure -> lastFailure = failure }
                    .getOrDefault(false)
            if (satisfied) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        val suffix = lastFailure?.message?.let { message -> "; last probe failure: $message" }.orEmpty()
        throw failure("Timed out waiting for $description$suffix")
    }

    /** Dispatches a real CDP pointer sequence and proves the resulting click event was trusted. */
    fun trustedClick(selector: String) {
        dispatchTrustedClick(selector, null, null)
    }

    /** Clicks a point inside an element using a real CDP pointer sequence. */
    fun trustedClickAt(
        selector: String,
        offsetX: Double,
        offsetY: Double,
    ) {
        dispatchTrustedClick(selector, offsetX, offsetY)
    }

    /** Clicks an already hit-tested viewport coordinate using a real CDP pointer sequence. */
    fun trustedClickAtViewport(
        x: Double,
        y: Double,
    ) {
        evaluate(
            """
            (() => {
                window.__docxCdpTrustedClick = false;
                window.__docxCdpTrustedClickTarget = null;
                document.addEventListener("click", (event) => {
                    window.__docxCdpTrustedClick = event.isTrusted === true;
                    const target = event.composedPath()[0];
                    window.__docxCdpTrustedClickTarget = {
                        tag: target?.tagName || null,
                        className: target?.className || null,
                        atlasCanvas: target?.hasAttribute?.("data-docx-atlas-canvas") === true,
                    };
                }, { once: true, capture: true });
            })()
            """.trimIndent(),
        )
        dispatchMouseClick(x, y)
        waitUntil("trusted click at viewport coordinate") {
            evaluate("window.__docxCdpTrustedClick === true") == true
        }
    }

    /** Scrolls the document with a real wheel event, matching the user path into an embedded Atlas. */
    fun trustedScrollBy(deltaY: Double) {
        val target =
            evaluateJsonObject(
                """
                (() => {
                    window.__docxCdpTrustedWheel = false;
                    const deepQuery = (root, selector) => {
                        const direct = root.querySelector(selector);
                        if (direct) return direct;
                        for (const element of root.querySelectorAll("*")) {
                            if (element.shadowRoot) {
                                const nested = deepQuery(element.shadowRoot, selector);
                                if (nested) return nested;
                            }
                        }
                        return null;
                    };
                    const surface = deepQuery(document, "[data-docx-atlas-surface]");
                    const scrollElement = surface?.scrollHeight > surface?.clientHeight ? surface : document.scrollingElement;
                    const before = scrollElement.scrollTop;
                    const maximum = Math.max(0, scrollElement.scrollHeight - scrollElement.clientHeight);
                    const expected = Math.max(0, Math.min(maximum, before + $deltaY));
                    window.__docxCdpScrollBefore = before;
                    window.__docxCdpScrollExpected = expected;
                    window.__docxCdpScrollElement = scrollElement;
                    document.addEventListener("wheel", (event) => {
                        window.__docxCdpTrustedWheel = event.isTrusted === true;
                    }, { once: true, capture: true });
                    const rect = surface?.getBoundingClientRect() || {
                        left: 0,
                        top: 0,
                        right: document.documentElement.clientWidth,
                        height: window.innerHeight,
                    };
                    return {
                        x: Math.max(rect.left + 1, rect.right - 4),
                        y: Math.max(rect.top + 24, Math.min(rect.top + rect.height / 2, rect.top + 240)),
                        before,
                        expected,
                    };
                })()
                """.trimIndent(),
            )
        command(
            "Input.dispatchMouseEvent",
            mapOf(
                "type" to "mouseWheel",
                "x" to target.number("x"),
                "y" to target.number("y"),
                "deltaX" to 0,
                "deltaY" to deltaY,
            ),
        )
        waitUntil("trusted document scroll") {
            evaluate(
                """
                window.__docxCdpTrustedWheel === true &&
                    (Math.abs(window.__docxCdpScrollElement.scrollTop - window.__docxCdpScrollBefore) > 1 ||
                        window.__docxCdpScrollBefore === window.__docxCdpScrollExpected)
                """.trimIndent(),
            ) == true
        }
    }

    /** Drags between two points inside an element using real CDP mouse input. */
    fun trustedDragAt(
        selector: String,
        startOffsetX: Double,
        startOffsetY: Double,
        endOffsetX: Double,
        endOffsetY: Double,
    ) {
        val bounds = elementBounds(selector)
        val startX = bounds.number("left") + startOffsetX
        val startY = bounds.number("top") + startOffsetY
        val endX = bounds.number("left") + endOffsetX
        val endY = bounds.number("top") + endOffsetY
        command("Input.dispatchMouseEvent", mapOf("type" to "mouseMoved", "x" to startX, "y" to startY))
        command(
            "Input.dispatchMouseEvent",
            mapOf(
                "type" to "mousePressed",
                "x" to startX,
                "y" to startY,
                "button" to "left",
                "buttons" to 1,
                "clickCount" to 1,
            ),
        )
        command(
            "Input.dispatchMouseEvent",
            mapOf(
                "type" to "mouseMoved",
                "x" to endX,
                "y" to endY,
                "button" to "left",
                "buttons" to 1,
            ),
        )
        command(
            "Input.dispatchMouseEvent",
            mapOf(
                "type" to "mouseReleased",
                "x" to endX,
                "y" to endY,
                "button" to "left",
                "buttons" to 0,
                "clickCount" to 1,
            ),
        )
    }

    /** Wheel-zooms at a point inside an element using real CDP mouse input. */
    fun trustedWheelAt(
        selector: String,
        offsetX: Double,
        offsetY: Double,
        deltaY: Double,
    ) {
        val bounds = elementBounds(selector)
        val x = bounds.number("left") + offsetX
        val y = bounds.number("top") + offsetY
        command(
            "Input.dispatchMouseEvent",
            mapOf(
                "type" to "mouseWheel",
                "x" to x,
                "y" to y,
                "deltaX" to 0,
                "deltaY" to deltaY,
            ),
        )
    }

    private fun elementBounds(selector: String): Map<String, Any?> {
        val selectorJson = JsonOutput.toJson(selector)
        return evaluateJsonObject(
            """
                (() => {
                    const selector = $selectorJson;
                    const clickable = (root, candidate) => {
                        const rect = candidate.getBoundingClientRect();
                        const style = getComputedStyle(candidate);
                        const hit = root.elementFromPoint?.(
                            rect.left + rect.width / 2,
                            rect.top + rect.height / 2,
                        );
                        return rect.width > 0 && rect.height > 0 &&
                            rect.right > 0 && rect.bottom > 0 &&
                            rect.left < window.innerWidth && rect.top < window.innerHeight &&
                            style.display !== "none" && style.visibility !== "hidden" &&
                            style.pointerEvents !== "none" && candidate.contains(hit);
                    };
                    const deepQuery = (root) => {
                        const direct = Array.from(root.querySelectorAll(selector)).find(
                            (candidate) => clickable(root, candidate),
                        );
                        if (direct) return direct;
                        for (const element of root.querySelectorAll("*")) {
                            if (element.shadowRoot) {
                                const nested = deepQuery(element.shadowRoot);
                                if (nested) return nested;
                            }
                        }
                        return null;
                    };
                    const element = deepQuery(document);
                    if (!element) throw new Error("Missing pointer target " + selector);
                    const rect = element.getBoundingClientRect();
                    return { left: rect.left, top: rect.top, width: rect.width, height: rect.height };
                })()
            """.trimIndent(),
        )
    }

    private fun dispatchTrustedClick(
        selector: String,
        offsetX: Double?,
        offsetY: Double?,
    ) {
        val selectorJson = JsonOutput.toJson(selector)
        val offsetXJson = JsonOutput.toJson(offsetX)
        val offsetYJson = JsonOutput.toJson(offsetY)
        val target =
            evaluateJsonObject(
                """
                (() => {
                    const selector = $selectorJson;
                    const requestedOffsetX = $offsetXJson;
                    const requestedOffsetY = $offsetYJson;
                    const clickable = (root, candidate) => {
                        const rect = candidate.getBoundingClientRect();
                        const style = getComputedStyle(candidate);
                        const hit = root.elementFromPoint?.(
                            rect.left + rect.width / 2,
                            rect.top + rect.height / 2,
                        );
                        return rect.width > 0 && rect.height > 0 &&
                            rect.right > 0 && rect.bottom > 0 &&
                            rect.left < window.innerWidth && rect.top < window.innerHeight &&
                            style.display !== "none" && style.visibility !== "hidden" &&
                            style.pointerEvents !== "none" && candidate.contains(hit);
                    };
                    const deepQuery = (root) => {
                        const direct = Array.from(root.querySelectorAll(selector)).find(
                            (candidate) => clickable(root, candidate),
                        );
                        if (direct) return direct;
                        for (const element of root.querySelectorAll("*")) {
                            if (element.shadowRoot) {
                                const nested = deepQuery(element.shadowRoot);
                                if (nested) return nested;
                            }
                        }
                        return null;
                    };
                    const element = deepQuery(document);
                    if (!element) throw new Error("Missing trusted-click target " + selector);
                    const rect = element.getBoundingClientRect();
                    window.__docxCdpTrustedClick = false;
                    window.__docxCdpTrustedClickTarget = null;
                    document.addEventListener("click", (event) => {
                        window.__docxCdpTrustedClick = event.isTrusted === true;
                        const target = event.composedPath()[0];
                        window.__docxCdpTrustedClickTarget = {
                            tag: target?.tagName || null,
                            className: target?.className || null,
                            atlasCanvas: target?.hasAttribute?.("data-docx-atlas-canvas") === true,
                        };
                    }, { once: true, capture: true });
                    const screenMatrix = element.tagName?.toLowerCase() === "circle" ? element.getScreenCTM?.() : null;
                    const paintedCenter = screenMatrix ? new DOMPoint(0, 0).matrixTransform(screenMatrix) : null;
                    const offsetX = requestedOffsetX == null ? rect.width / 2 : requestedOffsetX;
                    const offsetY = requestedOffsetY == null ? rect.height / 2 : requestedOffsetY;
                    const x = paintedCenter?.x ??
                        (rect.left + Math.max(1, Math.min(rect.width - 1, offsetX)));
                    const y = paintedCenter?.y ??
                        (rect.top + Math.max(1, Math.min(rect.height - 1, offsetY)));
                    const describe = (candidate) => candidate && ({
                        tag: candidate.tagName || null,
                        className: candidate.className || null,
                        pointerEvents: getComputedStyle(candidate).pointerEvents,
                    });
                    const ancestors = [];
                    for (let current = element; current;) {
                        ancestors.push(describe(current));
                        current = current.parentElement || current.getRootNode()?.host || null;
                    }
                    window.__docxCdpTrustedClickHitTest = {
                        point: { x, y },
                        elements: document.elementsFromPoint(x, y).slice(0, 8).map(describe),
                        ancestors,
                    };
                    return { x, y };
                })()
                """.trimIndent(),
            )
        val x = target.number("x")
        val y = target.number("y")
        dispatchMouseClick(x, y)
        waitUntil("trusted click for $selector") {
            evaluate("window.__docxCdpTrustedClick === true") == true
        }
    }

    private fun dispatchMouseClick(
        x: Double,
        y: Double,
    ) {
        command("Input.dispatchMouseEvent", mapOf("type" to "mouseMoved", "x" to x, "y" to y))
        command(
            "Input.dispatchMouseEvent",
            mapOf(
                "type" to "mousePressed",
                "x" to x,
                "y" to y,
                "button" to "left",
                "buttons" to 1,
                "clickCount" to 1,
            ),
        )
        command(
            "Input.dispatchMouseEvent",
            mapOf(
                "type" to "mouseReleased",
                "x" to x,
                "y" to y,
                "button" to "left",
                "buttons" to 0,
                "clickCount" to 1,
            ),
        )
    }

    fun trustedType(
        selector: String,
        text: String,
    ) {
        trustedClick(selector)
        evaluate("document.execCommand('selectAll', false, null)")
        command("Input.insertText", mapOf("text" to text))
        val expected = JsonOutput.toJson(text)
        waitUntil("trusted text input for $selector") {
            evaluate(
                """
                (() => {
                    const active = document.activeElement?.shadowRoot?.activeElement || document.activeElement;
                    return active?.value === $expected;
                })()
                """.trimIndent(),
            ) == true
        }
    }

    /** Exits native browser modes through the same trusted Escape key a user presses. */
    fun pressEscape() {
        val key =
            mapOf(
                "key" to "Escape",
                "code" to "Escape",
                "windowsVirtualKeyCode" to 27,
                "nativeVirtualKeyCode" to 53,
            )
        command("Input.dispatchKeyEvent", key + ("type" to "keyDown"))
        command("Input.dispatchKeyEvent", key + ("type" to "keyUp"))
    }

    fun capturePng(file: File) {
        val response =
            command(
                "Page.captureScreenshot",
                mapOf(
                    "format" to "png",
                    "fromSurface" to true,
                    "captureBeyondViewport" to false,
                ),
            )
        val encoded = response["data"]?.toString().orEmpty()
        if (encoded.isBlank()) throw failure("CDP returned an empty screenshot")
        file.parentFile.mkdirs()
        file.writeBytes(Base64.getDecoder().decode(encoded))
    }

    fun collectGarbage() {
        command("HeapProfiler.collectGarbage")
    }

    fun startCpuProfile() {
        command("Profiler.enable")
        command("Profiler.start")
    }

    fun stopCpuProfile(file: File) {
        val profile = command("Profiler.stop")["profile"] ?: error("CDP returned no CPU profile")
        file.parentFile.mkdirs()
        file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(profile)))
    }

    fun heapUsage(): DocxWebCdpHeapUsage {
        val response = command("Runtime.getHeapUsage")
        return DocxWebCdpHeapUsage(
            usedBytes = response.long("usedSize"),
            totalBytes = response.long("totalSize"),
            embedderUsedBytes = response.long("embedderHeapUsedSize"),
            backingStorageBytes = response.long("backingStorageSize"),
        )
    }

    fun networkSampleCount(): Int = networkSamples.size

    fun networkSamplesSince(index: Int): List<DocxWebCdpNetworkSample> =
        networkSamples.drop(index.coerceIn(0, networkSamples.size))

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(
        webSocket: WebSocket,
        data: CharSequence,
        last: Boolean,
    ): java.util.concurrent.CompletionStage<*>? {
        val completeMessage =
            synchronized(messageBuffer) {
                messageBuffer.append(data)
                if (last) messageBuffer.toString().also { messageBuffer.setLength(0) } else null
            }
        completeMessage?.let(::acceptMessage)
        webSocket.request(1)
        return null
    }

    override fun onBinary(
        webSocket: WebSocket,
        data: ByteBuffer,
        last: Boolean,
    ): java.util.concurrent.CompletionStage<*>? {
        webSocket.request(1)
        return null
    }

    override fun onError(
        webSocket: WebSocket,
        error: Throwable,
    ) {
        pendingCommands.values.forEach { future -> future.completeExceptionally(error) }
        pendingCommands.clear()
    }

    override fun close() {
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "browser proof complete").get(1, TimeUnit.SECONDS) }
        if (process.isAlive) {
            process.descendants().use { descendants -> descendants.forEach(ProcessHandle::destroy) }
            process.destroy()
            if (!process.waitFor(TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.descendants().use { descendants -> descendants.forEach(ProcessHandle::destroyForcibly) }
                process.destroyForcibly()
                process.waitFor(TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS)
            }
        }
        httpExecutor.shutdownNow()
    }

    private fun command(
        method: String,
        parameters: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        check(::socket.isInitialized) { "CDP WebSocket is not connected" }
        val id = commandIds.incrementAndGet()
        val response = CompletableFuture<Map<String, Any?>>()
        pendingCommands[id] = response
        val payload = JsonOutput.toJson(mapOf("id" to id, "method" to method, "params" to parameters))
        runCatching { socket.sendText(payload, true).get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .onFailure { error ->
                pendingCommands.remove(id)
                throw failure("Could not send CDP command $method", error)
            }
        val envelope =
            runCatching { response.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .getOrElse { error ->
                    pendingCommands.remove(id)
                    throw failure("CDP command $method did not complete", error)
                }
        envelope["error"]?.let { error ->
            throw failure("CDP command $method failed: ${JsonOutput.toJson(error)}")
        }
        return envelope.stringMap("result")
    }

    private fun acceptMessage(message: String) {
        val envelope = JsonSlurper().parseText(message).stringMap()
        val id = (envelope["id"] as? Number)?.toLong()
        if (id != null) {
            pendingCommands.remove(id)?.complete(envelope)
            return
        }
        acceptEvent(envelope)
    }

    private fun acceptEvent(envelope: Map<String, Any?>) {
        val method = envelope["method"]?.toString() ?: return
        val parameters = envelope.stringMap("params")
        when (method) {
            "Network.requestWillBeSent" -> {
                val requestId = parameters["requestId"]?.toString() ?: return
                val request = parameters.stringMap("request")
                responseUrls[requestId] = request["url"]?.toString().orEmpty()
                request["postData"]?.toString()?.let { body -> requestBodies[requestId] = body }
            }

            "Network.responseReceived" -> {
                val requestId = parameters["requestId"]?.toString() ?: return
                responseUrls[requestId] = parameters.stringMap("response")["url"]?.toString().orEmpty()
            }

            "Network.loadingFinished" -> {
                val requestId = parameters["requestId"]?.toString() ?: return
                networkSamples +=
                    DocxWebCdpNetworkSample(
                        requestId = requestId,
                        url = responseUrls.remove(requestId).orEmpty(),
                        requestBody = requestBodies.remove(requestId),
                        encodedBytes = parameters.long("encodedDataLength"),
                        timestampSeconds = parameters.number("timestamp"),
                    )
            }
        }
    }

    private fun failure(
        message: String,
        cause: Throwable? = null,
    ): GradleException =
        GradleException(
            "$message\nChrome stdout:\n${standardOutput.safeTail()}\nChrome stderr:\n${standardError.safeTail()}",
            cause,
        )

    companion object {
        fun start(
            chromeExecutable: String,
            viewport: DocxWebCdpViewport,
            profileDirectory: File,
            standardOutput: File,
            standardError: File,
        ): DocxWebCdpSession {
            profileDirectory.mkdirs()
            val activePort = profileDirectory.resolve("DevToolsActivePort")
            if (activePort.exists() && !activePort.delete()) {
                throw GradleException("Could not clear stale Chrome DevTools marker ${activePort.invariantSeparatorsPath}")
            }
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
                    "--force-device-scale-factor=1",
                    "--no-first-run",
                    "--remote-allow-origins=*",
                    "--remote-debugging-port=0",
                    "--run-all-compositor-stages-before-draw",
                    "--user-data-dir=${profileDirectory.absolutePath}",
                    "--window-size=${viewport.width},${viewport.height}",
                    "about:blank",
                ).redirectOutput(standardOutput)
                    .redirectError(standardError)
                    .start()
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "docx-web-cdp-http").apply { isDaemon = true }
                }
            val client =
                HttpClient.newBuilder()
                    .executor(executor)
                    .connectTimeout(Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
                    .build()
            val session = DocxWebCdpSession(process, standardOutput, standardError, executor, client)
            runCatching {
                val port = session.awaitDevToolsPort(profileDirectory)
                val pageSocket = session.awaitPageSocket(port)
                session.socket =
                    client.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
                        .buildAsync(URI.create(pageSocket), session)
                        .get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                session.command("Page.enable")
                session.command("Runtime.enable")
                session.command("Network.enable")
                session.command("Performance.enable")
                session.command("HeapProfiler.enable")
                session.setViewport(viewport)
            }.onFailure {
                session.close()
                throw it
            }
            return session
        }

        private const val DEFAULT_WAIT_SECONDS = 30L
        private const val CONNECTION_TIMEOUT_SECONDS = 15L
        private const val COMMAND_TIMEOUT_SECONDS = 120L
        private const val TERMINATION_GRACE_SECONDS = 2L
        private const val POLL_INTERVAL_MILLIS = 50L
    }

    private fun awaitDevToolsPort(profileDirectory: File): Int {
        val activePort = profileDirectory.resolve("DevToolsActivePort")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONNECTION_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline && process.isAlive) {
            val port =
                activePort
                    .takeIf(File::isFile)
                    ?.readLines()
                    ?.firstOrNull()
                    ?.toIntOrNull()
            if (port != null) return port
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw failure("Chrome did not publish DevToolsActivePort")
    }

    private fun awaitPageSocket(port: Int): String {
        val endpoint = URI.create("http://127.0.0.1:$port/json/list")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONNECTION_TIMEOUT_SECONDS)
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline && process.isAlive) {
            val socketUrl =
                runCatching {
                    val request = HttpRequest.newBuilder(endpoint).GET().timeout(Duration.ofSeconds(2)).build()
                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    JsonSlurper()
                        .parseText(response.body())
                        .listOfMaps()
                        .firstOrNull { target -> target["type"] == "page" }
                        ?.get("webSocketDebuggerUrl")
                        ?.toString()
                }.onFailure { error -> lastFailure = error }
                    .getOrNull()
            if (!socketUrl.isNullOrBlank()) return socketUrl
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw failure("Chrome did not expose a page DevTools socket", lastFailure)
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.stringMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

private fun Map<String, Any?>.stringMap(key: String): Map<String, Any?> = get(key).stringMap()

private fun Map<String, Any?>.number(key: String): Double =
    (get(key) as? Number)?.toDouble() ?: error("Missing numeric CDP field $key")

private fun Map<String, Any?>.long(key: String): Long = number(key).roundToLong()

private fun Any?.listOfMaps(): List<Map<String, Any?>> =
    (this as? List<*>).orEmpty().map(Any?::stringMap)

private fun File.safeTail(): String =
    runCatching { readText().takeLast(8_000) }.getOrDefault("<unavailable>")
