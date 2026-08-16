package zone.clanker.docx.web.probe

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InteractionProbeScopeReadinessTest {
    @Test
    fun publishesABuildScopeFromTheCanonicalFrameWithoutBulkProjectShards() {
        val readiness =
            InteractionProbeScopeReadiness(
                kind = InteractionProbeScopeReadiness.Kind.BOUNDED_GRAPH,
                atlasOverviewAvailable = true,
                canonicalGraphSettled = true,
            )

        assertTrue(readiness.isSettled())
        assertFalse(readiness.copy(canonicalGraphSettled = false).isSettled())
    }

    @Test
    fun keepsOverviewAndBoundedGraphReadinessExact() {
        val bounded =
            InteractionProbeScopeReadiness(
                kind = InteractionProbeScopeReadiness.Kind.BOUNDED_GRAPH,
                atlasOverviewAvailable = false,
                canonicalGraphSettled = false,
            )
        val overview = bounded.copy(kind = InteractionProbeScopeReadiness.Kind.OVERVIEW)

        assertFalse(bounded.isSettled())
        assertTrue(bounded.copy(canonicalGraphSettled = true).isSettled())
        assertFalse(overview.isSettled())
        assertTrue(overview.copy(atlasOverviewAvailable = true).isSettled())
    }
}
