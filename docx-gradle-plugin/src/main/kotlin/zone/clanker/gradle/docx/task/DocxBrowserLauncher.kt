package zone.clanker.gradle.docx.task

import java.net.URI

/** Builds a headless operating-system command for opening the preview URL. */
internal object DocxBrowserLauncher {
    fun command(
        osName: String,
        url: URI,
    ): List<String> =
        when {
            osName.startsWith("Mac", ignoreCase = true) -> listOf("/usr/bin/open", url.toASCIIString())
            osName.startsWith("Windows", ignoreCase = true) ->
                listOf("rundll32", "url.dll,FileProtocolHandler", url.toASCIIString())
            else -> listOf("xdg-open", url.toASCIIString())
        }
}
