@file:OptIn(ExperimentalWasmJsInterop::class)

package zone.clanker.srcx.atlas.site

import zone.clanker.srcx.atlas.AtlasDrawEdge
import zone.clanker.srcx.atlas.AtlasDrawFileNode
import zone.clanker.srcx.atlas.AtlasDrawSeed
import zone.clanker.srcx.atlas.AtlasDrawSymbolNode

/** Builds a real JS object for srcxAtlasDraw. Does not stringify or JSON.parse. */
internal fun AtlasDrawSeed.toJsObject(): JsAny {
    val fileNodes = jsArray()
    this.fileNodes.forEach { node -> jsPush(fileNodes, node.toJsObject()) }
    val fileEdges = jsArray()
    this.fileEdges.forEach { edge -> jsPush(fileEdges, edge.toJsObject()) }
    val symbols = jsArray()
    nodes.forEach { node -> jsPush(symbols, node.toJsObject()) }
    val builds = jsArray()
    this.builds.forEach { name ->
        val build = jsObject()
        jsSet(build, "name", jsStr(name))
        jsPush(builds, build)
    }
    val empty = jsArray()
    val root = jsObject()
    jsSet(root, "workspace", jsStr(workspace))
    jsSet(root, "defaultView", jsStr("files"))
    jsSet(root, "nodeLimit", jsNum(nodeLimit.toDouble()))
    jsSet(root, "fileNodeLimit", jsNum(nodeLimit.toDouble()))
    jsSet(root, "fileNodes", fileNodes)
    jsSet(root, "fileEdges", fileEdges)
    jsSet(root, "nodes", symbols)
    jsSet(root, "edges", jsArray())
    jsSet(root, "builds", builds)
    jsSet(root, "sourceFiles", fileNodes)
    jsSet(root, "availableNodes", empty)
    jsSet(root, "availableEdges", empty)
    jsSet(root, "availableFileNodes", empty)
    jsSet(root, "availableFileEdges", empty)
    return root
}

private fun AtlasDrawFileNode.toJsObject(): JsAny {
    val symbolsJs = jsArray()
    symbols.forEach { symbol ->
        val item = jsObject()
        jsSet(item, "id", jsStr(symbol.id))
        jsSet(item, "name", jsStr(symbol.name))
        jsSet(item, "kind", jsStr(symbol.kind))
        jsSet(item, "declarationSemantic", jsStr(symbol.declarationSemantic))
        jsSet(item, "line", jsNum(symbol.line.toDouble()))
        jsPush(symbolsJs, item)
    }
    val node = jsObject()
    jsSet(node, "id", jsStr(id))
    jsSet(node, "name", jsStr(name))
    jsSet(node, "path", jsStr(path))
    jsSet(node, "build", jsStr(build))
    jsSet(node, "project", jsStr(project))
    jsSet(node, "sourceSet", jsStr(sourceSet))
    jsSet(node, "symbols", symbolsJs)
    jsSet(node, "important", jsBool(important))
    jsSet(node, "relationshipRecordCount", jsNum(relationshipRecordCount.toDouble()))
    jsSet(node, "content", jsStr(content))
    return node
}

private fun AtlasDrawSymbolNode.toJsObject(): JsAny {
    val node = jsObject()
    jsSet(node, "id", jsStr(id))
    jsSet(node, "name", jsStr(name))
    jsSet(node, "build", jsStr(build))
    jsSet(node, "project", jsStr(project))
    jsSet(node, "sourceSet", jsStr(sourceSet))
    jsSet(node, "file", jsStr(file))
    jsSet(node, "line", jsNum(line.toDouble()))
    jsSet(node, "kind", jsStr(kind))
    return node
}

private fun AtlasDrawEdge.toJsObject(): JsAny {
    val edge = jsObject()
    jsSet(edge, "id", jsStr(id))
    jsSet(edge, "source", jsStr(source))
    jsSet(edge, "target", jsStr(target))
    jsSet(edge, "kind", jsStr(kind))
    jsSet(edge, "recordCount", jsNum(recordCount.toDouble()))
    return edge
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => ({})")
private external fun jsObject(): JsAny

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => []")
private external fun jsArray(): JsAny

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(o, k, v) => { o[k] = v }")
private external fun jsSet(
    o: JsAny,
    k: String,
    v: JsAny?,
)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(a, v) => { a.push(v) }")
private external fun jsPush(
    a: JsAny,
    v: JsAny?,
)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(s) => s")
private external fun jsStr(s: String): JsAny

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(n) => n")
private external fun jsNum(n: Double): JsAny

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(b) => b")
private external fun jsBool(b: Boolean): JsAny
