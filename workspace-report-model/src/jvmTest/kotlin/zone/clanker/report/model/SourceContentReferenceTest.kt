package zone.clanker.report.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SourceContentReferenceTest :
    BehaviorSpec({
        given("a content-addressed source-body reference") {
            val hash = "a".repeat(64)

            then("its path is derived deterministically from its SHA-256 hash") {
                SourceContentReference(
                    fileId = "file:fixture",
                    contentHash = hash,
                    encodedByteSize = 12,
                ).file shouldBe "data/sources/$hash.txt"
            }

            then("mismatched hashes and paths are rejected") {
                shouldThrow<IllegalArgumentException> {
                    SourceContentReference(
                        fileId = "file:fixture",
                        contentHash = "A".repeat(64),
                        encodedByteSize = 12,
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    SourceContentReference(
                        fileId = "file:fixture",
                        contentHash = hash,
                        file = "data/sources/other.txt",
                        encodedByteSize = 12,
                    )
                }
            }

            then("legacy metadata shards without source references still decode") {
                val project =
                    ProjectGraphShard(
                        projectId = "project:fixture",
                        sourceSets = emptyList(),
                        files = emptyList(),
                        symbols = emptyList(),
                        relationships = emptyList(),
                    )
                val fields =
                    Json
                        .parseToJsonElement(WorkspaceSiteJson.encodeProject(project))
                        .jsonObject
                        .toMutableMap()
                        .apply { remove("sourceContents") }

                WorkspaceSiteJson.decodeProject(JsonObject(fields).toString()) shouldBe project
            }
        }
    })
