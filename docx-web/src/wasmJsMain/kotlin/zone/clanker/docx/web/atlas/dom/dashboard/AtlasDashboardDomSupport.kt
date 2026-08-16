package zone.clanker.docx.web.atlas.dom.dashboard

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.report.model.FindingSeverity
import kotlin.math.roundToInt

internal fun dashboardPercentage(
    count: Int,
    maximum: Int,
): String = "${dashboardPercentageNumber(count, maximum)}%"

internal fun dashboardPercentageNumber(
    count: Int,
    maximum: Int,
): String =
    if (maximum == 0) {
        "0.0"
    } else {
        (
            (count.toDouble() / maximum.toDouble() * PERCENT_ROUNDING_MULTIPLIER).roundToInt() /
                PERCENT_DECIMAL_DIVISOR
        ).toString()
    }

internal fun Int.dashboardPlural(noun: String): String = if (this == 1) noun else "${noun}s"

internal fun String.dashboardRootProjectLabel(): String = if (this == ":") ": (root project)" else this

internal fun dashboardEmptyState(
    title: String,
    detail: String,
): HTMLElement =
    htmlElement("div", "srcx-empty-state").also { state ->
        state.appendChild(htmlElement("strong", text = title))
        state.appendChild(htmlElement("span", text = detail))
    }

internal fun dashboardStrongParagraph(
    label: String,
    value: String,
): HTMLElement =
    htmlElement("p").also { paragraph ->
        paragraph.appendChild(htmlElement("strong", text = label))
        paragraph.appendChild(document.createTextNode(" $value"))
    }

internal fun dashboardStrongCodeParagraph(
    label: String,
    value: String,
): HTMLElement =
    htmlElement("p").also { paragraph ->
        paragraph.appendChild(htmlElement("strong", text = label))
        paragraph.appendChild(document.createTextNode(" "))
        paragraph.appendChild(htmlElement("code", text = value))
    }

internal fun dashboardSourceLocation(
    filePath: String?,
    line: Int?,
): String = filePath?.let { path -> path + (line?.let { ":$it" } ?: "") } ?: "Unresolved"

internal fun HTMLElement.renderDashboardFacts(facts: List<Pair<String, Int>>) {
    clearContent()
    facts.forEach { (label, count) ->
        appendChild(
            htmlElement("div").also { item ->
                item.appendChild(htmlElement("dt", text = label))
                item.appendChild(htmlElement("dd", text = count.toString()))
            },
        )
    }
}

internal val FindingSeverity.dashboardToneClass: String
    get() =
        when (this) {
            FindingSeverity.FORBIDDEN -> "srcx-tone--error"
            FindingSeverity.WARNING -> "srcx-tone--warning"
            FindingSeverity.INFO -> "srcx-tone--accent"
        }

internal fun KeyboardEvent.dashboardRovingIndex(
    current: Int,
    size: Int,
): Int? =
    when (key) {
        "ArrowRight", "ArrowDown" -> (current + 1) % size
        "ArrowLeft", "ArrowUp" -> (current - 1 + size) % size
        "Home" -> 0
        "End" -> size - 1
        else -> null
    }

internal fun dashboardScrollToArchitecture(source: HTMLElement) {
    source
        .closest("[data-docx-atlas-surface]")
        ?.querySelector("#architecture")
        ?.scrollIntoView()
}

private const val PERCENT_ROUNDING_MULTIPLIER = 1_000.0
private const val PERCENT_DECIMAL_DIVISOR = 10.0
