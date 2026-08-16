package zone.clanker.gradle.srcx.report

internal fun String.escapeWorkspaceHtml(): String =
    buildString(length) {
        for (character in this@escapeWorkspaceHtml) {
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    '{' -> "&#123;"
                    '}' -> "&#125;"
                    else -> character
                },
            )
        }
    }

internal fun workspaceProjectDisplayName(
    buildName: String,
    projectPath: String,
): String =
    if (projectPath == ":") {
        "${buildName.uppercase()} · : (root project)"
    } else {
        projectPath
    }
