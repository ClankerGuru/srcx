package zone.clanker.srcx.atlas.site

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zone.clanker.srcx.atlas.AtlasDrawFileNode

@Composable
fun AtlasFileSource(
    node: AtlasDrawFileNode,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(AtlasPalette.paper)
                .padding(16.dp),
    ) {
        BasicText("FILE SOURCE", style = TextStyle(color = AtlasPalette.mute, fontSize = 11.sp))
        BasicText(
            text = node.path.ifBlank { node.name },
            style = TextStyle(color = AtlasPalette.ink, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        )
        BasicText(
            text = node.content.ifBlank { node.build + " / " + node.project + " / " + node.sourceSet },
            style =
                TextStyle(
                    color = AtlasPalette.ink,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                ),
        )
    }
}
