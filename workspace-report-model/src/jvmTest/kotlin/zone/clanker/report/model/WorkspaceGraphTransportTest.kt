package zone.clanker.report.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkspaceGraphTransportTest :
    BehaviorSpec({
        given("a bounded source-hierarchy slice") {
            val slice = validGraphSlice()

            `when`("it crosses the common serialization boundary") {
                val format = Json { encodeDefaults = true }
                val decoded = format.decodeFromString<WorkspaceGraphSlice>(format.encodeToString(slice))

                then("the hierarchy, relation evidence, and explicit availability survive") {
                    decoded shouldBe slice
                    decoded.content.nodes.size shouldBe 8
                    decoded.content.relations
                        .single()
                        .facts.sampleFactIds shouldBe
                        listOf("relationship:a", "relationship:b")
                    decoded.content.availability
                        .single { item -> item.fact == WorkspaceGraphFactKind.GRADLE_TASKS }
                        .state shouldBe WorkspaceGraphAvailabilityState.UNAVAILABLE
                    decoded.content.availability
                        .single { item -> item.fact == WorkspaceGraphFactKind.STRUCTURE }
                        .observedFactCount shouldBe 8
                }
            }

            `when`("the request pins a generation for continuation") {
                val request =
                    WorkspaceGraphRequest(
                        workspaceId = WORKSPACE_ID,
                        generationId = GENERATION_ID,
                        facet = WorkspaceGraphFacet.SOURCE,
                        view =
                            WorkspaceGraphView(
                                selection =
                                    WorkspaceGraphSelection(
                                        scopeRootIds = listOf(PROJECT_ID),
                                        expandedNodeIds = listOf(PACKAGE_ID),
                                        focusNodeIds = listOf(MEMBER_ID),
                                    ),
                                filter =
                                    WorkspaceGraphFilter(
                                        nodeKinds =
                                            listOf(
                                                WorkspaceGraphNodeKind.FILE,
                                                WorkspaceGraphNodeKind.PACKAGE,
                                            ),
                                        relationKinds =
                                            listOf(
                                                WorkspaceGraphRelationKind.CALL,
                                                WorkspaceGraphRelationKind.TYPE_REFERENCE,
                                            ),
                                        query = "worker",
                                        searchTargets =
                                            listOf(
                                                WorkspaceGraphSearchTarget.FILE,
                                                WorkspaceGraphSearchTarget.METHOD,
                                            ),
                                        declarationKinds =
                                            listOf(
                                                WorkspaceGraphDeclarationKind.CLASS,
                                                WorkspaceGraphDeclarationKind.METHOD,
                                            ),
                                        relationshipCountFilter =
                                            WorkspaceGraphRelationshipCountFilter(
                                                moreThan = 4,
                                                direction = WorkspaceGraphRelationshipDirection.OUTGOING,
                                                keepInverseContext = false,
                                            ),
                                    ),
                                continuationToken = "next:fixture",
                            ),
                        limits = TEST_LIMITS,
                    )
                val format = Json { encodeDefaults = true }
                val decoded = format.decodeFromString<WorkspaceGraphRequest>(format.encodeToString(request))

                then("all filters remain deterministic and bounded") {
                    decoded shouldBe request
                    decoded.view.filter.searchTargets shouldBe
                        listOf(
                            WorkspaceGraphSearchTarget.FILE,
                            WorkspaceGraphSearchTarget.METHOD,
                        )
                    decoded.view.filter.declarationKinds shouldBe
                        listOf(
                            WorkspaceGraphDeclarationKind.CLASS,
                            WorkspaceGraphDeclarationKind.METHOD,
                        )
                    decoded.view.filter.relationshipCountFilter shouldBe
                        WorkspaceGraphRelationshipCountFilter(
                            moreThan = 4,
                            direction = WorkspaceGraphRelationshipDirection.OUTGOING,
                            keepInverseContext = false,
                        )
                    decoded.view.selection.scopeRootIds shouldBe listOf(PROJECT_ID)
                    decoded.limits shouldBe TEST_LIMITS
                }
            }
        }

        given("a request encoded before typed graph filters were added") {
            val filter =
                Json.decodeFromString<WorkspaceGraphFilter>(
                    """{"nodeKinds":[],"relationKinds":[],"query":"worker"}""",
                )

            then("missing declaration, search-target, and relationship-count filters use compatible defaults") {
                filter.searchTargets shouldBe emptyList()
                filter.declarationKinds shouldBe emptyList()
                filter.relationshipCountFilter shouldBe null
            }
        }

        given("a request outside the transport envelope") {
            then("unsorted IDs, unpinned continuation, long queries, and oversized limits are rejected") {
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphRequest(
                        workspaceId = WORKSPACE_ID,
                        facet = WorkspaceGraphFacet.SOURCE,
                        view =
                            WorkspaceGraphView(
                                selection = WorkspaceGraphSelection(scopeRootIds = listOf(PROJECT_ID, BUILD_ID)),
                            ),
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphRequest(
                        workspaceId = WORKSPACE_ID,
                        facet = WorkspaceGraphFacet.SOURCE,
                        view = WorkspaceGraphView(continuationToken = "next:fixture"),
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphFilter(
                        query = "x".repeat(WorkspaceGraphFilter.MAX_QUERY_LENGTH + 1),
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphFilter(
                        nodeKinds = listOf(WorkspaceGraphNodeKind.PACKAGE, WorkspaceGraphNodeKind.FILE),
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphLimits(nodeLimit = WorkspaceGraphLimits.MAX_NODE_LIMIT + 1)
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphLimits(relationLimit = 0)
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphLimits(
                        evidencePerRelationLimit = WorkspaceGraphLimits.MAX_EVIDENCE_PER_RELATION_LIMIT + 1,
                    )
                }
            }

            then("search targets must be distinct and deterministic") {
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphFilter(
                        searchTargets =
                            listOf(
                                WorkspaceGraphSearchTarget.PROJECT,
                                WorkspaceGraphSearchTarget.BUILD,
                            ),
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphFilter(
                        searchTargets =
                            listOf(
                                WorkspaceGraphSearchTarget.BUILD,
                                WorkspaceGraphSearchTarget.BUILD,
                            ),
                    )
                }
            }

            then("declaration kinds must be distinct and deterministic") {
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphFilter(
                        declarationKinds =
                            listOf(
                                WorkspaceGraphDeclarationKind.METHOD,
                                WorkspaceGraphDeclarationKind.CLASS,
                            ),
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphFilter(
                        declarationKinds =
                            listOf(
                                WorkspaceGraphDeclarationKind.CLASS,
                                WorkspaceGraphDeclarationKind.CLASS,
                            ),
                    )
                }
            }

            then("relationship-count thresholds are strictly more-than and nonnegative") {
                WorkspaceGraphRelationshipCountFilter(moreThan = 0) shouldBe
                    WorkspaceGraphRelationshipCountFilter(
                        moreThan = 0,
                        direction = WorkspaceGraphRelationshipDirection.ANY,
                        keepInverseContext = true,
                    )
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphRelationshipCountFilter(moreThan = -1)
                }
            }
        }

        given("a producer attempting to emit an incomplete graph") {
            val slice = validGraphSlice()

            then("missing ancestors and relation endpoints are rejected") {
                shouldThrow<IllegalArgumentException> {
                    slice.copy(
                        content =
                            slice.content.copy(
                                nodes = slice.content.nodes.filterNot { node -> node.id == BUILD_ID },
                            ),
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    slice.copy(
                        content =
                            slice.content.copy(
                                nodes = slice.content.nodes.filterNot { node -> node.id == MEMBER_ID },
                            ),
                    )
                }
            }

            then("incorrect depth and understated hierarchy totals are rejected") {
                shouldThrow<IllegalArgumentException> {
                    slice.copy(
                        content =
                            slice.content.copy(
                                nodes =
                                    slice.content.nodes
                                        .map { node ->
                                            if (node.id == MEMBER_ID) {
                                                node.copy(hierarchy = node.hierarchy.copy(depth = 6))
                                            } else {
                                                node
                                            }
                                        }.sortedBy(WorkspaceGraphNode::id),
                            ),
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    slice.copy(
                        content =
                            slice.content.copy(
                                nodes =
                                    slice.content.nodes
                                        .map { node ->
                                            if (node.id == WORKSPACE_ID) {
                                                node.copy(hierarchy = node.hierarchy.copy(descendantCount = 1))
                                            } else {
                                                node
                                            }
                                        }.sortedBy(WorkspaceGraphNode::id),
                            ),
                    )
                }
            }
        }

        given("slice and evidence bounds") {
            val slice = validGraphSlice()
            val relation = slice.content.relations.single()

            then("the declared response limits are enforced") {
                shouldThrow<IllegalArgumentException> {
                    slice.copy(limits = TEST_LIMITS.copy(nodeLimit = slice.content.nodes.size - 1))
                }
                shouldThrow<IllegalArgumentException> {
                    slice.copy(limits = TEST_LIMITS.copy(relationLimit = 1, evidencePerRelationLimit = 1))
                }
                shouldThrow<IllegalArgumentException> {
                    slice.copy(counts = slice.counts.copy(matchingPrimaryNodeCount = 0))
                }
                shouldThrow<IllegalArgumentException> {
                    slice.copy(counts = slice.counts.copy(matchingRelationCount = 0))
                }
            }

            then("sampled and omitted facts must cover the aggregate exactly") {
                shouldThrow<IllegalArgumentException> {
                    relation.copy(facts = relation.facts.copy(omittedFactCount = 1))
                }
                relation.facts.factCount shouldBe 4
                relation.facts.omittedFactCount shouldBe 2
            }
        }

        given("fact-family availability") {
            val availability = graphAvailability()

            then("complete-empty remains different from unavailable") {
                WorkspaceGraphFactAvailability(
                    fact = WorkspaceGraphFactKind.DEPENDENCY_UPGRADES,
                    state = WorkspaceGraphAvailabilityState.COMPLETE,
                    observedFactCount = 0,
                ).state shouldBe WorkspaceGraphAvailabilityState.COMPLETE
                availability
                    .single { item -> item.fact == WorkspaceGraphFactKind.DEPENDENCY_UPGRADES }
                    .state shouldBe WorkspaceGraphAvailabilityState.UNAVAILABLE
            }

            then("every fact family must be stated and unavailable families cannot claim observations") {
                shouldThrow<IllegalArgumentException> {
                    val slice = validGraphSlice()
                    slice.copy(content = slice.content.copy(availability = availability.dropLast(1)))
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceGraphFactAvailability(
                        fact = WorkspaceGraphFactKind.GRADLE_TASKS,
                        state = WorkspaceGraphAvailabilityState.UNAVAILABLE,
                        observedFactCount = 1,
                        reason = WorkspaceGraphAvailabilityReason.NOT_CAPTURED,
                    )
                }
            }
        }
    })

private fun validGraphSlice(): WorkspaceGraphSlice =
    WorkspaceGraphSlice(
        target = WorkspaceGraphSliceTarget(WORKSPACE_ID, GENERATION_ID, WorkspaceGraphFacet.SOURCE),
        viewport = WorkspaceGraphSliceViewport(listOf(PROJECT_ID), "next:fixture"),
        content =
            WorkspaceGraphSliceContent(
                availability = graphAvailability(),
                nodes = graphNodes(),
                relations =
                    listOf(
                        WorkspaceGraphRelation(
                            id = "graph-relation:call",
                            endpoints = WorkspaceGraphRelationEndpoints(MEMBER_ID, TYPE_ID),
                            kind = WorkspaceGraphRelationKind.CALL,
                            facts =
                                WorkspaceGraphRelationFacts(
                                    factCount = 4,
                                    sampleFactIds = listOf("relationship:a", "relationship:b"),
                                ),
                        ),
                    ),
            ),
        counts =
            WorkspaceGraphSliceCounts(
                matchingPrimaryNodeCount = 6,
                matchingRelationCount = 1,
            ),
        limits = TEST_LIMITS,
    )

private fun graphAvailability(): List<WorkspaceGraphFactAvailability> =
    WorkspaceGraphFactKind.entries
        .sortedBy(Enum<*>::name)
        .map { fact ->
            when (fact) {
                WorkspaceGraphFactKind.STRUCTURE ->
                    WorkspaceGraphFactAvailability(
                        fact = fact,
                        state = WorkspaceGraphAvailabilityState.COMPLETE,
                        observedFactCount = 8,
                    )

                WorkspaceGraphFactKind.CODE_RELATIONSHIPS ->
                    WorkspaceGraphFactAvailability(
                        fact = fact,
                        state = WorkspaceGraphAvailabilityState.PARTIAL,
                        observedFactCount = 4,
                        reason = WorkspaceGraphAvailabilityReason.SOURCE_SCAN_ONLY,
                    )

                else ->
                    WorkspaceGraphFactAvailability(
                        fact = fact,
                        state = WorkspaceGraphAvailabilityState.UNAVAILABLE,
                        observedFactCount = 0,
                        reason = WorkspaceGraphAvailabilityReason.NOT_CAPTURED,
                    )
            }
        }

private fun graphNodes(): List<WorkspaceGraphNode> =
    listOf(
        graphNode(
            WORKSPACE_ID,
            WorkspaceGraphNodeKind.WORKSPACE,
            WorkspaceGraphNodeHierarchy(null, 0, 1, 7),
            WorkspaceGraphNodeRole.ANCESTOR,
        ),
        graphNode(
            BUILD_ID,
            WorkspaceGraphNodeKind.BUILD,
            WorkspaceGraphNodeHierarchy(WORKSPACE_ID, 1, 1, 6),
            WorkspaceGraphNodeRole.ANCESTOR,
        ),
        graphNode(PROJECT_ID, WorkspaceGraphNodeKind.PROJECT, WorkspaceGraphNodeHierarchy(BUILD_ID, 2, 1, 5)),
        graphNode(
            SOURCE_SET_ID,
            WorkspaceGraphNodeKind.SOURCE_SET,
            WorkspaceGraphNodeHierarchy(PROJECT_ID, 3, 1, 4),
        ),
        graphNode(
            PACKAGE_ID,
            WorkspaceGraphNodeKind.PACKAGE,
            WorkspaceGraphNodeHierarchy(SOURCE_SET_ID, 4, 1, 3),
        ),
        graphNode(FILE_ID, WorkspaceGraphNodeKind.FILE, WorkspaceGraphNodeHierarchy(PACKAGE_ID, 5, 1, 2)),
        graphNode(TYPE_ID, WorkspaceGraphNodeKind.TYPE, WorkspaceGraphNodeHierarchy(FILE_ID, 6, 1, 1)),
        graphNode(MEMBER_ID, WorkspaceGraphNodeKind.MEMBER, WorkspaceGraphNodeHierarchy(TYPE_ID, 7, 0, 0)),
    ).sortedBy(WorkspaceGraphNode::id)

private fun graphNode(
    id: String,
    kind: WorkspaceGraphNodeKind,
    hierarchy: WorkspaceGraphNodeHierarchy,
    role: WorkspaceGraphNodeRole = WorkspaceGraphNodeRole.PRIMARY,
): WorkspaceGraphNode =
    WorkspaceGraphNode(
        id = id,
        kind = kind,
        hierarchy = hierarchy,
        presentation = WorkspaceGraphNodePresentation(id.substringAfter(':'), kind.name.lowercase()),
        roles = listOf(role),
    )

private const val WORKSPACE_ID = "workspace:fixture"
private const val GENERATION_ID = "generation:fixture"
private const val BUILD_ID = "build:fixture"
private const val PROJECT_ID = "project:app"
private const val SOURCE_SET_ID = "source-set:main"
private const val PACKAGE_ID = "package:example"
private const val FILE_ID = "file:worker"
private const val TYPE_ID = "type:worker"
private const val MEMBER_ID = "member:run"
private val TEST_LIMITS = WorkspaceGraphLimits(nodeLimit = 16, relationLimit = 8, evidencePerRelationLimit = 2)
