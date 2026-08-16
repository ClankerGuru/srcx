package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

class EntryPointsRendererTest :
    BehaviorSpec({

        given("an EntryPointsRenderer") {

            `when`("rendering with app entry points") {
                val entries =
                    listOf(
                        EntryPointsRenderer.ClassifiedEntry(
                            "AppController",
                            "com.example.app",
                            EntryPointsRenderer.EntryKind.APP,
                        ),
                    )
                val renderer = EntryPointsRenderer(entries)
                val output = renderer.render()

                then("it shows app entry points") {
                    output shouldContain "## App Entry Points"
                    output shouldContain "| `AppController` | com.example.app |"
                }
            }

            `when`("rendering with test classes") {
                val entries =
                    listOf(
                        EntryPointsRenderer.ClassifiedEntry(
                            "AppTest",
                            "com.example",
                            EntryPointsRenderer.EntryKind.TEST,
                        ),
                        EntryPointsRenderer.ClassifiedEntry(
                            "ServiceSpec",
                            "com.example",
                            EntryPointsRenderer.EntryKind.TEST,
                        ),
                    )
                val renderer = EntryPointsRenderer(entries)
                val output = renderer.render()

                then("it shows test entry points") {
                    output shouldContain "## Test Entry Points"
                    output shouldContain "| `AppTest` | com.example |"
                    output shouldContain "| `ServiceSpec` | com.example |"
                }
            }

            `when`("rendering with test doubles") {
                val entries =
                    listOf(
                        EntryPointsRenderer.ClassifiedEntry(
                            "MockRepository",
                            "com.example",
                            EntryPointsRenderer.EntryKind.MOCK,
                        ),
                        EntryPointsRenderer.ClassifiedEntry(
                            "FakeService",
                            "com.example",
                            EntryPointsRenderer.EntryKind.MOCK,
                        ),
                    )
                val renderer = EntryPointsRenderer(entries)
                val output = renderer.render()

                then("it shows test doubles") {
                    output shouldContain "## Test Doubles"
                    output shouldContain "| `MockRepository` | com.example |"
                    output shouldContain "| `FakeService` | com.example |"
                }
            }

            `when`("rendering with mixed entry points") {
                val entries =
                    listOf(
                        EntryPointsRenderer.ClassifiedEntry(
                            "AppController",
                            "com.example",
                            EntryPointsRenderer.EntryKind.APP,
                        ),
                        EntryPointsRenderer.ClassifiedEntry(
                            "AppControllerTest",
                            "com.example",
                            EntryPointsRenderer.EntryKind.TEST,
                        ),
                        EntryPointsRenderer.ClassifiedEntry(
                            "MockService",
                            "com.example",
                            EntryPointsRenderer.EntryKind.MOCK,
                        ),
                    )
                val renderer = EntryPointsRenderer(entries)
                val output = renderer.render()

                then("separates entries by kind") {
                    output shouldContain "## App Entry Points"
                    output shouldContain "| `AppController` | com.example |"
                    output shouldContain "## Test Entry Points"
                    output shouldContain "| `AppControllerTest` | com.example |"
                    output shouldContain "## Test Doubles"
                    output shouldContain "| `MockService` | com.example |"
                }
            }

            `when`("rendering with no data") {
                val renderer = EntryPointsRenderer(emptyList())
                val output = renderer.render()

                then("it shows no-data messages") {
                    output shouldContain "None detected."
                }
            }
        }
    })
