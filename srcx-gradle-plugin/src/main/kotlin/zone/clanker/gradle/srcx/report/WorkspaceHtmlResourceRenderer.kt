package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.Srcx

/** Loads and resolves the bundled Gort presentation resources. */
internal class WorkspaceHtmlResourceRenderer {
    private val resources: Map<String, String> =
        RESOURCE_NAMES.associateWith { resourceName -> readClasspathResource(resourceName) }
    private val d3Script = readAbsoluteClasspathResource(D3_WEBJAR_RESOURCE_PATH)

    fun dashboard(slots: Map<String, String>): String =
        renderTemplate(resources.getValue(DASHBOARD), slots, DASHBOARD)

    fun component(
        name: String,
        slots: Map<String, String>,
    ): String {
        val resourceName = "components/$name.html"
        val template = requireNotNull(resources[resourceName]) { "Unknown HTML component: $name" }
        return renderTemplate(template, slots, resourceName)
    }

    fun styles(): String =
        buildString {
            append(resources.getValue(THEME).trimEnd())
            appendLine()
            appendLine()
            append(resources.getValue(DASHBOARD_STYLES).trimEnd())
            appendLine()
            appendLine()
            append(resources.getValue(ATLAS_MAP_STYLES).trimEnd())
        }

    fun d3Vendor(): String = d3Script

    fun drawAdapter(): String = resources.getValue(ATLAS_DRAW_SCRIPT)

    fun scripts(): String =
        buildString {
            appendLine(
                "<script src=\"${Srcx.HTML_D3_FILE}\" data-srcx-vendor=\"d3-7.9.0\"></script>",
            )
            appendLine(
                "<script src=\"${Srcx.HTML_DRAW_FILE}\" data-srcx-owned=\"atlas-draw\"></script>",
            )
            appendLine("<script src=\"${Srcx.HTML_SEED_WASM_BYTES_FILE}\"></script>")
            appendLine("<script src=\"${Srcx.HTML_SQLITE_BYTES_FILE}\"></script>")
            appendLine("<script src=\"${Srcx.HTML_SEED_LOADER_FILE}\"></script>")
        }

    internal fun renderTemplate(
        template: String,
        slots: Map<String, String>,
        templateName: String = "inline template",
    ): String {
        val slotNames =
            SLOT_PATTERN
                .findAll(template)
                .map { match -> match.groupValues[1] }
                .toSortedSet()
        val unresolved = slotNames.filterNot(slots::containsKey)
        require(unresolved.isEmpty()) {
            "Unresolved HTML template slots in $templateName: ${unresolved.joinToString()}"
        }
        return slotNames.fold(template) { rendered, slot ->
            rendered.replace("{{$slot}}", slots.getValue(slot))
        }
    }

    internal companion object {
        const val D3_WEBJAR_RESOURCE_PATH =
            "/META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js"

        const val DASHBOARD = "dashboard.html"
        const val DASHBOARD_STYLES = "dashboard.css"
        const val ATLAS_MAP_STYLES = "atlas-map.css"
        const val THEME = "theme.css"
        const val ARCHITECTURE_GRAPH_SCRIPT = "architecture-graph.js"
        const val ATLAS_DRAW_SCRIPT = "atlas-draw.js"
        const val RESOURCE_ROOT = "/zone/clanker/gradle/srcx/report/html/"

        val SLOT_PATTERN = Regex("""\{\{([a-zA-Z][a-zA-Z0-9]*)}}""")
        val RESOURCE_NAMES =
            listOf(
                THEME,
                DASHBOARD_STYLES,
                ATLAS_MAP_STYLES,
                DASHBOARD,
                ARCHITECTURE_GRAPH_SCRIPT,
                ATLAS_DRAW_SCRIPT,
                "components/disclosure.html",
                "components/empty-state.html",
                "components/masthead.html",
                "components/metric-strip.html",
                "components/metric.html",
                "components/section-heading.html",
                "components/source-note.html",
            )

        fun readClasspathResource(resourceName: String): String =
            readAbsoluteClasspathResource(RESOURCE_ROOT + resourceName, resourceName)

        fun readAbsoluteClasspathResource(
            resourcePath: String,
            displayName: String = resourcePath,
        ): String {
            val stream =
                requireNotNull(WorkspaceHtmlResourceRenderer::class.java.getResourceAsStream(resourcePath)) {
                    "Missing HTML resource: $displayName"
                }
            return stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        }
    }
}

private val CLOSING_SCRIPT_SEQUENCE = Regex("</script", RegexOption.IGNORE_CASE)

internal fun String.escapeClosingScriptSequence(): String = replace(CLOSING_SCRIPT_SEQUENCE) { "<\\/script" }
