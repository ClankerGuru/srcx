package zone.clanker.docx.service

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class PackageBoundaryTest :
    BehaviorSpec({
        val mainScope = Konsist.scopeFromSourceSet("main")
        val serviceFiles =
            mainScope.files.filter { file ->
                file.packagee?.name?.startsWith(SERVICE_PACKAGE) == true
            }

        given("a layered standalone workspace service") {
            then("the root package contains composition and configuration only") {
                serviceFiles
                    .filter { file -> file.packagee?.name == SERVICE_PACKAGE }
                    .map { file -> file.name }
                    .toSet() shouldBe ROOT_FILES
            }

            then("workspace state never depends on transports or the concrete index") {
                serviceFiles
                    .filter { file -> file.packagee?.name == WORKSPACE_PACKAGE }
                    .assertTrue { file ->
                        file.imports.none { dependency -> dependency.name.hasAnyPrefix(FORBIDDEN_WORKSPACE) }
                    }
            }

            then("the index adapter depends only on the workspace lifecycle port") {
                serviceFiles
                    .filter { file -> file.packagee?.name == INDEX_PACKAGE }
                    .assertTrue { file ->
                        file.imports.none { dependency -> dependency.name.hasAnyPrefix(FORBIDDEN_INDEX) }
                    }
            }

            then("HTTP is the only package coupled to the JDK HTTP server") {
                serviceFiles
                    .filter { file -> file.packagee?.name != HTTP_PACKAGE }
                    .assertTrue { file ->
                        file.imports.none { dependency -> dependency.name.startsWith(JDK_HTTP_PACKAGE) }
                    }
            }

            then("the SQLite library adapter is confined to the service index package") {
                serviceFiles
                    .filter { file -> file.packagee?.name != INDEX_PACKAGE }
                    .assertTrue { file ->
                        file.imports.none { dependency -> dependency.name.startsWith(DOCX_INDEX_PACKAGE) }
                    }
            }

            then("production files stay inside the declared service packages") {
                serviceFiles.assertTrue { file ->
                    file.packagee?.name?.let(ALLOWED_PACKAGES::contains) == true
                }
            }
        }
    })

private fun String.hasAnyPrefix(prefixes: Set<String>): Boolean = prefixes.any { prefix -> startsWith(prefix) }

private const val SERVICE_PACKAGE: String = "zone.clanker.docx.service"
private const val CLIENT_PACKAGE: String = "$SERVICE_PACKAGE.client"
private const val WORKSPACE_PACKAGE: String = "$SERVICE_PACKAGE.workspace"
private const val INDEX_PACKAGE: String = "$SERVICE_PACKAGE.index"
private const val HTTP_PACKAGE: String = "$SERVICE_PACKAGE.http"
private const val JDK_HTTP_PACKAGE: String = "com.sun.net.httpserver"
private const val DOCX_INDEX_PACKAGE: String = "zone.clanker.docx.index"
private val ROOT_FILES: Set<String> = setOf("DocxServiceConfig", "DocxWorkspaceServer", "Main")
private val ALLOWED_PACKAGES: Set<String> =
    setOf(SERVICE_PACKAGE, CLIENT_PACKAGE, WORKSPACE_PACKAGE, INDEX_PACKAGE, HTTP_PACKAGE)
private val FORBIDDEN_WORKSPACE: Set<String> = setOf(CLIENT_PACKAGE, INDEX_PACKAGE, HTTP_PACKAGE)
private val FORBIDDEN_INDEX: Set<String> = setOf(CLIENT_PACKAGE, HTTP_PACKAGE)
