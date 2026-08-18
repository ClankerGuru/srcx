package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class AtlasDrawAdapterTest :
    BehaviorSpec({
        given("the D3 adapter sidecar") {
            val adapter =
                WorkspaceHtmlResourceRenderer.readClasspathResource(
                    WorkspaceHtmlResourceRenderer.ATLAS_DRAW_SCRIPT,
                )

            then("it draws locked ringed file particles and not a 2-col filename header grid") {
                adapter shouldContain "srcx-dashboard__architecture-svg-node-ring is-importance-ring"
                adapter shouldContain "srcx-dashboard__architecture-svg-node-ring is-finding-ring"
                adapter shouldContain "srcx-dashboard__architecture-svg-node-ring is-cycle-ring"
                adapter shouldContain "srcx-dashboard__architecture-svg-node-ring is-analysis-cycle-ring"
                adapter shouldContain "srcx-dashboard__architecture-build-region-body"
                adapter shouldContain "function scatterParticlesInRoom"
                adapter shouldContain "forceSimulation"
                adapter shouldContain "forceManyBody"
                adapter shouldNotContain "function packLooseParticles"
                adapter shouldNotContain "function packLabeledSeedGrid"
                adapter shouldNotContain "mid - 14"
                adapter shouldNotContain "stepX"
                adapter shouldContain ".catch("
                adapter shouldContain "srcxAtlasSqliteBase64"
                adapter shouldNotContain "JSON.parse"
            }
        }
    })
