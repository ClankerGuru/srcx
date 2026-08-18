package zone.clanker.srcx.atlas.site

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AtlasChromeBar(
    query: String,
    onQuery: (String) -> Unit,
    kinds: Set<String>,
    onToggleKind: (String) -> Unit,
    usedAtLeast: Int,
    onUsedAtLeast: (Int) -> Unit,
    lens: String,
    onLens: (String) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AtlasPalette.bar)
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf("files" to "FILES", "symbols" to "SYMBOLS", "problems" to "PROBLEMS", "cycles" to "CYCLES").forEach { (id, label) ->
                AtlasChip(label = label, on = lens == id, onClick = { onLens(id) })
            }
            BasicText("FIND", style = TextStyle(color = AtlasPalette.mute, fontSize = 11.sp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                textStyle = TextStyle(color = AtlasPalette.ink, fontSize = 13.sp),
                cursorBrush = SolidColor(AtlasPalette.ink),
                modifier =
                    Modifier
                        .weight(1f)
                        .border(1.dp, AtlasPalette.roomLine)
                        .background(AtlasPalette.paper)
                        .padding(6.dp),
                singleLine = true,
            )
            AtlasChip(label = "+", on = false, onClick = onZoomIn)
            AtlasChip(label = "−", on = false, onClick = onZoomOut)
            AtlasChip(label = "FIT", on = false, onClick = onFit)
            AtlasChip(label = "RESET", on = false, onClick = onReset)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            KIND_CHIPS.forEach { (id, label) ->
                AtlasChip(label = label, on = id in kinds, onClick = { onToggleKind(id) })
            }
            BasicText("USED AT LEAST", style = TextStyle(color = AtlasPalette.mute, fontSize = 11.sp))
            AtlasChip(label = "−", on = false, onClick = { onUsedAtLeast(usedAtLeast - 1) })
            BasicText(usedAtLeast.toString(), style = TextStyle(color = AtlasPalette.ink, fontSize = 13.sp))
            AtlasChip(label = "+", on = false, onClick = { onUsedAtLeast(usedAtLeast + 1) })
        }
    }
}

@Composable
private fun AtlasChip(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    BasicText(
        text = label,
        style =
            TextStyle(
                color = if (on) AtlasPalette.paper else AtlasPalette.ink,
                fontSize = 11.sp,
            ),
        modifier =
            Modifier
                .background(if (on) AtlasPalette.chipOn else AtlasPalette.chip)
                .border(1.dp, AtlasPalette.roomLine)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

private val KIND_CHIPS =
    listOf(
        "class" to "CLASS",
        "interface" to "INTERFACE",
        "fun-interface" to "FUNCTIONAL INTERFACE",
        "object" to "OBJECT",
        "enum" to "ENUM",
        "sealed-class" to "SEALED CLASS",
        "function" to "FUNCTION",
        "property" to "PROPERTY",
        "variable" to "VARIABLE",
        "file" to "FILE",
        "kotlin" to "KOTLIN",
        "java" to "JAVA",
        "gradle-kts" to "GRADLE KTS",
    )
