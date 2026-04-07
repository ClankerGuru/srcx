package zone.clanker.gradle.srcx

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

/**
 * Enforces directional import boundaries between packages.
 *
 * The dependency direction is:
 * ```
 * extractor → model (extractors consume models)
 * report → model (renderers consume models)
 * model → (nothing internal — models are leaf nodes)
 * ```
 *
 * Models must never depend on extractors or reports.
 * Extractors may depend on models but not on reports.
 * This prevents circular dependencies and keeps the model layer pure.
 */
class PackageBoundaryTest :
    BehaviorSpec({

        val mainScope = Konsist.scopeFromSourceSet("main")

        given("import direction enforcement") {

            `when`("files are in the model package") {
                val modelFiles =
                    mainScope.files.filter {
                        it.packagee?.name?.contains("srcx.model") == true
                    }

                then("models never import from the extractor package") {
                    modelFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.extractor") }
                    }
                }

                then("models never import from the report package") {
                    modelFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.report") }
                    }
                }
            }

            `when`("files are in the extractor package") {
                val extractorFiles =
                    mainScope.files.filter {
                        it.packagee?.name?.contains("srcx.extractor") == true
                    }

                then("extractors never import from the report package") {
                    extractorFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.report") }
                    }
                }
            }
        }
    })
