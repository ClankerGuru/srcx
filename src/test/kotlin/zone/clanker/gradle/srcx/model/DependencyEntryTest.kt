package zone.clanker.gradle.srcx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Tests for [DependencyEntry] data class and value class validations.
 */
class DependencyEntryTest :
    BehaviorSpec({

        given("a DependencyEntry") {

            `when`("created with valid data") {
                val entry =
                    DependencyEntry(
                        group = ArtifactGroup("org.jetbrains.kotlin"),
                        artifact = ArtifactName("kotlin-stdlib"),
                        version = ArtifactVersion("2.1.20"),
                        scope = "implementation",
                    )

                then("all fields are accessible") {
                    entry.group.value shouldBe "org.jetbrains.kotlin"
                    entry.artifact.value shouldBe "kotlin-stdlib"
                    entry.version.value shouldBe "2.1.20"
                    entry.scope shouldBe "implementation"
                }
            }

            `when`("two entries have the same values") {
                val entry1 =
                    DependencyEntry(ArtifactGroup("com.foo"), ArtifactName("bar"), ArtifactVersion("1.0"), "api")
                val entry2 =
                    DependencyEntry(ArtifactGroup("com.foo"), ArtifactName("bar"), ArtifactVersion("1.0"), "api")

                then("they are equal") {
                    entry1 shouldBe entry2
                }
            }

            `when`("version is unspecified") {
                val entry =
                    DependencyEntry(
                        ArtifactGroup("com.foo"),
                        ArtifactName("bar"),
                        ArtifactVersion("unspecified"),
                        "implementation",
                    )

                then("version is 'unspecified'") {
                    entry.version.value shouldBe "unspecified"
                }
            }

            `when`("scope is blank") {
                then("it throws IllegalArgumentException") {
                    val ex =
                        shouldThrow<IllegalArgumentException> {
                            DependencyEntry(ArtifactGroup("com.foo"), ArtifactName("bar"), ArtifactVersion("1.0"), "")
                        }
                    ex.message shouldContain "scope must not be blank"
                }
            }

            `when`("scope is whitespace-only") {
                then("it throws IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        DependencyEntry(ArtifactGroup("com.foo"), ArtifactName("bar"), ArtifactVersion("1.0"), "   ")
                    }
                }
            }
        }

        given("ArtifactGroup value class") {

            `when`("created with valid input") {
                then("it stores the value") {
                    ArtifactGroup("org.jetbrains.kotlin").value shouldBe "org.jetbrains.kotlin"
                }
            }

            `when`("toString is called") {
                then("it returns the raw value") {
                    ArtifactGroup("org.jetbrains.kotlin").toString() shouldBe "org.jetbrains.kotlin"
                }
            }

            `when`("created with blank input") {
                then("it throws IllegalArgumentException") {
                    val ex =
                        shouldThrow<IllegalArgumentException> {
                            ArtifactGroup("")
                        }
                    ex.message shouldContain "ArtifactGroup must not be blank"
                }
            }

            `when`("created with whitespace-only input") {
                then("it throws IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        ArtifactGroup("   ")
                    }
                }
            }

            `when`("created with input containing whitespace") {
                then("it throws IllegalArgumentException") {
                    val ex =
                        shouldThrow<IllegalArgumentException> {
                            ArtifactGroup("org. jetbrains")
                        }
                    ex.message shouldContain "ArtifactGroup must not contain whitespace"
                }
            }
        }

        given("ArtifactName value class") {

            `when`("created with valid input") {
                then("it stores the value") {
                    ArtifactName("kotlin-stdlib").value shouldBe "kotlin-stdlib"
                }
            }

            `when`("toString is called") {
                then("it returns the raw value") {
                    ArtifactName("kotlin-stdlib").toString() shouldBe "kotlin-stdlib"
                }
            }

            `when`("created with blank input") {
                then("it throws IllegalArgumentException") {
                    val ex =
                        shouldThrow<IllegalArgumentException> {
                            ArtifactName("")
                        }
                    ex.message shouldContain "ArtifactName must not be blank"
                }
            }

            `when`("created with whitespace-only input") {
                then("it throws IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        ArtifactName("   ")
                    }
                }
            }
        }

        given("ArtifactVersion value class") {

            `when`("created with valid input") {
                then("it stores the value") {
                    ArtifactVersion("2.1.20").value shouldBe "2.1.20"
                }
            }

            `when`("toString is called") {
                then("it returns the raw value") {
                    ArtifactVersion("2.1.20").toString() shouldBe "2.1.20"
                }
            }

            `when`("created with blank input") {
                then("it throws IllegalArgumentException") {
                    val ex =
                        shouldThrow<IllegalArgumentException> {
                            ArtifactVersion("")
                        }
                    ex.message shouldContain "ArtifactVersion must not be blank"
                }
            }

            `when`("created with whitespace-only input") {
                then("it throws IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        ArtifactVersion("   ")
                    }
                }
            }
        }
    })
