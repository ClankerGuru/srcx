package zone.clanker.docx.web.atlas.dom

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTemplateElement
import kotlin.js.ExperimentalWasmJsInterop

internal fun cloneAtlasTemplate(): HTMLDivElement {
    val template =
        document.getElementById(ATLAS_TEMPLATE_ID) as? HTMLTemplateElement
            ?: error("DOCX Atlas template is missing")
    val templateRoot = template.content.firstElementChild ?: error("DOCX Atlas template is empty")
    return templateRoot.cloneNode(deep = true) as? HTMLDivElement
        ?: error("DOCX Atlas template root must be a div")
}

internal fun createAtlasInteropHost(): HTMLDivElement {
    val host = document.createElement("div") as HTMLDivElement
    host.className = "docx-atlas-interop-host"
    host.style.setProperty("overflow", "hidden")
    host.style.setProperty("position", "relative")
    val surface = cloneAtlasTemplate()
    surface.style.setProperty("visibility", "hidden")
    surface.style.setProperty("pointer-events", "none")
    surface.setAttribute(STYLES_READY_ATTRIBUTE, "false")
    val loading = atlasStyleLoadingSurface()
    host.appendChild(surface)
    host.appendChild(loading)
    revealAtlasAfterStylesLoad(host, surface, loading)
    return host
}

private fun atlasStyleLoadingSurface(): HTMLDivElement =
    (document.createElement("div") as HTMLDivElement).also { loading ->
        loading.setAttribute("role", "status")
        loading.setAttribute("aria-live", "polite")
        loading.setAttribute("data-docx-atlas-style-loading", "true")
        loading.textContent = "Loading workspace atlas…"
        loading.style.setProperty("position", "absolute")
        loading.style.setProperty("inset", "0")
        loading.style.setProperty("display", "grid")
        loading.style.setProperty("place-items", "center")
        loading.style.setProperty("background", "#f6f1e6")
        loading.style.setProperty("color", "#181818")
        loading.style.setProperty("font", "800 12px/1.4 monospace")
        loading.style.setProperty("text-transform", "uppercase")
    }

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun revealAtlasAfterStylesLoad(
    host: HTMLDivElement,
    surface: HTMLDivElement,
    loading: HTMLDivElement,
): Unit =
    js(
        """{
            const begin = () => {
                if (!host.isConnected) { requestAnimationFrame(begin); return; }
                const links = Array.from(surface.querySelectorAll('link[rel="stylesheet"]'));
                const fail = (error) => {
                    host.setAttribute("data-docx-atlas-styles-ready", "error"); loading.textContent = "Workspace Atlas styles failed to load.";
                    loading.setAttribute("data-docx-atlas-style-error", String(error?.message || error || "unknown"));
                };
                const hydrate = async () => {
                    const styles = await Promise.all(links.map(async (link) => {
                        const response = await fetch(link.href, { credentials: "same-origin" });
                        if (!response.ok) throw new Error("Could not load Atlas stylesheet: " + response.status);
                        const style = document.createElement("style");
                        style.setAttribute("data-docx-atlas-inline-stylesheet", link.dataset.docxAtlasStylesheet || "atlas");
                        style.textContent = await response.text();
                        link.replaceWith(style); return style;
                    }));
                    let stableRoot = null, stableFrames = 0, disconnectedFrames = 0;
                    let visible = false;
                    const conceal = () => {
                        surface.style.setProperty("visibility", "hidden"); surface.style.setProperty("pointer-events", "none");
                        surface.setAttribute("data-docx-atlas-styles-ready", "false");
                        host.setAttribute("data-docx-atlas-styles-ready", "false");
                        loading.style.setProperty("display", "grid"); visible = false;
                    };
                    const show = (activeStyleCount) => {
                        surface.style.removeProperty("visibility"); surface.style.removeProperty("pointer-events");
                        surface.setAttribute("data-docx-atlas-styles-ready", "true");
                        surface.setAttribute("data-docx-atlas-active-styles", String(activeStyleCount));
                        host.setAttribute("data-docx-atlas-styles-ready", "true");
                        host.setAttribute("data-docx-atlas-active-styles", String(activeStyleCount));
                        loading.style.setProperty("display", "none"); visible = true;
                    };
                    const waitForRegistration = () => {
                        if (!host.isConnected) {
                            disconnectedFrames += 1;
                            if (disconnectedFrames >= 120) return;
                            requestAnimationFrame(waitForRegistration); return;
                        }
                        disconnectedFrames = 0;
                        const currentRoot = surface.getRootNode();
                        const rootSheets = Array.from(currentRoot?.styleSheets || []);
                        const activeStyleCount = styles.filter((style) => rootSheets.includes(style.sheet)).length;
                        if (currentRoot === stableRoot && activeStyleCount === styles.length) stableFrames += 1;
                        else {
                            if (visible) conceal();
                            stableRoot = currentRoot; stableFrames = activeStyleCount === styles.length ? 1 : 0;
                        }
                        if (stableFrames < 3) { requestAnimationFrame(waitForRegistration); return; }
                        if (!visible) { const requestedScrollTop = Number(surface.getAttribute("data-docx-atlas-scroll-restore-request")); if (Number.isFinite(requestedScrollTop)) surface.scrollTop = requestedScrollTop; show(activeStyleCount); }
                        requestAnimationFrame(waitForRegistration);
                    };
                    requestAnimationFrame(waitForRegistration); };
                hydrate().catch(fail);
            };
            requestAnimationFrame(begin);
        }""",
    )

internal fun Element.requiredElement(selector: String): Element =
    querySelector(selector) ?: error("DOCX Atlas template is missing $selector")

internal fun Element.requiredHtmlElement(selector: String): HTMLElement =
    requiredElement(selector) as? HTMLElement
        ?: error("DOCX Atlas template hook is not an HTML element: $selector")

internal fun Element.requiredInput(selector: String): HTMLInputElement =
    requiredElement(selector) as? HTMLInputElement
        ?: error("DOCX Atlas template hook is not an input: $selector")

internal fun Element.requiredButton(selector: String): HTMLButtonElement =
    requiredElement(selector) as? HTMLButtonElement
        ?: error("DOCX Atlas template hook is not a button: $selector")

internal fun htmlElement(
    tag: String,
    className: String? = null,
    text: String? = null,
): HTMLElement =
    (document.createElement(tag) as HTMLElement).also { element ->
        className?.let { element.className = it }
        text?.let { element.textContent = it }
    }

internal fun buttonElement(
    label: String,
    pressed: Boolean,
    onClick: () -> Unit,
): HTMLButtonElement =
    (document.createElement("button") as HTMLButtonElement).also { button ->
        button.type = "button"
        button.textContent = label
        button.setAttribute("aria-pressed", pressed.toString())
        button.onclick = {
            onClick()
            null
        }
    }

internal fun HTMLElement.clearContent() {
    textContent = ""
}

internal const val ATLAS_TEMPLATE_ID = "docx-atlas-template"
private const val STYLES_READY_ATTRIBUTE = "data-docx-atlas-styles-ready"
