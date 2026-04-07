package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

class SourceFileMetadataTest :
    BehaviorSpec({

        fun tempFile(name: String, ext: String, content: String): File =
            File.createTempFile(name, ".$ext").apply {
                writeText(content)
                deleteOnExit()
            }

        given("parseSourceFile") {
            `when`("kotlin file with class") {
                val file =
                    tempFile(
                        "MyService", "kt",
                        """
                        package com.example

                        import com.other.Dependency

                        @Service
                        class MyService : BaseService {
                            fun process(): String = "done"
                            private fun helper() {}
                        }
                        """.trimIndent(),
                    )

                val metadata = parseSourceFile(file)

                then("it parses package name") {
                    metadata shouldNotBe null
                    metadata?.packageName shouldBe "com.example"
                }

                then("it parses class name") {
                    metadata?.simpleName shouldBe "MyService"
                    metadata?.qualifiedName shouldBe "com.example.MyService"
                }

                then("it parses imports (excluding platform)") {
                    metadata!!.imports shouldContain "com.other.Dependency"
                }

                then("it parses annotations") {
                    metadata!!.annotations shouldContain "Service"
                }

                then("it parses supertypes") {
                    metadata!!.supertypes shouldContain "BaseService"
                }

                then("it parses methods") {
                    metadata!!.methods shouldContain "process"
                    metadata!!.methods shouldContain "helper"
                }

                then("it detects language") {
                    metadata?.language shouldBe SourceFileMetadata.Language.KOTLIN
                }

                file.delete()
            }

            `when`("kotlin data class") {
                val file =
                    tempFile(
                        "Config", "kt",
                        """
                        package com.example

                        data class Config(val name: String)
                        """.trimIndent(),
                    )

                val metadata = parseSourceFile(file)

                then("it detects data class") {
                    metadata?.isDataClass shouldBe true
                }

                file.delete()
            }

            `when`("kotlin interface") {
                val file =
                    tempFile(
                        "Repository", "kt",
                        """
                        package com.example

                        interface Repository {
                            fun find(): String
                        }
                        """.trimIndent(),
                    )

                val metadata = parseSourceFile(file)

                then("it detects interface") {
                    metadata?.isInterface shouldBe true
                }

                file.delete()
            }

            `when`("kotlin object") {
                val file =
                    tempFile(
                        "Singleton", "kt",
                        """
                        package com.example

                        object Singleton
                        """.trimIndent(),
                    )

                val metadata = parseSourceFile(file)

                then("it detects object") {
                    metadata?.isObject shouldBe true
                }

                file.delete()
            }

            `when`("java file") {
                val file =
                    tempFile(
                        "JavaService", "java",
                        """
                        package com.example;

                        import com.other.Dependency;

                        public class JavaService {
                            public void process() {}
                        }
                        """.trimIndent(),
                    )

                val metadata = parseSourceFile(file)

                then("it detects java language") {
                    metadata?.language shouldBe SourceFileMetadata.Language.JAVA
                }

                then("it parses class name") {
                    metadata?.simpleName shouldBe "JavaService"
                }

                file.delete()
            }

            `when`("file has no class declaration") {
                val file =
                    tempFile(
                        "NoClass", "kt",
                        """
                        package com.example

                        fun topLevel() {}
                        """.trimIndent(),
                    )

                val metadata = parseSourceFile(file)

                then("it uses filename as class name") {
                    metadata?.simpleName shouldBe file.nameWithoutExtension
                }

                file.delete()
            }

            `when`("file is not a source file") {
                val file = tempFile("readme", "md", "# Readme")

                val metadata = parseSourceFile(file)

                then("it returns null") {
                    metadata shouldBe null
                }

                file.delete()
            }

            `when`("file does not exist") {
                val metadata = parseSourceFile(File("/nonexistent/file.kt"))

                then("it returns null") {
                    metadata shouldBe null
                }
            }
        }

        given("scanSources") {
            `when`("directory has source files") {
                val dir =
                    File.createTempFile("src", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                dir.resolve("A.kt").writeText("package com.example\nclass A")
                dir.resolve("B.kt").writeText("package com.example\nclass B")

                val results = scanSources(listOf(dir))

                then("it parses all files") {
                    results shouldHaveSize 2
                }

                dir.deleteRecursively()
            }

            `when`("directory does not exist") {
                val results = scanSources(listOf(File("/nonexistent")))

                then("it returns empty list") {
                    results.shouldBeEmpty()
                }
            }
        }
    })
