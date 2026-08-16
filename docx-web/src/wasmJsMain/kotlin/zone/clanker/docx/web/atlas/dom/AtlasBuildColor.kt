package zone.clanker.docx.web.atlas.dom

internal fun atlasBuildColor(name: String): String {
    val multiplied = name.hashCode().toLong() * BUILD_COLOR_HUE_STEP
    val hue = ((multiplied % BUILD_COLOR_HUE_COUNT) + BUILD_COLOR_HUE_COUNT) % BUILD_COLOR_HUE_COUNT
    return "hsl($hue 58% 66%)"
}

private const val BUILD_COLOR_HUE_COUNT = 360L
private const val BUILD_COLOR_HUE_STEP = 137L
