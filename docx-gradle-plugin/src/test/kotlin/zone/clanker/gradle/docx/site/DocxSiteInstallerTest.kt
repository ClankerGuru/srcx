package zone.clanker.gradle.docx.site

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.docx.DocxScopePlan
import zone.clanker.gradle.docx.NameSelectionPlan
import zone.clanker.gradle.docx.UnknownSelectionPolicy
import zone.clanker.gradle.docx.allNames
import zone.clanker.gradle.docx.crossProjectWorkspaceFixture
import zone.clanker.gradle.docx.docxTestPlan
import zone.clanker.gradle.docx.tempDirectory
import zone.clanker.gradle.docx.viewerDistribution
import zone.clanker.gradle.docx.workspaceFixture
import zone.clanker.gradle.docx.zipBytes
import zone.clanker.report.model.WorkspaceEvidenceJson
import zone.clanker.report.model.WorkspaceSearchJson
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSiteState
import zone.clanker.report.model.WorkspaceSnapshotJson
import java.nio.file.Files

class DocxSiteInstallerTest :
    BehaviorSpec({
        given("a valid precompiled viewer and renderer-neutral snapshot") {
            val root = tempDirectory("docx-site-").toPath()
            val site = root.resolve(".docx")
            val lock = root.resolve("state/site.lock")
            val snapshot = WorkspaceSnapshotJson.encode(workspaceFixture()).encodeToByteArray()
            val distribution = viewerDistribution("data/build-time-fixture.json" to "{}".encodeToByteArray())
            val plan = docxTestPlan()

            `when`("the static site is installed twice") {
                val first = DocxSiteInstaller().install(snapshot, distribution, plan, site, lock)
                val firstFiles = fileContents(site)
                val second = DocxSiteInstaller().install(snapshot, distribution, plan, site, lock)

                then("the content-derived generation is stable") {
                    second shouldBe first
                    fileContents(site) shouldBe firstFiles
                }

                then("the shared manifest points to the summary and one lazy project shard") {
                    val manifest =
                        WorkspaceSiteJson.decodeManifest(
                            Files.readString(site.resolve("data/manifest.json")),
                        )
                    manifest.workspaceFile shouldBe "data/workspace.json"
                    manifest.dashboardFile shouldBe "data/dashboard.json"
                    manifest.atlasOverviewFile shouldBe "data/atlas-overview.json"
                    manifest.atlasOverviewsFile shouldBe "data/atlas-overviews.json"
                    manifest.projectShards.map { it.projectId } shouldContainExactly listOf("project:a")
                    manifest.projectShards
                        .single()
                        .sourceContents
                        .map { it.fileId } shouldContainExactly
                        listOf("file:a", "file:b")
                    manifest.assetFiles shouldContainExactly
                        listOf("assets/docx-viewer.mjs", "assets/docx-viewer.wasm")
                    site.resolve(manifest.projectShards.single().file).toFile().shouldExist()
                    site.resolve(manifest.dashboardFile).toFile().shouldExist()
                    site.resolve(requireNotNull(manifest.atlasOverviewFile)).toFile().shouldExist()
                    site.resolve(requireNotNull(manifest.atlasOverviewsFile)).toFile().shouldExist()
                    manifest.projectShards.single().sourceContents.forEach { source ->
                        site.resolve(source.file).toFile().shouldExist()
                    }
                    val searchCatalog = requireNotNull(manifest.searchCatalog)
                    site.resolve(searchCatalog.file).toFile().shouldExist()
                    WorkspaceSearchJson
                        .decodeCatalog(Files.readString(site.resolve(searchCatalog.file)))
                        .generationId shouldBe manifest.generationId
                    val evidenceCatalog = requireNotNull(manifest.evidenceCatalog)
                    site.resolve(evidenceCatalog.file).toFile().shouldExist()
                    WorkspaceEvidenceJson
                        .decodeStaticCatalog(Files.readString(site.resolve(evidenceCatalog.file)))
                        .target
                        .generationId shouldBe manifest.generationId
                }

                then("build-time fixture data is replaced by generated typed data") {
                    Files.exists(site.resolve("data/build-time-fixture.json")) shouldBe false
                    val summary =
                        WorkspaceSiteJson.decodeWorkspace(
                            Files.readString(site.resolve("data/workspace.json")),
                        )
                    summary.workspace.name shouldBe "fixture"
                    val dashboard =
                        WorkspaceSiteJson.decodeDashboard(
                            Files.readString(site.resolve(first.manifest.dashboardFile)),
                        )
                    dashboard.workspaceId shouldBe summary.workspace.id
                    dashboard.projectCount shouldBe 1
                    dashboard.symbolCount shouldBe 2
                    val atlas =
                        WorkspaceSiteJson.decodeAtlasFrame(
                            Files.readString(site.resolve(requireNotNull(first.manifest.atlasOverviewFile))),
                        )
                    atlas.scope.workspaceId shouldBe summary.workspace.id
                    atlas.nodes.size shouldBe 2
                    atlas.shownRelationshipRecordCount shouldBe 1
                    val overviews =
                        WorkspaceSiteJson.decodeAtlasOverviews(
                            Files.readString(site.resolve(requireNotNull(first.manifest.atlasOverviewsFile))),
                        )
                    overviews.frame(atlas.lens) shouldBe atlas
                    val shard =
                        WorkspaceSiteJson.decodeProject(
                            Files.readString(
                                site.resolve(
                                    first.manifest.projectShards
                                        .single()
                                        .file,
                                ),
                            ),
                        )
                    shard.relationships.single().referenceId shouldBe "reference:a"
                    shard.references.single().line shouldBe 1
                    shard.files.all { file -> file.content == null } shouldBe true
                    shard.sourceContents shouldBe
                        first.manifest.projectShards
                            .single()
                            .sourceContents
                    val consumerSource = shard.sourceContents.single { source -> source.fileId == "file:a" }
                    Files.readString(site.resolve(consumerSource.file)) shouldBe
                        "class Consumer(val target: Target)\n"
                }

                then("the current typed status and ignore policy are present") {
                    val status = WorkspaceSiteJson.decodeStatus(Files.readString(site.resolve("status.json")))
                    status.state shouldBe WorkspaceSiteState.CURRENT
                    Files.readString(site.resolve(".gitignore")) shouldBe "*\n!.gitignore\n"
                    Files.readString(site.resolve(".docx-site")) shouldBe
                        "zone.clanker.gradle.docx/static-site/v1\n"
                }
            }
        }

        given("two files with one identical source body") {
            val root = tempDirectory("docx-source-dedupe-").toPath()
            val site = root.resolve(".docx")
            val fixture = workspaceFixture()
            val sharedContent = "class Shared\n"
            val snapshot =
                fixture.copy(
                    files = fixture.files.map { file -> file.copy(content = sharedContent) },
                )

            `when`("the content-addressed site is installed") {
                val installed =
                    DocxSiteInstaller().install(
                        WorkspaceSnapshotJson.encode(snapshot).encodeToByteArray(),
                        viewerDistribution(),
                        docxTestPlan(),
                        site,
                        root.resolve("state/site.lock"),
                    )

                then("both file references share one exact static body") {
                    val sources =
                        installed.manifest.projectShards
                            .single()
                            .sourceContents
                    sources.size shouldBe 2
                    sources.map { source -> source.file }.distinct().size shouldBe 1
                    Files.readString(site.resolve(sources.first().file)) shouldBe sharedContent
                    Files.list(site.resolve("data/sources")).use { paths -> paths.count() } shouldBe 1L
                }
            }
        }

        given("an existing valid generation") {
            val root = tempDirectory("docx-preserve-").toPath()
            val site = root.resolve(".docx")
            val lock = root.resolve("state/site.lock")
            val installer = DocxSiteInstaller()
            val initialSnapshot = WorkspaceSnapshotJson.encode(workspaceFixture()).encodeToByteArray()
            val distribution = viewerDistribution()
            val plan = docxTestPlan()
            installer.install(initialSnapshot, distribution, plan, site, lock)
            val initialIndex = Files.readString(site.resolve("index.html"))

            `when`("the replacement distribution attempts path traversal") {
                val malformed = zipBytes("../escape.html" to "no".encodeToByteArray())

                then("generation fails without replacing the last valid report") {
                    shouldThrow<IllegalArgumentException> {
                        installer.install(initialSnapshot, malformed, plan, site, lock)
                    }
                    Files.readString(site.resolve("index.html")) shouldBe initialIndex
                    Files.exists(root.resolve("escape.html")) shouldBe false
                }
            }

            `when`("a changed snapshot publishes successfully") {
                val changed = WorkspaceSnapshotJson.encode(workspaceFixture("renamed")).encodeToByteArray()
                val replacement = installer.install(changed, distribution, plan, site, lock)

                then("one bounded previous generation is retained") {
                    replacement.manifest.generationId shouldBe
                        WorkspaceSiteJson
                            .decodeManifest(Files.readString(site.resolve("data/manifest.json")))
                            .generationId
                    site.resolve("generations/previous/index.html").toFile().shouldExist()
                    Files.readString(site.resolve("generations/previous/index.html")) shouldBe initialIndex
                }
            }
        }

        given("a publication interrupted after preserving the last valid site") {
            val root = tempDirectory("docx-recover-").toPath()
            val site = root.resolve(".docx")
            val rollback = root.resolve(".docx.rollback")
            val lock = root.resolve("state/site.lock")
            val installer = DocxSiteInstaller()
            val snapshot = WorkspaceSnapshotJson.encode(workspaceFixture()).encodeToByteArray()
            val distribution = viewerDistribution()
            val plan = docxTestPlan()
            val installed = installer.install(snapshot, distribution, plan, site, lock)
            Files.move(site, rollback)

            `when`("the next invocation finds only the rollback") {
                val recovered = installer.install(snapshot, distribution, plan, site, lock)

                then("it restores the last valid generation before doing any cleanup") {
                    recovered.manifest shouldBe installed.manifest
                    site.resolve("index.html").toFile().shouldExist()
                    Files.exists(rollback) shouldBe false
                }
            }
        }

        given("publication recovery with both output and rollback directories") {
            val root = tempDirectory("docx-recover-both-").toPath()
            val site = root.resolve(".docx")
            val rollback = root.resolve(".docx.rollback")
            val lock = root.resolve("state/site.lock")
            val installer = DocxSiteInstaller()
            val snapshot = WorkspaceSnapshotJson.encode(workspaceFixture()).encodeToByteArray()
            val distribution = viewerDistribution()
            val plan = docxTestPlan()
            val initial = installer.install(snapshot, distribution, plan, site, lock)

            `when`("both copies are valid") {
                copyTree(site, rollback)
                val recovered = installer.install(snapshot, distribution, plan, site, lock)

                then("the current output wins and stale rollback is removed") {
                    recovered.manifest shouldBe initial.manifest
                    Files.exists(rollback) shouldBe false
                }
            }

            `when`("the output is invalid but rollback is valid") {
                copyTree(site, rollback)
                Files.writeString(site.resolve("data/manifest.json"), "invalid")
                val recovered = installer.install(snapshot, distribution, plan, site, lock)

                then("the valid rollback replaces the corrupt output") {
                    recovered.manifest shouldBe initial.manifest
                    Files.exists(rollback) shouldBe false
                    WorkspaceSiteJson.decodeManifest(
                        Files.readString(site.resolve("data/manifest.json")),
                    ) shouldBe initial.manifest
                }
            }
        }

        given("a valid generation with a transient failed status") {
            val root = tempDirectory("docx-repair-status-").toPath()
            val site = root.resolve(".docx")
            val lock = root.resolve("state/site.lock")
            val installer = DocxSiteInstaller()
            val snapshot = WorkspaceSnapshotJson.encode(workspaceFixture()).encodeToByteArray()
            val distribution = viewerDistribution()
            val plan = docxTestPlan()
            val installed = installer.install(snapshot, distribution, plan, site, lock)
            installer.markFailed(site, lock, "transient renderer failure")

            `when`("the same valid generation is requested again") {
                val repaired = installer.install(snapshot, distribution, plan, site, lock)

                then("the generation is current instead of reusing the failed status") {
                    repaired.manifest shouldBe installed.manifest
                    val status = WorkspaceSiteJson.decodeStatus(Files.readString(site.resolve("status.json")))
                    status.state shouldBe WorkspaceSiteState.CURRENT
                    status.generationId shouldBe installed.manifest.generationId
                }
            }
        }

        given("an unrelated non-empty output directory") {
            val root = tempDirectory("docx-unowned-output-").toPath()
            val sourceDirectory = root.resolve("src")
            Files.createDirectories(sourceDirectory)
            Files.writeString(sourceDirectory.resolve("Main.kt"), "class Main")
            val originalFiles = fileContents(sourceDirectory)

            `when`("site publication targets that directory") {
                then("the installer fails without changing any source file") {
                    shouldThrow<IllegalArgumentException> {
                        DocxSiteInstaller().install(
                            WorkspaceSnapshotJson.encode(workspaceFixture()).encodeToByteArray(),
                            viewerDistribution(),
                            docxTestPlan(),
                            sourceDirectory,
                            root.resolve("state/site.lock"),
                        )
                    }.message shouldContain "not a DOCX-owned site"
                    fileContents(sourceDirectory) shouldBe originalFiles
                    Files.exists(root.resolve("src.staging")) shouldBe false
                    Files.exists(root.resolve("src.rollback")) shouldBe false
                }
            }
        }

        listOf("staging", "rollback").forEach { publicationRole ->
            given("an unrelated non-empty $publicationRole publication sibling") {
                val root = tempDirectory("docx-unowned-$publicationRole-").toPath()
                val site = root.resolve(".docx")
                val sibling = root.resolve(".docx.$publicationRole")
                Files.createDirectories(sibling)
                Files.writeString(sibling.resolve("keep.txt"), "unrelated")
                val originalFiles = fileContents(sibling)

                `when`("site publication discovers the occupied sibling") {
                    then("the installer fails without deleting or moving it") {
                        shouldThrow<IllegalArgumentException> {
                            DocxSiteInstaller().install(
                                WorkspaceSnapshotJson.encode(workspaceFixture()).encodeToByteArray(),
                                viewerDistribution(),
                                docxTestPlan(),
                                site,
                                root.resolve("state/site.lock"),
                            )
                        }.message shouldContain "DOCX $publicationRole directory"
                        fileContents(sibling) shouldBe originalFiles
                    }
                }
            }
        }

        given("an outgoing cross-project import") {
            val snapshot = crossProjectWorkspaceFixture()

            `when`("the project graph is scoped from owned reference evidence") {
                val consumer =
                    DocxSiteProjection
                        .apply(snapshot, docxTestPlan())
                        .projectGraphs
                        .single { it.projectId == "project:a" }

                then("the outgoing relationship and external declaration context remain visible") {
                    consumer.relationships.map { it.id } shouldContainExactly
                        listOf("relationship:a", "relationship:b")
                    consumer.references.map { it.id } shouldContainExactly listOf("reference:a", "reference:b")
                    consumer.symbols.map { it.id } shouldContainExactly listOf("symbol:a", "symbol:b", "symbol:c")
                    consumer.files.single { it.id == "file:c" }.content shouldBe null
                }
            }

            `when`("the configured project scope selects only the root project") {
                val rootOnly =
                    NameSelectionPlan(
                        all = false,
                        included = listOf(":"),
                        excluded = emptyList(),
                    )
                val plan =
                    docxTestPlan(
                        scope =
                            DocxScopePlan(
                                builds = allNames(),
                                projects = rootOnly,
                                sourceSets = allNames(),
                                includeTests = true,
                                configuredBuilds = emptyList(),
                                unknownSelections = UnknownSelectionPolicy.FAIL,
                            ),
                    )
                val projection = DocxSiteProjection.apply(snapshot, plan)

                then("the summary and lazy shard catalog contain only that selected project") {
                    projection.projects.map { it.id } shouldContainExactly listOf("project:a")
                    projection.projectGraphs.map { it.projectId } shouldContainExactly listOf("project:a")
                }
            }
        }

        given("invalid generation inputs") {
            val root = tempDirectory("docx-invalid-").toPath()
            val site = root.resolve(".docx")
            val lock = root.resolve("state/site.lock")
            val installer = DocxSiteInstaller()

            `when`("the snapshot schema is incompatible") {
                then("the shared decoder rejects it before publication") {
                    shouldThrow<Exception> {
                        installer.install(
                            "{\"schemaVersion\":999}".encodeToByteArray(),
                            viewerDistribution(),
                            docxTestPlan(),
                            site,
                            lock,
                        )
                    }
                    Files.exists(site.resolve("index.html")) shouldBe false
                    val status = WorkspaceSiteJson.decodeStatus(Files.readString(site.resolve("status.json")))
                    status.state shouldBe WorkspaceSiteState.FAILED
                    status.message.isNotBlank() shouldBe true
                }
            }

            `when`("the viewer has no Wasm runtime") {
                val malformed =
                    zipBytes(
                        "index.html" to "<script src=\"assets/viewer.js\"></script>".encodeToByteArray(),
                        "assets/viewer.js" to byteArrayOf(1),
                    )

                then("the installer rejects the incomplete distribution") {
                    shouldThrow<IllegalArgumentException> {
                        installer.install(
                            WorkspaceSnapshotJson.encode(workspaceFixture()).encodeToByteArray(),
                            malformed,
                            docxTestPlan(),
                            site,
                            lock,
                        )
                    }.message shouldContain "Wasm"
                }
            }

            `when`("the viewer index names a missing local asset") {
                val malformed =
                    zipBytes(
                        "index.html" to
                            "<script src=\"assets/missing.js\"></script>".encodeToByteArray(),
                        "assets/viewer.js" to byteArrayOf(1),
                        "assets/viewer.wasm" to byteArrayOf(0, 97, 115, 109),
                    )

                then("verification rejects the broken bootstrap reference") {
                    shouldThrow<IllegalArgumentException> {
                        installer.install(
                            WorkspaceSnapshotJson.encode(workspaceFixture()).encodeToByteArray(),
                            malformed,
                            docxTestPlan(),
                            site,
                            lock,
                        )
                    }.message shouldContain "missing local asset"
                }
            }

            `when`("a failed status is recorded without a prior report") {
                installer.markFailed(site, lock, "snapshot unavailable")

                then("the failure remains machine-readable") {
                    val status = WorkspaceSiteJson.decodeStatus(Files.readString(site.resolve("status.json")))
                    status.state shouldBe WorkspaceSiteState.FAILED
                    status.message shouldContain "snapshot unavailable"
                }
            }
        }
    })

private fun fileContents(root: java.nio.file.Path): Map<String, List<Byte>> =
    Files.walk(root).use { paths ->
        paths
            .filter { path -> Files.isRegularFile(path) }
            .iterator()
            .asSequence()
            .associate { path -> root.relativize(path).toString() to Files.readAllBytes(path).toList() }
    }

private fun copyTree(
    source: java.nio.file.Path,
    target: java.nio.file.Path,
) {
    Files.walk(source).use { paths ->
        paths.forEach { path ->
            val destination = target.resolve(source.relativize(path).toString())
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination)
            } else {
                Files.createDirectories(destination.parent)
                Files.copy(path, destination)
            }
        }
    }
}
