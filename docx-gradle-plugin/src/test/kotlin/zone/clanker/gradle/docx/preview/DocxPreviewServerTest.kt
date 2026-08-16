package zone.clanker.gradle.docx.preview

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.docx.tempDirectory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class DocxPreviewServerTest :
    BehaviorSpec({
        given("a task-scoped static DOCX preview") {
            val site = tempDirectory("docx-preview-")
            site.resolve("index.html").writeText("<h1>DOCX</h1>")
            site.resolve("assets").mkdirs()
            site.resolve("assets/viewer.wasm").writeBytes(byteArrayOf(0, 97, 115, 109))
            site.resolve("nested").mkdirs()
            site.resolve("nested/index.html").writeText("nested")
            mapOf(
                "styles.css" to "text/css; charset=utf-8",
                "viewer.mjs" to "text/javascript; charset=utf-8",
                "data.json" to "application/json; charset=utf-8",
                "icon.svg" to "image/svg+xml",
                "image.png" to "image/png",
                "photo.jpg" to "image/jpeg",
                "photo.webp" to "image/webp",
                "font.woff" to "font/woff",
                "font.woff2" to "font/woff2",
                "unknown.bin" to "application/octet-stream",
            ).keys.forEach { name -> site.resolve("assets/$name").writeText(name) }

            `when`("the loopback server handles static requests") {
                DocxPreviewServer.start(site.toPath()).use { server ->
                    val client = HttpClient.newHttpClient()
                    val index = client.send(request(server.url, "/"), HttpResponse.BodyHandlers.ofString())
                    val wasm =
                        client.send(
                            request(server.url, "/assets/viewer.wasm"),
                            HttpResponse.BodyHandlers.ofByteArray(),
                        )
                    val missing = client.send(request(server.url, "/missing"), HttpResponse.BodyHandlers.ofString())
                    val traversal =
                        client.send(request(server.url, "/%2e%2e/secret"), HttpResponse.BodyHandlers.ofString())
                    val encodedSeparator =
                        client.send(request(server.url, "/assets%2fviewer.wasm"), HttpResponse.BodyHandlers.ofString())
                    val nested = client.send(request(server.url, "/nested/"), HttpResponse.BodyHandlers.ofString())
                    val head =
                        client.send(
                            HttpRequest
                                .newBuilder(server.url.resolve("/assets/viewer.wasm"))
                                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                .build(),
                            HttpResponse.BodyHandlers.ofByteArray(),
                        )
                    val post =
                        client.send(
                            HttpRequest
                                .newBuilder(server.url.resolve("/"))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )

                    then("it binds to an operating-system-assigned loopback port") {
                        server.url.host shouldBe "127.0.0.1"
                        (server.url.port > 0) shouldBe true
                    }

                    then("it serves HTML and Wasm with explicit media types") {
                        index.statusCode() shouldBe 200
                        index.body() shouldBe "<h1>DOCX</h1>"
                        index.headers().firstValue("content-type").orElse("") shouldBe "text/html; charset=utf-8"
                        wasm.statusCode() shouldBe 200
                        wasm.headers().firstValue("content-type").orElse("") shouldBe "application/wasm"
                        nested.body() shouldBe "nested"
                        head.statusCode() shouldBe 200
                        head.body().size shouldBe 0
                    }

                    then("it rejects missing, traversing, and unsupported requests") {
                        missing.statusCode() shouldBe 404
                        traversal.statusCode() shouldBe 403
                        encodedSeparator.statusCode() shouldBe 403
                        post.statusCode() shouldBe 405
                    }

                    then("it assigns explicit content types to every supported static asset") {
                        mapOf(
                            "styles.css" to "text/css; charset=utf-8",
                            "viewer.mjs" to "text/javascript; charset=utf-8",
                            "data.json" to "application/json; charset=utf-8",
                            "icon.svg" to "image/svg+xml",
                            "image.png" to "image/png",
                            "photo.jpg" to "image/jpeg",
                            "photo.webp" to "image/webp",
                            "font.woff" to "font/woff",
                            "font.woff2" to "font/woff2",
                            "unknown.bin" to "application/octet-stream",
                        ).forEach { (name, mediaType) ->
                            val response =
                                client.send(
                                    request(server.url, "/assets/$name"),
                                    HttpResponse.BodyHandlers.ofString(),
                                )
                            response.headers().firstValue("content-type").orElse("") shouldBe mediaType
                        }
                    }
                }
            }
        }

        given("a directory without a generated site") {
            `when`("preview startup is requested") {
                then("the missing index is rejected before binding") {
                    shouldThrow<IllegalArgumentException> {
                        DocxPreviewServer.start(tempDirectory("docx-preview-missing-").toPath())
                    }.message shouldContain "index.html"
                }
            }
        }
    })

private fun request(
    base: URI,
    path: String,
): HttpRequest = HttpRequest.newBuilder(base.resolve(path)).GET().build()
