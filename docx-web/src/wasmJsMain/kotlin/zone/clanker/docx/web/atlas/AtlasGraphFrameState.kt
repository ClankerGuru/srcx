package zone.clanker.docx.web.atlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal interface AtlasGraphFrameState {
    val totalNodeCount: Int
    val visibleNodeIds: List<String>
    val pageIndex: Int
    val pageCount: Int
    val requestedFrameRevision: Int
    val bridgeEnabled: Boolean
    val frameIsSettled: Boolean

    fun updateBridgeEnabled(enabled: Boolean)

    fun updateFrame(
        revision: Int,
        totalNodes: Int,
        visibleNodes: List<String>,
        pages: Int,
        actualPage: Int,
    )
}

internal class AtlasGraphFrameController : AtlasGraphFrameState {
    override var totalNodeCount by mutableStateOf(0)
        private set

    override var visibleNodeIds by mutableStateOf<List<String>>(emptyList())
        private set

    override var pageIndex by mutableStateOf(0)
        private set

    override var pageCount by mutableStateOf(0)
        private set

    override var requestedFrameRevision by mutableStateOf(0)
        private set

    override var bridgeEnabled by mutableStateOf(true)
        private set

    private var appliedFrameRevision by mutableStateOf(-1)

    override val frameIsSettled: Boolean
        get() = appliedFrameRevision == requestedFrameRevision

    override fun updateBridgeEnabled(enabled: Boolean) {
        bridgeEnabled = enabled
    }

    override fun updateFrame(
        revision: Int,
        totalNodes: Int,
        visibleNodes: List<String>,
        pages: Int,
        actualPage: Int,
    ) {
        if (revision != requestedFrameRevision) return
        totalNodeCount = totalNodes
        visibleNodeIds = visibleNodes
        pageCount = pages
        pageIndex = actualPage
        appliedFrameRevision = revision
    }

    fun resetFrame() {
        pageIndex = 0
        invalidateFrame()
    }

    fun invalidateScope() {
        pageIndex = 0
        totalNodeCount = 0
        visibleNodeIds = emptyList()
        pageCount = 0
        invalidateFrame()
    }

    fun invalidateFocus() {
        pageIndex = 0
        invalidateFrame()
    }

    private fun invalidateFrame() {
        requestedFrameRevision += 1
    }
}
