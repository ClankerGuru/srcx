package zone.clanker.gradle.docx.site

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.docx.tempDirectory
import java.nio.file.Files

class DocxSourceContentWriterTest :
    BehaviorSpec({
        given("a known UTF-8 source body") {
            then("its content address is the lowercase SHA-256 digest") {
                sourceContentHash("abc".encodeToByteArray()) shouldBe
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            }
        }

        given("two projected files with the same exact source body") {
            val staging = tempDirectory("docx-source-content-").toPath()
            val content = "package fixture\nclass Shared\n"
            val references =
                DocxSourceContentWriter().write(
                    staging,
                    listOf(
                        ProjectedSourceContent("project:a", "file:a", content),
                        ProjectedSourceContent("project:b", "file:b", content),
                    ),
                )

            then("the content-addressed body is written once and referenced by both files") {
                val first = references.getValue("project:a").single()
                val second = references.getValue("project:b").single()
                first.file shouldBe second.file
                first.contentHash shouldBe second.contentHash
                Files.readString(staging.resolve(first.file)) shouldBe content
                Files.list(staging.resolve("data/sources")).use { paths ->
                    paths.map { path -> path.fileName.toString() }.toList()
                } shouldContainExactly listOf("${first.contentHash}.txt")
            }
        }
    })
