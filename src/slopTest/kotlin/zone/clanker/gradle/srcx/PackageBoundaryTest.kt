package zone.clanker.gradle.srcx

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

/**
 * Enforces directional import boundaries between packages.
 *
 * The dependency direction is:
 * ```
 * model      -> (nothing internal -- models are leaf nodes)
 * parse      -> model (parsers produce model types)
 * analysis   -> model (analyzers consume model types)
 * task       -> model, parse, analysis, report (tasks orchestrate everything)
 * report     -> model (renderers consume models)
 * ```
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

                then("models never import from the parse package") {
                    modelFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.parse") }
                    }
                }

                then("models never import from the analysis package") {
                    modelFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.analysis") }
                    }
                }

                then("models never import from the task package") {
                    modelFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.task") }
                    }
                }

                then("models never import from the report package") {
                    modelFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.report") }
                    }
                }
            }

            `when`("files are in the parse package") {
                val parseFiles =
                    mainScope.files.filter {
                        it.packagee?.name?.contains("srcx.parse") == true
                    }

                then("parse never imports from the task package") {
                    parseFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.task") }
                    }
                }

                then("parse never imports from the report package") {
                    parseFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.report") }
                    }
                }
            }

            `when`("files are in the analysis package") {
                val analysisFiles =
                    mainScope.files.filter {
                        it.packagee?.name?.contains("srcx.analysis") == true
                    }

                then("analysis never imports from the task package") {
                    analysisFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.task") }
                    }
                }

                then("analysis never imports from the report package") {
                    analysisFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.report") }
                    }
                }
            }

            `when`("files are in the report package") {
                val reportFiles =
                    mainScope.files.filter {
                        it.packagee?.name?.contains("srcx.report") == true
                    }

                then("report never imports from the parse package") {
                    reportFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.parse") }
                    }
                }

                then("report never imports from the task package") {
                    reportFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.task") }
                    }
                }

                then("report never imports from the analysis package") {
                    reportFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("srcx.analysis") }
                    }
                }
            }
        }
    })
