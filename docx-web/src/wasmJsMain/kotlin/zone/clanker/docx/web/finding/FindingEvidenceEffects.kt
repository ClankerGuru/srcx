@file:Suppress("FunctionNaming")

package zone.clanker.docx.web.finding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.AtlasReportSection
import zone.clanker.docx.web.atlas.navigateToSection
import zone.clanker.docx.web.state.ViewerState

internal fun requestFindingEvidence(
    dashboardFindingId: String,
    state: ViewerState.Ready,
    controller: AtlasController,
    onStateChange: (ViewerState) -> Unit,
    onPendingChange: (PendingFindingEvidence?) -> Unit,
) {
    val item =
        state.site.dashboard.findings
            .singleOrNull { finding -> finding.id == dashboardFindingId }
    if (item == null) {
        controller.graph.findingEvidenceUnavailable(
            dashboardFindingId,
            "This finding is not present in the typed workspace dashboard.",
        )
        controller.navigateToSection(AtlasReportSection.ARCHITECTURE)
        return
    }
    val pending = PendingFindingEvidence(item.id, item.projectId, item.finding.id)
    controller.graph.beginFindingEvidence(item.finding.id)
    onPendingChange(pending)
    val evidence = state.evidence.request(item.projectId)
    if (evidence != state.evidence) {
        onStateChange(state.copy(evidence = evidence))
    }
}

@Composable
internal fun FindingEvidenceEffects(
    pending: PendingFindingEvidence?,
    state: ViewerState,
    controller: AtlasController,
    onPendingChange: (PendingFindingEvidence?) -> Unit,
) {
    val ready = state as? ViewerState.Ready
    LaunchedEffect(
        pending,
        ready?.evidence?.requestedProjectId,
        ready?.evidence?.project?.projectId,
        ready?.evidence?.loading,
        ready?.evidence?.error,
    ) {
        if (pending == null || ready == null) {
            return@LaunchedEffect
        }
        if (ready.evidence.requestedProjectId != pending.projectId || ready.evidence.loading) {
            return@LaunchedEffect
        }
        val project = ready.evidence.project
        if (project == null) {
            finishUnavailableEvidence(
                pending,
                controller,
                ready.evidence.error ?: "The owning project shard did not provide analyzer evidence.",
                onPendingChange,
            )
            return@LaunchedEffect
        }
        val item =
            ready.site.dashboard.findings
                .singleOrNull { finding -> finding.id == pending.dashboardFindingId }
        val target = item?.let { finding -> resolveFindingEvidenceTarget(finding, project) }
        if (target == null || target.findingId != pending.findingId) {
            finishUnavailableEvidence(
                pending,
                controller,
                "No file, symbol, or resolved component from this finding exists in the loaded project shard.",
                onPendingChange,
            )
            return@LaunchedEffect
        }
        controller.graph.focusFindingEvidence(target.findingId, target.nodeId)
        controller.inspectNode(target.nodeId)
        controller.navigateToSection(AtlasReportSection.ARCHITECTURE)
        onPendingChange(null)
    }
}

private fun finishUnavailableEvidence(
    pending: PendingFindingEvidence,
    controller: AtlasController,
    message: String,
    onPendingChange: (PendingFindingEvidence?) -> Unit,
) {
    controller.graph.findingEvidenceUnavailable(pending.findingId, message)
    controller.navigateToSection(AtlasReportSection.ARCHITECTURE)
    onPendingChange(null)
}
