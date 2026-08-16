package zone.clanker.docx.web.atlas.source

import kotlinx.coroutines.test.runTest
import zone.clanker.docx.web.atlas.hierarchy.workspaceSourceHierarchySlice
import zone.clanker.docx.web.fixture.DocxWebFixture
import zone.clanker.docx.web.site.LoadedWorkspaceSite
import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.docx.web.site.WorkspaceSiteTextSource
import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphJson
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSelection
import zone.clanker.report.model.WorkspaceGraphView
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.WorkspaceSummaryShard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceGraphSliceSourceTest {
    @Test
    fun selectedBuildUsesTheSummaryWithoutReadingEveryProjectShard() =
        runTest {
            val requestedPaths = mutableListOf<String>()
            val loader = recordingLoader(requestedPaths)
            val site = loader.loadWorkspace()
            requestedPaths.clear()
            val source = staticSource(loader, site)

            val slice = source.load(graphRequest(scopeRootIds = listOf("build:fixture")))

            assertTrue(requestedPaths.isEmpty())
            assertEquals(
                listOf("project:fixture:app"),
                slice.content.nodes
                    .filter { node -> node.hierarchy.parentId == "build:fixture" }
                    .map { node -> node.id },
            )
        }

    @Test
    fun expandingOneProjectInAMultiProjectScopeLoadsOnlyThatProject() =
        runTest {
            val summary = groupedMainSummary(projectCount = 2)
            val requestedProjectIds = mutableListOf<String>()
            val source = recordingStaticSource(summary, requestedProjectIds)
            val expandedProjectId = summary.projects.last().id

            source.load(
                graphRequest(
                    scopeRootIds = summary.projects.map(ProjectSnapshot::id),
                    expandedNodeIds = listOf(expandedProjectId),
                    workspaceId = summary.workspace.id,
                ),
            )

            assertEquals(listOf(expandedProjectId), requestedProjectIds)
        }

    @Test
    fun groupedSourceSetScopeStaysSummaryOnlyUntilOneOwnerIsExpanded() =
        runTest {
            val summary = groupedMainSummary(projectCount = 100)
            val requestedProjectIds = mutableListOf<String>()
            val source = recordingStaticSource(summary, requestedProjectIds)
            val groupedMainIds = summary.sourceSets.map(WorkspaceSourceSetSummary::id)

            source.load(
                graphRequest(
                    scopeRootIds = groupedMainIds,
                    workspaceId = summary.workspace.id,
                ),
            )
            assertTrue(requestedProjectIds.isEmpty())

            val expandedSourceSet = summary.sourceSets[42]
            source.load(
                graphRequest(
                    scopeRootIds = groupedMainIds,
                    expandedNodeIds = listOf(expandedSourceSet.id),
                    workspaceId = summary.workspace.id,
                ),
            )

            assertEquals(listOf(expandedSourceSet.projectId), requestedProjectIds)
        }

    @Test
    fun discoveredPackageExpansionReusesItsRememberedProjectOwner() =
        runTest {
            val requestedPaths = mutableListOf<String>()
            val loader = recordingLoader(requestedPaths)
            val site = loader.loadWorkspace()
            requestedPaths.clear()
            val source = staticSource(loader, site)
            val mainSourceSetId =
                site.summary.sourceSets
                    .single { sourceSet ->
                        sourceSet.projectId == DocxWebFixture.project.projectId && sourceSet.name == "main"
                    }.id
            val collapsedRequest = graphRequest(scopeRootIds = listOf(mainSourceSetId))

            var collapsed = source.load(collapsedRequest)
            val packageId =
                collapsed.content.nodes
                    .single { node -> node.kind == WorkspaceGraphNodeKind.PACKAGE }
                    .id
            val expandedRequest =
                graphRequest(
                    scopeRootIds = listOf(mainSourceSetId),
                    expandedNodeIds = listOf(packageId),
                    focusNodeIds = listOf(packageId),
                )

            assertEquals(5, collapsed.content.nodes.size)
            assertEquals(0, collapsed.content.relations.size)
            repeat(PACKAGE_DELIVERY_STRESS_REPETITIONS) {
                val expanded = source.load(expandedRequest)
                assertEquals(7, expanded.content.nodes.size)
                assertEquals(1, expanded.content.relations.size)

                collapsed = source.load(collapsedRequest)
                assertEquals(5, collapsed.content.nodes.size)
                assertEquals(0, collapsed.content.relations.size)
            }
            assertEquals(1 + PACKAGE_DELIVERY_STRESS_REPETITIONS * 2, requestedPaths.size)
            assertEquals(1, requestedPaths.distinct().size)
        }

    @Test
    fun staticAndLiveSourcesReturnTheSameBoundedProjectSlice() =
        runTest {
            val requestedPaths = mutableListOf<String>()
            val loader = recordingLoader(requestedPaths)
            val site = loader.loadWorkspace()
            requestedPaths.clear()
            val request = graphRequest(scopeRootIds = listOf(DocxWebFixture.project.projectId))
            val expected =
                workspaceSourceHierarchySlice(
                    summary = site.summary,
                    generationId = site.manifest.generationId,
                    request = request,
                    loadedProjects = listOf(DocxWebFixture.project),
                )
            val staticSource = staticSource(loader, site)

            assertEquals(expected, staticSource.load(request))
            assertEquals(
                listOf(
                    DocxWebFixture.manifest.projectShards
                        .single { reference -> reference.projectId == DocxWebFixture.project.projectId }
                        .file,
                ),
                requestedPaths,
            )

            val posts = mutableListOf<Pair<String, String>>()
            val liveSource =
                LiveWorkspaceGraphSliceSource(
                    endpoint = "/w/fixture/api/graph",
                    transport =
                        WorkspaceGraphHttpTransport { endpoint, body ->
                            posts += endpoint to body
                            WorkspaceGraphHttpResponse(200, WorkspaceGraphJson.encodeSlice(expected))
                        },
                )
            assertEquals(expected, liveSource.load(request))
            assertEquals(listOf("/w/fixture/api/graph"), posts.map { post -> post.first })
            assertEquals(request, WorkspaceGraphJson.decodeRequest(posts.single().second))
        }

    @Test
    fun mountedEndpointFallbackIsLimitedToAnUnavailableRoute() =
        runTest {
            val request = graphRequest()
            val expected =
                workspaceSourceHierarchySlice(
                    summary = DocxWebFixture.summary,
                    generationId = DocxWebFixture.manifest.generationId,
                    request = request,
                )
            var fallbackLoads = 0
            val fallback =
                WorkspaceGraphSliceSource {
                    fallbackLoads += 1
                    expected
                }
            val unavailable =
                EndpointFallbackWorkspaceGraphSliceSource(
                    live = liveRespondingWith(404),
                    static = fallback,
                )
            assertEquals(expected, unavailable.load(request))
            assertEquals(1, fallbackLoads)

            val serviceFailure =
                EndpointFallbackWorkspaceGraphSliceSource(
                    live = liveRespondingWith(503),
                    static = fallback,
                )
            val error = assertFailsWith<WorkspaceGraphHttpException> { serviceFailure.load(request) }
            assertEquals(503, error.statusCode)
            assertEquals(1, fallbackLoads)
        }

    @Test
    fun mountedEndpointsAreStrictRelativeSameOriginPaths() {
        assertEquals("/w/fixture/api/graph", mountedWorkspaceGraphEndpoint("/w/fixture/"))
        assertEquals("/w/fixture/api/graph", mountedWorkspaceGraphEndpoint("/w/fixture/data/manifest.json"))
        assertEquals(null, mountedWorkspaceGraphEndpoint("/reports/fixture/"))
        assertEquals(null, mountedWorkspaceGraphEndpoint("https://example.test/w/fixture/"))
        assertEquals(null, mountedWorkspaceGraphEndpoint("/w/UPPER/"))
        assertFailsWith<IllegalArgumentException> {
            LiveWorkspaceGraphSliceSource("https://example.test/w/fixture/api/graph") { _, _ ->
                WorkspaceGraphHttpResponse(200, "")
            }
        }
    }

    private fun recordingLoader(requestedPaths: MutableList<String>): WorkspaceSiteLoader =
        WorkspaceSiteLoader(
            WorkspaceSiteTextSource { path ->
                requestedPaths += path
                DocxWebFixture.source.read(path)
            },
        )

    private fun staticSource(
        loader: WorkspaceSiteLoader,
        site: LoadedWorkspaceSite,
    ): WorkspaceGraphSliceSource =
        StaticWorkspaceGraphSliceSource(
            summary = site.summary,
            projects = ProjectGraphShardSource { projectId -> loader.loadProject(site, projectId) },
            projector =
                WorkspaceGraphSliceProjector { request, projects ->
                    workspaceSourceHierarchySlice(
                        summary = site.summary,
                        generationId = site.manifest.generationId,
                        request = request,
                        loadedProjects = projects,
                    )
                },
        )

    private fun recordingStaticSource(
        summary: WorkspaceSummaryShard,
        requestedProjectIds: MutableList<String>,
    ): WorkspaceGraphSliceSource =
        StaticWorkspaceGraphSliceSource(
            summary = summary,
            projects =
                ProjectGraphShardSource { projectId ->
                    requestedProjectIds += projectId
                    val sourceSets =
                        summary.sourceSets
                            .filter { sourceSet -> sourceSet.projectId == projectId }
                            .map { sourceSet -> SourceSetSnapshot(sourceSet.id, projectId, sourceSet.name) }
                    ProjectGraphShard(
                        projectId = projectId,
                        sourceSets = sourceSets,
                        files = emptyList(),
                        symbols = emptyList(),
                        relationships = emptyList(),
                    )
                },
            projector =
                WorkspaceGraphSliceProjector { request, projects ->
                    workspaceSourceHierarchySlice(
                        summary = summary,
                        generationId = TEST_GENERATION_ID,
                        request = request,
                        loadedProjects = projects,
                    )
                },
        )

    private fun graphRequest(
        scopeRootIds: List<String> = emptyList(),
        expandedNodeIds: List<String> = emptyList(),
        focusNodeIds: List<String> = emptyList(),
        workspaceId: String = DocxWebFixture.summary.workspace.id,
    ): WorkspaceGraphRequest =
        WorkspaceGraphRequest(
            workspaceId = workspaceId,
            generationId =
                if (workspaceId == DocxWebFixture.summary.workspace.id) {
                    DocxWebFixture.manifest.generationId
                } else {
                    TEST_GENERATION_ID
                },
            facet = WorkspaceGraphFacet.SOURCE,
            view =
                WorkspaceGraphView(
                    selection =
                        WorkspaceGraphSelection(
                            scopeRootIds = scopeRootIds,
                            expandedNodeIds = expandedNodeIds,
                            focusNodeIds = focusNodeIds,
                        ),
                ),
        )

    private fun groupedMainSummary(projectCount: Int): WorkspaceSummaryShard {
        val projects =
            (0 until projectCount).map { index ->
                val suffix = index.toString().padStart(3, '0')
                ProjectSnapshot(
                    id = "project:grouped:$suffix",
                    buildId = TEST_BUILD_ID,
                    path = ":project-$suffix",
                    buildFile = "project-$suffix/build.gradle.kts",
                )
            }
        return WorkspaceSummaryShard(
            workspace = WorkspaceIdentity(TEST_WORKSPACE_ID, "Grouped main fixture"),
            builds = listOf(BuildSnapshot(TEST_BUILD_ID, "grouped", BuildKind.ROOT, ".")),
            projects = projects,
            sourceSets =
                projects.map { project ->
                    WorkspaceSourceSetSummary(
                        id = "source-set:grouped:${project.id.substringAfterLast(':')}:main",
                        projectId = project.id,
                        name = "main",
                        fileCount = 0,
                    )
                },
            projectShards =
                projects.map { project ->
                    ProjectShardReference(
                        projectId = project.id,
                        file = "data/projects/${project.id.substringAfterLast(':')}.json",
                    )
                },
        )
    }

    private fun liveRespondingWith(statusCode: Int): WorkspaceGraphSliceSource =
        LiveWorkspaceGraphSliceSource("/w/fixture/api/graph") { _, _ ->
            WorkspaceGraphHttpResponse(statusCode, "service response")
        }

    private companion object {
        const val TEST_WORKSPACE_ID = "workspace:grouped"
        const val TEST_BUILD_ID = "build:grouped"
        const val TEST_GENERATION_ID = "generation:grouped"
        const val PACKAGE_DELIVERY_STRESS_REPETITIONS = 16
    }
}
