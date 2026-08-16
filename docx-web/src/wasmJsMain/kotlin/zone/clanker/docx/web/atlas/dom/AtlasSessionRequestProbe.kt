package zone.clanker.docx.web.atlas.dom

import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.AtlasSessionRequestProbe
import zone.clanker.docx.web.atlas.openSemanticNodeWithProbe
import zone.clanker.docx.web.atlas.sessionRequestProbe

internal fun HTMLElement.openCanonicalNode(
    controller: AtlasController,
    nodeId: String,
) {
    val probe = controller.openSemanticNodeWithProbe(nodeId)
    setAttribute(OPEN_SESSION_KNOWN_ATTRIBUTE, probe.hierarchyKnown.toString())
    setAttribute(OPEN_SESSION_EXPANDED_ATTRIBUTE, probe.expanded.toString())
    publishSessionRequestState(probe.request)
}

internal fun HTMLElement.publishSessionRequestState(controller: AtlasController) {
    publishSessionRequestState(controller.sessionRequestProbe())
}

private fun HTMLElement.publishSessionRequestState(probe: AtlasSessionRequestProbe) {
    setAttribute(SESSION_REQUEST_STATE_ATTRIBUTE, probe.status)
    setAttribute(SESSION_REQUEST_REVISION_ATTRIBUTE, probe.revision)
    setAttribute(SESSION_REQUEST_EXPANDED_ATTRIBUTE, probe.requestExpandedNodeIds)
    setAttribute(SESSION_FOCUS_EXPANDED_ATTRIBUTE, probe.focusExpandedNodeIds)
    setOptionalAttribute(SESSION_REQUEST_ERROR_ATTRIBUTE, probe.error)
}

private fun HTMLElement.setOptionalAttribute(
    name: String,
    value: String?,
) {
    if (value == null) removeAttribute(name) else setAttribute(name, value)
}

private const val OPEN_SESSION_KNOWN_ATTRIBUTE = "data-docx-atlas-open-session-known"
private const val OPEN_SESSION_EXPANDED_ATTRIBUTE = "data-docx-atlas-open-session-expanded"
private const val SESSION_REQUEST_STATE_ATTRIBUTE = "data-docx-atlas-session-request-state"
private const val SESSION_REQUEST_REVISION_ATTRIBUTE = "data-docx-atlas-session-request-revision"
private const val SESSION_REQUEST_ERROR_ATTRIBUTE = "data-docx-atlas-session-request-error"
private const val SESSION_REQUEST_EXPANDED_ATTRIBUTE = "data-docx-atlas-session-request-expanded-node-ids"
private const val SESSION_FOCUS_EXPANDED_ATTRIBUTE = "data-docx-atlas-session-focus-expanded-node-ids"
