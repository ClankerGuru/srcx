package zone.clanker.gradle.srcx.atlas

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.report.WorkspaceHtmlRenderer
import java.nio.file.Files
import java.sql.DriverManager
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArchitectureSummary
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSourceFile
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage

class AtlasSqliteWriterTest :
    BehaviorSpec({
        val writer = AtlasSqliteWriter()

        given("an Atlas site directory") {
            `when`("the overflow store is written") {
                val site = Files.createTempDirectory("atlas-store-write-")
                writer.write(site, workspace = "kind-lab", generatedAt = "2026-08-17T12:00:00Z")
                val database = site.resolve(AtlasStoreSchema.FILE_NAME)
                val firstSize = Files.size(database)

                then("the file is replaced with schema and one meta row") {
                    Files.isRegularFile(database) shouldBe true
                    tableNames(database) shouldContainExactly listOf(AtlasStoreSchema.META_TABLE)
                    columnNames(database) shouldContainExactly
                        listOf("schema_version", "generated_at", "workspace", "seed_limit")
                    readMeta(database) shouldBe
                        AtlasStoreMeta(
                            schemaVersion = 1,
                            generatedAt = "2026-08-17T12:00:00Z",
                            workspace = "kind-lab",
                            seedLimit = 42,
                        )
                    metaRowCount(database) shouldBe 1
                }

                then("a second regen replaces the file instead of appending") {
                    writer.write(site, workspace = "kind-lab", generatedAt = "2026-08-17T13:00:00Z")
                    Files.isRegularFile(database) shouldBe true
                    metaRowCount(database) shouldBe 1
                    readMeta(database) shouldBe
                        AtlasStoreMeta(
                            schemaVersion = 1,
                            generatedAt = "2026-08-17T13:00:00Z",
                            workspace = "kind-lab",
                            seedLimit = 42,
                        )
                    Files.size(database) shouldBe firstSize
                }
            }

            `when`("the seed HTML is rendered beside the store") {
                val html = WorkspaceHtmlRenderer().render(seedReport()).document

                then("the HTML seed stays 42 with empty available catalogs") {
                    html shouldContain "\"nodeLimit\":42"
                    html shouldContain "\"fileNodeLimit\":42"
                    html shouldContain "\"availableNodes\":[]"
                    html shouldContain "\"availableFileNodes\":[]"
                    html shouldContain "\"availableFileEdges\":[]"
                    html shouldContain "\"availableEdges\":[]"
                    html shouldNotContain "\"availableNodes\":[{"
                    html.toByteArray().size.toLong() shouldBeLessThan 6_000_000
                }
            }
        }
    })

private fun tableNames(database: java.nio.file.Path): List<String> =
    query(
        database,
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
    ) { result -> result.getString(1) }

private fun columnNames(database: java.nio.file.Path): List<String> =
    query(database, "PRAGMA table_info(meta)") { result -> result.getString("name") }

private fun metaRowCount(database: java.nio.file.Path): Int =
    query(database, "SELECT COUNT(*) FROM meta") { result -> result.getInt(1) }.single()

private fun readMeta(database: java.nio.file.Path): AtlasStoreMeta =
    query(database, "SELECT schema_version, generated_at, workspace, seed_limit FROM meta") { result ->
        AtlasStoreMeta(
            schemaVersion = result.getInt(1),
            generatedAt = result.getString(2),
            workspace = result.getString(3),
            seedLimit = result.getInt(4),
        )
    }.single()

private fun <T> query(
    database: java.nio.file.Path,
    sql: String,
    row: (java.sql.ResultSet) -> T,
): List<T> {
    Class.forName("org.sqlite.JDBC")
    return DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                buildList {
                    while (result.next()) add(row(result))
                }
            }
        }
    }
}

private fun seedReport(): WorkspaceReport {
    val symbol =
        WorkspaceSymbol(
            build = "atlas-root",
            project = ":app",
            sourceSet = "main",
            name = "SeedType",
            qualifiedName = "demo.SeedType",
            kind = SymbolDetailKind.CLASS,
            projectRelativeFile = "src/main/kotlin/demo/SeedType.kt",
            declarationLine = 1,
        )
    val entry =
        SymbolEntry(
            SymbolName(symbol.name),
            SymbolKind.CLASS,
            PackageName("demo"),
            FilePath(symbol.projectRelativeFile),
            1,
        )
    return WorkspaceReport(
        name = "atlas-root",
        rootProjects =
            listOf(
                ProjectSummary(
                    projectPath = ProjectPath(":app"),
                    symbols = listOf(entry),
                    dependencies = emptyList(),
                    buildFile = "build.gradle.kts",
                    sourceDirs = emptyList(),
                    subprojects = emptyList(),
                    sourceSets = listOf(SourceSetSummary(SourceSetName("main"), listOf(entry), listOf("src/main/kotlin"))),
                    analysis = AnalysisSummary(emptyList(), emptyList(), emptyList(), ArchitectureSummary()),
                ),
            ),
        includedBuilds = emptyList(),
        buildEdges = emptyList(),
        aggregateAnalysis = AnalysisSummary(emptyList(), emptyList(), emptyList()),
        entryPoints = emptyList(),
        interfaces = emptyList(),
        workspaceIndex =
            WorkspaceIndex(
                symbols = listOf(symbol),
                references = emptyList(),
                relationships = emptyList(),
                usages = listOf(WorkspaceSymbolUsage(symbol, emptyList(), emptyList())),
            ),
        importantSymbols = emptyList(),
        sourceFiles =
            listOf(
                WorkspaceSourceFile(
                    build = symbol.build,
                    project = symbol.project,
                    sourceSet = symbol.sourceSet,
                    projectRelativeFile = symbol.projectRelativeFile,
                    content = "class SeedType\n",
                ),
            ),
    )
}
