package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class FlowRendererTest :
    BehaviorSpec({

        given("a FlowRenderer") {

            `when`("rendering a single flow") {
                val diagram =
                    """
                    ```mermaid
                    sequenceDiagram
                        participant A as AppController
                        participant B as UserService
                        A->>B:
                        B-->>A:
                    ```
                    """.trimIndent()
                val renderer = FlowRenderer("AppController", diagram)
                val output = renderer.render()

                then("it contains the header and diagram") {
                    output shouldContain "# AppController Flow"
                    output shouldContain "sequenceDiagram"
                    output shouldContain "participant A as AppController"
                }
            }
        }

        given("FlowRenderer.splitDiagrams companion") {

            `when`("splitting an empty string") {
                val result = FlowRenderer.splitDiagrams("")

                then("it returns an empty list") {
                    result shouldHaveSize 0
                }
            }

            `when`("splitting a combined diagram output") {
                val combined =
                    """
                    ### AppController Flow

                    ```mermaid
                    sequenceDiagram
                        participant A as AppController
                    ```

                    ### UserService Flow

                    ```mermaid
                    sequenceDiagram
                        participant B as UserService
                    ```

                    """.trimIndent()
                val result = FlowRenderer.splitDiagrams(combined)

                then("it splits into separate entries") {
                    result shouldHaveSize 2
                    result[0].first shouldBe "AppController"
                    result[0].second shouldContain "participant A as AppController"
                    result[1].first shouldBe "UserService"
                    result[1].second shouldContain "participant B as UserService"
                }
            }

            `when`("splitting a single diagram") {
                val single =
                    """
                    ### MainApp Flow

                    ```mermaid
                    sequenceDiagram
                        participant M as MainApp
                    ```
                    """.trimIndent()
                val result = FlowRenderer.splitDiagrams(single)

                then("it returns one entry") {
                    result shouldHaveSize 1
                    result[0].first shouldBe "MainApp"
                }
            }

            `when`("splitting a blank string with whitespace") {
                val result = FlowRenderer.splitDiagrams("   \n   \n  ")

                then("it returns an empty list") {
                    result shouldHaveSize 0
                }
            }

            `when`("content before first header is ignored") {
                val combined =
                    """
                    Some preamble text that is not a header.
                    Another line before headers.
                    ### FirstFlow Flow

                    ```mermaid
                    sequenceDiagram
                        participant X as FirstFlow
                    ```
                    """.trimIndent()
                val result = FlowRenderer.splitDiagrams(combined)

                then("it only captures content after the header") {
                    result shouldHaveSize 1
                    result[0].first shouldBe "FirstFlow"
                    result[0].second shouldContain "participant X as FirstFlow"
                }
            }

            `when`("header with no content following is skipped") {
                val combined =
                    """
                    ### EmptySection Flow
                    ### RealSection Flow

                    some content here
                    """.trimIndent()
                val result = FlowRenderer.splitDiagrams(combined)

                then("it skips headers with blank content") {
                    result shouldHaveSize 1
                    result[0].first shouldBe "RealSection"
                }
            }
        }
    })
