package zone.clanker.srcx.atlas

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.DriverManager

class AtlasSqliteFileReaderTest :
    BehaviorSpec({
        given("a schema 2 Atlas store written with JDBC") {
            `when`("the Kotlin file reader opens it") {
                val database = Files.createTempFile("atlas-reader-", ".sqlite")
                Class.forName("org.sqlite.JDBC")
                DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(AtlasStoreSchema.CREATE_META)
                        statement.execute(AtlasStoreSchema.CREATE_NODES)
                        statement.execute(AtlasStoreSchema.CREATE_RELATIONSHIPS)
                        statement.execute(AtlasStoreSchema.CREATE_IMPORTS)
                    }
                    connection.prepareStatement(AtlasStoreSchema.INSERT_META).use { statement ->
                        statement.setInt(1, 2)
                        statement.setString(2, "2026-08-17T15:00:00Z")
                        statement.setString(3, "reader-lab")
                        statement.setInt(4, 42)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(AtlasStoreSchema.INSERT_NODE).use { statement ->
                        statement.setString(1, "file::reader-lab:::main::A.kt")
                        statement.setString(2, AtlasStoreSchema.ENTITY_FILE)
                        statement.setInt(3, 1)
                        statement.setString(4, "reader-lab")
                        statement.setString(5, ":")
                        statement.setString(6, "main")
                        statement.setString(7, "A.kt")
                        statement.setString(8, "A.kt")
                        statement.setInt(9, 0)
                        statement.setString(10, "FILE")
                        statement.setString(11, "FILE")
                        statement.setString(12, "file::reader-lab:::main::A.kt")
                        statement.setBytes(13, AtlasCborRenderer.encodeNode(AtlasNodePayload(content = "class A")))
                        statement.executeUpdate()
                    }
                }

                then("seed nodes and meta come back without sql.js") {
                    val seed = AtlasSqliteFileReader(Files.readAllBytes(database)).readSeed()
                    seed.meta.schemaVersion shouldBe 2
                    seed.meta.seedLimit shouldBe 42
                    seed.meta.workspace shouldBe "reader-lab"
                    seed.nodes.single().name shouldBe "A.kt"
                    AtlasCborRenderer.decodeNode(seed.nodes.single().payload).content shouldBe "class A"
                }
            }
        }
    })
