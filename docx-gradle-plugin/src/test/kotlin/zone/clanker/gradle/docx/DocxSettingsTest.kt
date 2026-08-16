package zone.clanker.gradle.docx

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder

class DocxSettingsTest :
    BehaviorSpec({
        given("the default DOCX settings") {
            val extension = newExtension()

            `when`("the analysis plan is materialized") {
                val plan = extension.analysisPlan()

                then("the full preset enables every feature") {
                    plan.preset shouldBe DocxPreset.FULL
                    plan.features.map(DocxFeaturePlan::feature) shouldContainExactly
                        DocxFeature.entries.sortedBy(Enum<*>::name)
                    plan.features.all(DocxFeaturePlan::enabled) shouldBe true
                }

                then("the default scope is complete") {
                    plan.scope.builds.all shouldBe true
                    plan.scope.projects.all shouldBe true
                    plan.scope.sourceSets.all shouldBe true
                    plan.scope.includeTests shouldBe true
                    plan.missingCapabilities shouldBe MissingCapabilityPolicy.WARN
                }

                then("the producer snapshot is the only cross-plugin input") {
                    plan.output.snapshotFile shouldBe Docx.DEFAULT_SNAPSHOT_FILE
                    plan.output.formats shouldContainExactly listOf(DocxOutputFormat.HTML)
                }

                then("the update policy is conservative and does not start transport") {
                    plan.updates.mode shouldBe DocxUpdateMode.ASYNC
                    plan.updates.liveReload shouldBe true
                    (plan.updates.maxParallelism in 1..4) shouldBe true
                    plan.updates.debounceMilliseconds shouldBe 250L
                }
            }
        }

        given("a focused architecture request") {
            val extension = newExtension()
            extension.preset.set(DocxPreset.NONE)
            extension.output {
                directory.set("build/documentation")
                formats.set(setOf(DocxOutputFormat.HTML))
            }
            extension.scope {
                builds.include("mosaic")
                projects.include(":mosaic-runtime")
                sourceSets.include("commonMain")
                includeTests.set(false)
                build("mosaic") {
                    projects.include(":mosaic-runtime")
                    sourceSets.include("commonMain", "jvmMain")
                    includeTests.set(false)
                }
            }
            extension.features {
                symbols {
                    enabled.set(true)
                    hops(2)
                    includeDisconnected.set(false)
                }
                relationships {
                    enabled.set(true)
                    hops(1)
                }
            }

            `when`("the request is converted before analysis") {
                val plan = extension.analysisPlan()

                then("scope selections are exact and deterministic") {
                    plan.scope.builds shouldBe NameSelectionPlan(false, listOf("mosaic"), emptyList())
                    plan.scope.projects shouldBe NameSelectionPlan(false, listOf(":mosaic-runtime"), emptyList())
                    plan.scope.sourceSets shouldBe NameSelectionPlan(false, listOf("commonMain"), emptyList())
                    plan.scope.configuredBuilds
                        .single()
                        .name shouldBe "mosaic"
                    plan.scope.configuredBuilds
                        .single()
                        .sourceSets.included shouldContainExactly
                        listOf("commonMain", "jvmMain")
                }

                then("only requested analysis capabilities are scheduled") {
                    plan.features.filter(DocxFeaturePlan::enabled).map(DocxFeaturePlan::feature) shouldContainExactly
                        listOf(DocxFeature.RELATIONSHIPS, DocxFeature.SYMBOLS)
                    plan.requiredCapabilities shouldContainExactly
                        listOf(AnalysisCapability.RELATIONSHIPS, AnalysisCapability.SYMBOLS)
                }

                then("graph depth is represented without a renderer limit") {
                    val symbols = plan.features.single { it.feature == DocxFeature.SYMBOLS }
                    symbols.depth shouldBe GraphDepthPlan(GraphDepthMode.HOPS, 2)
                    symbols.includeDisconnected shouldBe false
                }
            }
        }

        given("contradictory scope selection") {
            val extension = newExtension()
            extension.scope.builds.include("mosaic")
            extension.scope.builds.exclude("mosaic")

            `when`("the plan is materialized") {
                then("the invalid request is rejected before scanning") {
                    shouldThrow<IllegalArgumentException> { extension.analysisPlan() }
                }
            }
        }

        given("every built-in preset") {
            `when`("defaults are projected into feature plans") {
                val enabledFeatures =
                    DocxPreset.entries.associateWith { preset ->
                        newExtension()
                            .apply { this.preset.set(preset) }
                            .analysisPlan()
                            .features
                            .filter(DocxFeaturePlan::enabled)
                            .mapTo(mutableSetOf(), DocxFeaturePlan::feature)
                    }

                then("each preset expresses its intended analysis budget") {
                    enabledFeatures.getValue(DocxPreset.FINDINGS) shouldBe
                        setOf(
                            DocxFeature.WORKSPACE,
                            DocxFeature.FINDINGS,
                            DocxFeature.FILES,
                            DocxFeature.SOURCES,
                        )
                    enabledFeatures.getValue(DocxPreset.ARCHITECTURE) shouldBe
                        setOf(
                            DocxFeature.WORKSPACE,
                            DocxFeature.FILES,
                            DocxFeature.SYMBOLS,
                            DocxFeature.RELATIONSHIPS,
                            DocxFeature.CYCLES,
                            DocxFeature.SOURCES,
                        )
                    enabledFeatures.getValue(DocxPreset.DEPENDENCY_INJECTION) shouldBe
                        setOf(
                            DocxFeature.WORKSPACE,
                            DocxFeature.FILES,
                            DocxFeature.SYMBOLS,
                            DocxFeature.RELATIONSHIPS,
                            DocxFeature.DEPENDENCY_INJECTION,
                            DocxFeature.SOURCES,
                        )
                    enabledFeatures.getValue(DocxPreset.NONE) shouldBe emptySet()
                }
            }
        }

        given("the nested feature helpers") {
            val extension = newExtension()
            extension.features {
                workspace { enabled.set(false) }
                findings { severities.set(setOf(FindingSeveritySelection.FORBIDDEN)) }
                files { enabled.set(false) }
                symbols { full() }
                relationships {
                    full()
                    relationshipKinds.set(
                        setOf(
                            RelationshipKindSelection.CALL,
                            RelationshipKindSelection.CONSTRUCTOR,
                        ),
                    )
                }
                cycles { full() }
                dependencyInjection {
                    frameworks.set(setOf(DependencyInjectionFrameworkSelection.HILT))
                    generatedSources.set(GeneratedSourcePolicy.ONLY)
                }
                sources {
                    enabled.set(true)
                    includeContent.set(false)
                }
            }

            `when`("the feature plan is materialized") {
                val plan = extension.analysisPlan()

                then("all typed options survive without renderer limits") {
                    plan.features.single { it.feature == DocxFeature.WORKSPACE }.enabled shouldBe false
                    plan.features.single { it.feature == DocxFeature.FILES }.enabled shouldBe false
                    plan.features.single { it.feature == DocxFeature.FINDINGS }.severities shouldContainExactly
                        listOf(FindingSeveritySelection.FORBIDDEN)
                    plan.features
                        .single { it.feature == DocxFeature.DEPENDENCY_INJECTION }
                        .dependencyInjectionFrameworks shouldContainExactly
                        listOf(DependencyInjectionFrameworkSelection.HILT)
                    plan.features
                        .single { it.feature == DocxFeature.DEPENDENCY_INJECTION }
                        .generatedSources shouldBe GeneratedSourcePolicy.ONLY
                    plan.features
                        .single { it.feature == DocxFeature.RELATIONSHIPS }
                        .relationshipKinds shouldContainExactly
                        listOf(RelationshipKindSelection.CALL, RelationshipKindSelection.CONSTRUCTOR)
                    plan.features.single { it.feature == DocxFeature.SOURCES }.includeSourceContent shouldBe false
                    plan.requiredCapabilities shouldContainExactly
                        listOf(
                            AnalysisCapability.CYCLES,
                            AnalysisCapability.DEPENDENCY_INJECTION,
                            AnalysisCapability.FINDINGS,
                            AnalysisCapability.RELATIONSHIPS,
                            AnalysisCapability.SOURCE_METADATA,
                            AnalysisCapability.SYMBOLS,
                        )
                }
            }
        }

        given("invalid graph and output options") {
            `when`("a graph uses a non-positive hop count") {
                then("configuration rejects it immediately") {
                    shouldThrow<IllegalArgumentException> { newExtension().features.symbols.hops(0) }
                }
            }

            `when`("an output path escapes the root") {
                then("plan construction rejects it") {
                    shouldThrow<IllegalArgumentException> {
                        newExtension()
                            .apply { output.directory.set("../outside") }
                            .analysisPlan()
                    }
                }
            }

            `when`("Markdown is selected with HTML") {
                then("configuration rejects an output it cannot produce truthfully") {
                    shouldThrow<IllegalArgumentException> {
                        newExtension()
                            .apply {
                                output.formats.set(
                                    setOf(DocxOutputFormat.HTML, DocxOutputFormat.MARKDOWN),
                                )
                            }.analysisPlan()
                    }.message shouldContain "exactly [HTML]"
                }
            }

            `when`("Markdown is the only selected format") {
                then("configuration rejects an output it cannot produce truthfully") {
                    shouldThrow<IllegalArgumentException> {
                        newExtension()
                            .apply { output.formats.set(setOf(DocxOutputFormat.MARKDOWN)) }
                            .analysisPlan()
                    }.message shouldContain "Markdown output is not implemented"
                }
            }

            `when`("the output directory contains the configured snapshot") {
                then("configuration protects the producer input") {
                    shouldThrow<IllegalArgumentException> {
                        newExtension()
                            .apply { output.directory.set(".srcx") }
                            .analysisPlan()
                    }.message shouldContain "must not contain SRCX snapshot"
                }
            }

            `when`("a publication sibling contains the configured snapshot") {
                then("configuration protects the producer input from cleanup recovery") {
                    shouldThrow<IllegalArgumentException> {
                        newExtension()
                            .apply { output.snapshotFile.set(".docx.staging/workspace-report.json") }
                            .analysisPlan()
                    }.message shouldContain "DOCX staging directory"
                }
            }
        }

        given("a strict update policy") {
            val extension = newExtension()
            extension.updates {
                mode.set(DocxUpdateMode.STRICT)
                liveReload.set(false)
                maxParallelism.set(2)
                debounceMilliseconds.set(0L)
            }

            `when`("the plan crosses the Kotlin serialization boundary") {
                val plan = extension.analysisPlan()
                val encoded = DocxAnalysisPlanJson.encode(plan)

                then("every update option survives exactly") {
                    DocxAnalysisPlanJson.decode(encoded).updates shouldBe
                        DocxUpdatePlan(
                            mode = DocxUpdateMode.STRICT,
                            liveReload = false,
                            maxParallelism = 2,
                            debounceMilliseconds = 0L,
                        )
                }

                then("future fields remain compatible") {
                    val withFutureField = encoded.replaceFirst("{", "{\n  \"futureField\": true,")
                    DocxAnalysisPlanJson.decode(withFutureField) shouldBe plan
                }

                then("serialization is byte-deterministic") {
                    DocxAnalysisPlanJson.encode(DocxAnalysisPlanJson.decode(encoded)) shouldBe encoded
                }
            }
        }

        given("the conservative parallelism calculation") {
            `when`("processors exceed the memory budget") {
                then("memory limits the worker count") {
                    defaultMaxParallelism(processors = 16, maxMemoryBytes = 256L * MEBIBYTE) shouldBe 1
                    defaultMaxParallelism(processors = 16, maxMemoryBytes = 1_024L * MEBIBYTE) shouldBe 2
                }
            }

            `when`("memory exceeds the processor budget and safety cap") {
                then("processors and the fixed upper bound still apply") {
                    defaultMaxParallelism(processors = 2, maxMemoryBytes = 8_192L * MEBIBYTE) shouldBe 2
                    defaultMaxParallelism(processors = 32, maxMemoryBytes = 8_192L * MEBIBYTE) shouldBe 4
                }
            }

            `when`("the runtime reports invalid limits") {
                then("configuration rejects them") {
                    shouldThrow<IllegalArgumentException> {
                        defaultMaxParallelism(processors = 0, maxMemoryBytes = MEBIBYTE)
                    }
                    shouldThrow<IllegalArgumentException> {
                        defaultMaxParallelism(processors = 1, maxMemoryBytes = 0)
                    }
                }
            }
        }

        given("invalid update and decoded plan state") {
            `when`("update parallelism is not positive") {
                val extension = newExtension().apply { updates.maxParallelism.set(0) }

                then("plan construction rejects it") {
                    shouldThrow<IllegalArgumentException> { extension.analysisPlan() }
                }
            }

            `when`("update debounce is negative") {
                val extension = newExtension().apply { updates.debounceMilliseconds.set(-1L) }

                then("plan construction rejects it") {
                    shouldThrow<IllegalArgumentException> { extension.analysisPlan() }
                }
            }

            `when`("decoded features are duplicated") {
                val plan = newExtension().analysisPlan()

                then("the serialized boundary rejects ambiguity") {
                    shouldThrow<IllegalArgumentException> {
                        plan.copy(features = plan.features + plan.features.last())
                    }
                }
            }

            `when`("no output format is selected") {
                val plan = newExtension().analysisPlan()

                then("the serialized boundary rejects an unusable output") {
                    shouldThrow<IllegalArgumentException> {
                        plan.copy(output = plan.output.copy(formats = emptyList()))
                    }.message shouldContain "exactly [HTML]"
                }
            }
        }
    })

private fun newExtension(): DocxSettingsExtension =
    ProjectBuilder
        .builder()
        .build()
        .objects
        .newInstance(DocxSettingsExtension::class.java)

private const val MEBIBYTE: Long = 1024L * 1024
