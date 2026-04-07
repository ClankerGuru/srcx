package zone.clanker.gradle.srcx.extractor

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.gradle.testfixtures.ProjectBuilder

/**
 * Tests for [DependencyExtractor] project dependency extraction.
 */
class DependencyExtractorTest :
    BehaviorSpec({

        given("a DependencyExtractor") {

            `when`("project has implementation dependencies") {
                val project = ProjectBuilder.builder().build()
                project.pluginManager.apply("java-library")
                project.repositories.mavenCentral()
                project.dependencies.add("implementation", "org.jetbrains.kotlin:kotlin-stdlib:2.1.20")

                val extractor = DependencyExtractor(project)
                val deps = extractor.extract()

                then("it extracts the dependency") {
                    deps shouldHaveSize 1
                    deps[0].group shouldBe "org.jetbrains.kotlin"
                    deps[0].artifact shouldBe "kotlin-stdlib"
                    deps[0].version shouldBe "2.1.20"
                    deps[0].scope shouldBe "implementation"
                }
            }

            `when`("project has multiple scoped dependencies") {
                val project = ProjectBuilder.builder().build()
                project.pluginManager.apply("java-library")
                project.repositories.mavenCentral()
                project.dependencies.add("api", "com.google.guava:guava:33.0.0-jre")
                project.dependencies.add("implementation", "org.slf4j:slf4j-api:2.0.9")
                project.dependencies.add("compileOnly", "org.projectlombok:lombok:1.18.30")

                val extractor = DependencyExtractor(project)
                val deps = extractor.extract()

                then("it extracts all scoped dependencies") {
                    deps shouldHaveSize 3
                    deps.map { it.scope }.toSet() shouldBe setOf("api", "implementation", "compileOnly")
                }
            }

            `when`("project has no dependencies") {
                val project = ProjectBuilder.builder().build()
                project.pluginManager.apply("java-library")

                val extractor = DependencyExtractor(project)
                val deps = extractor.extract()

                then("it returns empty list") {
                    deps.shouldBeEmpty()
                }
            }

            `when`("dependency has no version") {
                val project = ProjectBuilder.builder().build()
                project.pluginManager.apply("java-library")
                project.dependencies.add("implementation", "com.foo:bar")

                val extractor = DependencyExtractor(project)
                val deps = extractor.extract()

                then("version is 'unspecified'") {
                    deps shouldHaveSize 1
                    deps[0].version shouldBe "unspecified"
                }
            }
        }
    })
