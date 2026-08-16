package zone.clanker.docx.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class AtlasStatusTone {
    LOADING,
    ERROR,
}

@Composable
internal fun atlasStatusSurface(
    title: String,
    detail: String,
    tone: AtlasStatusTone,
) {
    val accent = if (tone == AtlasStatusTone.LOADING) STATUS_LOADING else STATUS_ERROR
    Box(
        modifier = Modifier.fillMaxSize().background(STATUS_PAPER).padding(STATUS_OUTER_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(STATUS_WIDTH_FRACTION)
                    .background(STATUS_SURFACE, RoundedCornerShape(STATUS_RADIUS))
                    .border(STATUS_BORDER_WIDTH, STATUS_INK, RoundedCornerShape(STATUS_RADIUS))
                    .padding(STATUS_INNER_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(STATUS_GAP),
        ) {
            BasicText(
                text = "WORKSPACE STATE",
                style = statusTextStyle(STATUS_LABEL_SIZE, accent, FontWeight.Bold, FontFamily.Monospace),
            )
            BasicText(
                text = title,
                style = statusTextStyle(STATUS_TITLE_SIZE, STATUS_INK, FontWeight.Black),
            )
            BasicText(
                text = detail,
                style = statusTextStyle(STATUS_DETAIL_SIZE, STATUS_MUTED, FontWeight.Normal),
            )
        }
    }
}

private fun statusTextStyle(
    size: Int,
    color: Color,
    weight: FontWeight,
    family: FontFamily = FontFamily.SansSerif,
): TextStyle =
    TextStyle(
        color = color,
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size + STATUS_LINE_HEIGHT_PADDING).sp,
    )

private val STATUS_PAPER = Color(0xFFF3EFE4)
private val STATUS_SURFACE = Color(0xFFFBF7EC)
private val STATUS_INK = Color(0xFF1A1A1A)
private val STATUS_MUTED = Color(0xFF68645E)
private val STATUS_LOADING = Color(0xFF4CC9F0)
private val STATUS_ERROR = Color(0xFFFF3333)
private val STATUS_OUTER_PADDING = 28.dp
private val STATUS_INNER_PADDING = 22.dp
private val STATUS_RADIUS = 6.dp
private val STATUS_BORDER_WIDTH = 2.dp
private val STATUS_GAP = 8.dp
private const val STATUS_WIDTH_FRACTION = 0.68f
private const val STATUS_LABEL_SIZE = 9
private const val STATUS_TITLE_SIZE = 22
private const val STATUS_DETAIL_SIZE = 12
private const val STATUS_LINE_HEIGHT_PADDING = 4
