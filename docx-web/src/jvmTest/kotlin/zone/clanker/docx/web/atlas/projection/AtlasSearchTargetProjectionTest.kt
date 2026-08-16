package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasLens
import kotlin.test.Test
import kotlin.test.assertContentEquals

class AtlasSearchTargetProjectionTest {
    @Test
    fun searchesOnlyTruthfulTypedFields() {
        val cases =
            listOf(
                SearchCase(AtlasSearchTarget.BUILD, "nebula", allSymbolIds),
                SearchCase(AtlasSearchTarget.PROJECT, "orion", allSymbolIds),
                SearchCase(
                    AtlasSearchTarget.PACKAGE,
                    "acme.gamma",
                    listOf(AtlasTypedFilterFixture.GAMMA_SYMBOL_ID),
                ),
                SearchCase(
                    AtlasSearchTarget.FILE,
                    "BetaPort.java",
                    listOf(AtlasTypedFilterFixture.BETA_SYMBOL_ID),
                ),
                SearchCase(
                    AtlasSearchTarget.CLASS,
                    "AlphaService",
                    listOf(AtlasTypedFilterFixture.ALPHA_SYMBOL_ID),
                ),
                SearchCase(
                    AtlasSearchTarget.SYMBOL,
                    "BetaPort",
                    listOf(AtlasTypedFilterFixture.BETA_SYMBOL_ID),
                ),
                SearchCase(
                    AtlasSearchTarget.METHOD,
                    "calculateOrbit",
                    listOf(AtlasTypedFilterFixture.GAMMA_SYMBOL_ID),
                ),
                SearchCase(
                    AtlasSearchTarget.EXTENSION,
                    ".java",
                    listOf(AtlasTypedFilterFixture.BETA_SYMBOL_ID),
                ),
            )

        cases.forEach { case ->
            assertContentEquals(case.expectedIds, symbolFrame(case.target, case.query).primaryNodeIds())
        }
    }

    @Test
    fun treatsAnEmptyTargetSetAsEverything() {
        assertContentEquals(
            listOf(AtlasTypedFilterFixture.GAMMA_SYMBOL_ID),
            symbolFrame(target = null, query = "acme.gamma").primaryNodeIds(),
        )
    }

    @Test
    fun choosesMixedPrimariesThatAgreeWithTheExplicitTargetType() {
        assertContentEquals(
            listOf(AtlasTypedFilterFixture.GAMMA_FILE_ID),
            fileFrame(AtlasSearchTarget.METHOD, "calculateOrbit").primaryNodeIds(),
        )
        assertContentEquals(
            listOf(AtlasTypedFilterFixture.GAMMA_SYMBOL_ID),
            problemFrame(AtlasSearchTarget.METHOD, "calculateOrbit").primaryNodeIds(),
        )
        assertContentEquals(
            listOf(AtlasTypedFilterFixture.BETA_FILE_ID),
            problemFrame(AtlasSearchTarget.FILE, "BetaPort.java").primaryNodeIds(),
        )
        assertContentEquals(
            listOf(AtlasTypedFilterFixture.ALPHA_SYMBOL_ID),
            problemFrame(AtlasSearchTarget.CLASS, "AlphaService").primaryNodeIds(),
        )
        assertContentEquals(
            listOf(AtlasTypedFilterFixture.BETA_SYMBOL_ID),
            problemFrame(AtlasSearchTarget.SYMBOL, "BetaPort").primaryNodeIds(),
        )
    }

    @Test
    fun normalizesPlainDottedAndWildcardExtensions() {
        val dotted = symbolFrame(AtlasSearchTarget.EXTENSION, ".kt").primaryNodeIds()

        assertContentEquals(dotted, symbolFrame(AtlasSearchTarget.EXTENSION, "kt").primaryNodeIds())
        assertContentEquals(dotted, symbolFrame(AtlasSearchTarget.EXTENSION, "*.KT").primaryNodeIds())
        assertContentEquals(
            listOf(
                AtlasTypedFilterFixture.ALPHA_SYMBOL_ID,
                AtlasTypedFilterFixture.GAMMA_SYMBOL_ID,
            ),
            dotted,
        )
    }
}

private data class SearchCase(
    val target: AtlasSearchTarget,
    val query: String,
    val expectedIds: List<String>,
)

private fun symbolFrame(
    target: AtlasSearchTarget?,
    query: String,
) = typedFilterFrame(AtlasLens.SYMBOLS, target, query)

private fun fileFrame(
    target: AtlasSearchTarget,
    query: String,
) = typedFilterFrame(AtlasLens.FILES, target, query)

private fun problemFrame(
    target: AtlasSearchTarget,
    query: String,
) = typedFilterFrame(AtlasLens.PROBLEMS, target, query)

private fun typedFilterFrame(
    lens: AtlasLens,
    target: AtlasSearchTarget?,
    query: String,
) =
    atlasProjectFrame(
        summary = AtlasTypedFilterFixture.summary,
        project = AtlasTypedFilterFixture.project,
        request =
            AtlasProjectFrameRequest(
                lens = lens,
                query = query,
                searchTargets = setOfNotNull(target),
            ),
    )

private val allSymbolIds =
    listOf(
        AtlasTypedFilterFixture.ALPHA_SYMBOL_ID,
        AtlasTypedFilterFixture.BETA_SYMBOL_ID,
        AtlasTypedFilterFixture.GAMMA_SYMBOL_ID,
    )
