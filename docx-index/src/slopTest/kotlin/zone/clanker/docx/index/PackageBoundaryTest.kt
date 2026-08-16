package zone.clanker.docx.index

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class PackageBoundaryTest :
    BehaviorSpec({
        val mainScope = Konsist.scopeFromSourceSet("main")
        val indexFiles =
            mainScope.files.filter { file ->
                file.packagee?.name?.startsWith(INDEX_PACKAGE) == true
            }

        given("a layered DOCX workspace index library") {
            then("the public root package contains only the facade and public model") {
                indexFiles
                    .filter { file -> file.packagee?.name == INDEX_PACKAGE }
                    .map { file -> file.name }
                    .toSet() shouldBe ROOT_FILES
            }

            then("database code is independent of higher layers") {
                indexFiles
                    .filter { file -> file.packagee?.name == DATABASE_PACKAGE }
                    .assertTrue { file ->
                        file.imports.none { dependency ->
                            dependency.name.startsWith(INDEX_PACKAGE) &&
                                !dependency.name.startsWith(DATABASE_PACKAGE)
                        }
                    }
            }

            then("generation code never depends on importing or query code") {
                indexFiles
                    .filter { file -> file.packagee?.name == GENERATION_PACKAGE }
                    .assertTrue { file ->
                        file.imports.none { dependency -> dependency.name.hasAnyPrefix(FORBIDDEN_GENERATION_IMPORTS) }
                    }
            }

            then("importing and query code remain independent peers") {
                indexFiles
                    .filter { file -> file.packagee?.name == IMPORTING_PACKAGE }
                    .assertTrue { file ->
                        file.imports.none { dependency -> dependency.name.startsWith(QUERY_PACKAGE) }
                    }
                indexFiles
                    .filter { file -> file.packagee?.name == QUERY_PACKAGE }
                    .assertTrue { file ->
                        file.imports.none { dependency -> dependency.name.hasAnyPrefix(FORBIDDEN_QUERY_IMPORTS) }
                    }
            }

            then("production files stay inside the declared index packages") {
                indexFiles.assertTrue { file ->
                    file.packagee?.name?.let(ALLOWED_PACKAGES::contains) == true
                }
            }
        }
    })

private fun String.hasAnyPrefix(prefixes: Set<String>): Boolean = prefixes.any { prefix -> startsWith(prefix) }

private const val INDEX_PACKAGE: String = "zone.clanker.docx.index"
private const val DATABASE_PACKAGE: String = "$INDEX_PACKAGE.database"
private const val GENERATION_PACKAGE: String = "$INDEX_PACKAGE.generation"
private const val IMPORTING_PACKAGE: String = "$INDEX_PACKAGE.importing"
private const val QUERY_PACKAGE: String = "$INDEX_PACKAGE.query"
private val ROOT_FILES: Set<String> = setOf("WorkspaceIndexModel", "WorkspaceReportIndex")
private val ALLOWED_PACKAGES: Set<String> =
    setOf(INDEX_PACKAGE, DATABASE_PACKAGE, GENERATION_PACKAGE, IMPORTING_PACKAGE, QUERY_PACKAGE)
private val FORBIDDEN_GENERATION_IMPORTS: Set<String> = setOf(IMPORTING_PACKAGE, QUERY_PACKAGE)
private val FORBIDDEN_QUERY_IMPORTS: Set<String> = setOf(GENERATION_PACKAGE, IMPORTING_PACKAGE)
