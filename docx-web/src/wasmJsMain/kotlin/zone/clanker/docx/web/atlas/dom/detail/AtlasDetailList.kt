package zone.clanker.docx.web.atlas.dom.detail

import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.htmlElement

internal fun detailList(vararg rows: Pair<String, String>): HTMLElement =
    htmlElement("dl", "srcx-dashboard__architecture-detail-list").also { list ->
        rows.forEach { (label, value) ->
            val row = htmlElement("div")
            row.appendChild(htmlElement("dt", text = label))
            row.appendChild(htmlElement("dd", text = value))
            list.appendChild(row)
        }
    }
