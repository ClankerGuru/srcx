package zone.clanker.docx.web.probe

import kotlinx.browser.document
import kotlinx.browser.window

internal data class DocxSmokeCommand(
    val revision: Int,
    val name: String,
    val argument: String,
)

internal class DocxSmokeCommandSource(
    private val rootId: String,
) {
    private var observedRevision = 0

    fun isComplete(): Boolean =
        !isBrowserProof() &&
            document
                .getElementById(rootId)
                ?.getAttribute(SMOKE_RESULT)
                .orEmpty() in terminalSmokeResults

    fun poll(): DocxSmokeCommand? {
        val root = document.getElementById(rootId)
        val revision = root?.getAttribute(COMMAND_REQUEST_REVISION)?.toIntOrNull()
        return if (root == null || revision == null || revision <= observedRevision) {
            null
        } else {
            observedRevision = revision
            DocxSmokeCommand(
                revision = revision,
                name = root.getAttribute(COMMAND_REQUEST_NAME).orEmpty(),
                argument = root.getAttribute(COMMAND_REQUEST_ARGUMENT).orEmpty(),
            )
        }
    }
}

private fun isBrowserProof(): Boolean =
    window.location.search
        .removePrefix("?")
        .split('&')
        .any { parameter -> parameter.startsWith("proof=") }

private const val COMMAND_REQUEST_REVISION = "data-docx-command-request-revision"
private const val COMMAND_REQUEST_NAME = "data-docx-command-request-name"
private const val COMMAND_REQUEST_ARGUMENT = "data-docx-command-request-argument"
private const val SMOKE_RESULT = "data-docx-smoke-result"
private val terminalSmokeResults = setOf("passed", "failed")
