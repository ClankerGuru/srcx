package zone.clanker.docx.web.fixture

internal fun buildColor(index: Int): String =
    "hsl(${BUILD_COLOR_BASE_HUE + index * BUILD_COLOR_HUE_STEP} 58% 66%)"

internal fun padded(
    value: Int,
    width: Int,
): String = value.toString().padStart(width, '0')

internal val SOURCE_SET_NAMES = listOf("main", "test")
internal const val BUILD_WIDTH = 3
internal const val PROJECT_WIDTH = 3
internal const val FILE_WIDTH = 3
internal const val SYMBOL_WIDTH = 4
internal const val LINE_WIDTH = 2
internal const val SYMBOL_STRUCTURE_LINE_COUNT = 4
internal const val DEPENDENCY_REFERENCE_LINE = 2
private const val BUILD_COLOR_BASE_HUE = 210
private const val BUILD_COLOR_HUE_STEP = 31
