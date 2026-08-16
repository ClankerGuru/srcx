package zone.clanker.docx.web.atlas.dom.detail

import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.report.model.ProjectGraphShard

internal fun appendFindingEvidenceDetail(
    fields: HTMLElement,
    project: ProjectGraphShard,
    findingId: String,
) {
    val finding =
        (project.findings + project.analysis?.findings.orEmpty())
            .distinctBy { candidate -> candidate.id }
            .firstOrNull { candidate -> candidate.id == findingId }
    val section = htmlElement("section", "srcx-dashboard__architecture-detail-section")
    section.appendChild(htmlElement("h4", text = "Analyzer finding"))
    if (finding == null) {
        section.appendChild(htmlElement("p", text = "The finding is not present in the loaded project shard."))
    } else {
        val list = htmlElement("dl", "srcx-dashboard__architecture-detail-list")
        list.appendDetailRow("Severity", finding.severity.label)
        list.appendDetailRow("Finding", finding.message)
        list.appendDetailRow("Suggestion", finding.suggestion)
        finding.filePath?.let { path ->
            val location = finding.line?.let { line -> "$path:$line" } ?: path
            list.appendDetailRow("Source", location)
        }
        section.appendChild(list)
    }
    fields.appendChild(section)
}

private fun HTMLElement.appendDetailRow(
    label: String,
    value: String,
) {
    appendChild(
        htmlElement("div").also { row ->
            row.appendChild(htmlElement("dt", text = label))
            row.appendChild(htmlElement("dd", text = value))
        },
    )
}
