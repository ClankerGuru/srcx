package zone.clanker.srcx.atlas.site

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import zone.clanker.srcx.atlas.AtlasDrawFileNode
import zone.clanker.srcx.atlas.AtlasDrawSeed

@Composable
fun AtlasWorkspace(seed: AtlasDrawSeed?) {
    var query by remember { mutableStateOf("") }
    var kinds by remember { mutableStateOf(setOf<String>()) }
    var usedAtLeast by remember { mutableIntStateOf(0) }
    var lens by remember { mutableStateOf("files") }
    var selected by remember { mutableStateOf<AtlasDrawFileNode?>(null) }
    var hovered by remember { mutableStateOf<AtlasDrawFileNode?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AtlasPalette.paper),
    ) {
        AtlasChromeBar(
            query = query,
            onQuery = { query = it },
            kinds = kinds,
            onToggleKind = { kind ->
                kinds = if (kind in kinds) kinds - kind else kinds + kind
            },
            usedAtLeast = usedAtLeast,
            onUsedAtLeast = { usedAtLeast = it.coerceAtLeast(0) },
            lens = lens,
            onLens = { lens = it },
            onZoomIn = { scale = (scale * 1.2f).coerceIn(0.4f, 8f) },
            onZoomOut = { scale = (scale / 1.2f).coerceIn(0.4f, 8f) },
            onFit = {
                scale = 1f
                pan = Offset.Zero
            },
            onReset = {
                scale = 1f
                pan = Offset.Zero
                selected = null
                query = ""
                kinds = emptySet()
                usedAtLeast = 0
            },
        )
        Row(modifier = Modifier.fillMaxSize()) {
            AtlasMapPane(
                seed = seed,
                query = query,
                kinds = kinds,
                usedAtLeast = usedAtLeast,
                lens = lens,
                selected = selected,
                hovered = hovered,
                scale = scale,
                pan = pan,
                onPan = { pan = it },
                onScale = { scale = it.coerceIn(0.4f, 8f) },
                onHover = { hovered = it },
                onSelect = { node, nextPan ->
                    selected = node
                    scale = 2.2f
                    pan = nextPan
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            selected?.let { node ->
                AtlasFileSource(
                    node = node,
                    modifier =
                        Modifier
                            .width(420.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
