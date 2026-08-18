package zone.clanker.gradle.srcx.report

/** HTML mount for the Compose/Wasm Atlas. First paint is the map SVG, not an empty root. */
class AtlasComposeHostRenderer {
    fun document(
        workspaceName: String,
        firstPaint: String = "",
    ): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${workspaceName.escapeWorkspaceHtml()} SRCX atlas</title>
        <style>html,body,#atlas-root{margin:0;height:100%;background:#f4efe6;color:#2a241e}#atlas-first-paint{width:100%;height:100%;display:block}</style>
        </head>
        <body>
        <div id="atlas-root">$firstPaint</div>
        <script>
        (function(){var w=console.warn,e=console.error,i=console.info,l=console.log;function quiet(fn){return function(){var s=String(arguments[0]||"");if(/Clipboard|WebGL|GPU stall|insecure context/i.test(s))return;return fn.apply(console,arguments);};}console.warn=quiet(w);console.error=quiet(e);console.info=quiet(i);console.log=quiet(l);})();
        </script>
        <script src="atlas-host.js"></script>
        </body>
        </html>
        """.trimIndent() + "\n"
}
