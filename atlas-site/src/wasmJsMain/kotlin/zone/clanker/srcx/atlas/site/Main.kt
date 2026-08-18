package zone.clanker.srcx.atlas.site

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val root = document.getElementById("atlas-root") ?: return
    ComposeViewport(root) {
        AtlasHost()
    }
}
