package zone.clanker.gradle.srcx.report

/** Small Compose/Wasm mount. The Atlas is drawn in Compose, not in this HTML. */
class AtlasComposeHostRenderer {
    fun document(workspaceName: String): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${workspaceName.escapeWorkspaceHtml()} SRCX atlas</title>
        <style>html,body,#atlas-root{margin:0;height:100%;background:#f4efe6;color:#2a241e}</style>
        </head>
        <body>
        <div id="atlas-root"></div>
        <script src="atlas-host.js"></script>
        </body>
        </html>
        """.trimIndent() + "\n"
}
