package zone.clanker.gradle.srcx.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [DependencyEntry] data class behavior.
 */
class DependencyEntryTest :
    BehaviorSpec({

        given("a DependencyEntry") {

            `when`("created with valid data") {
                val entry =
                    DependencyEntry(
                        group = "org.jetbrains.kotlin",
                        artifact = "kotlin-stdlib",
                        version = "2.1.20",
                        scope = "implementation",
                    )

                then("all fields are accessible") {
                    entry.group shouldBe "org.jetbrains.kotlin"
                    entry.artifact shouldBe "kotlin-stdlib"
                    entry.version shouldBe "2.1.20"
                    entry.scope shouldBe "implementation"
                }
            }

            `when`("two entries have the same values") {
                val entry1 = DependencyEntry("com.foo", "bar", "1.0", "api")
                val entry2 = DependencyEntry("com.foo", "bar", "1.0", "api")

                then("they are equal") {
                    entry1 shouldBe entry2
                }
            }

            `when`("version is unspecified") {
                val entry = DependencyEntry("com.foo", "bar", "unspecified", "implementation")

                then("version is 'unspecified'") {
                    entry.version shouldBe "unspecified"
                }
            }
        }
    })
