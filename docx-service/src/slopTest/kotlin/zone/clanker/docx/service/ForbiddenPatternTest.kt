package zone.clanker.docx.service

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

class ForbiddenPatternTest :
    BehaviorSpec({
        val mainScope = Konsist.scopeFromSourceSet("main")
        val serviceFiles =
            mainScope.files.filter { file ->
                file.packagee?.name?.startsWith(SERVICE_PACKAGE) == true
            }

        given("service source follows the repository structural rules") {
            then("generic declaration names are rejected") {
                serviceFiles.assertTrue { file ->
                    !FORBIDDEN_DECLARATION.containsMatchIn(file.text)
                }
            }

            then("error flow uses runCatching rather than try catch or finally") {
                serviceFiles.assertTrue { file ->
                    !FORBIDDEN_ERROR_FLOW.containsMatchIn(file.text)
                }
            }

            then("constants stay with their owning type or feature") {
                serviceFiles.assertTrue { file ->
                    file.name.removeSuffix(".kt") !in FORBIDDEN_CONSTANT_FILES
                }
            }

            then("imports are explicit") {
                serviceFiles.assertTrue { file -> file.imports.none { dependency -> dependency.isWildcard } }
            }
        }
    })

private val FORBIDDEN_DECLARATION: Regex =
    Regex("""\b(?:class|interface|object)\s+(?:[A-Za-z0-9_]+(?:Manager|Handler|Impl|Helper|Utils)|Service)\b""")
private val FORBIDDEN_ERROR_FLOW: Regex = Regex("""\b(?:try\s*\{|catch\s*\(|finally\s*\{)""")
private val FORBIDDEN_CONSTANT_FILES: Set<String> = setOf("Constants", "Consts")
private const val SERVICE_PACKAGE: String = "zone.clanker.docx.service"
