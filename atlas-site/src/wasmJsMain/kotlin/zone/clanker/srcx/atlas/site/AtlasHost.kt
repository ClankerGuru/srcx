package zone.clanker.srcx.atlas.site

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import zone.clanker.srcx.atlas.AtlasDrawSeed

@Composable
fun AtlasHost() {
    var seed by remember { mutableStateOf<AtlasDrawSeed?>(null) }
    LaunchedEffect(Unit) {
        seed = runCatching { AtlasSqliteFetch.readSeed() }.getOrNull()
    }
    AtlasWorkspace(seed)
}
