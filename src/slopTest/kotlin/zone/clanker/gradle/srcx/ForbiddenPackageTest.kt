package zone.clanker.gradle.srcx

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

/**
 * Prevents creation of junk-drawer packages.
 *
 * Packages like `utils`, `helpers`, `managers`, `common`, or `misc`
 * are symptoms of unclear responsibility. Every file must belong to
 * a package that describes what it does, not how it's used.
 *
 * If something feels like a utility, it either belongs in the domain
 * package that uses it or deserves its own named package.
 */
class ForbiddenPackageTest :
    BehaviorSpec({

        val mainScope = Konsist.scopeFromSourceSet("main")

        given("no junk-drawer packages exist") {

            val forbidden =
                listOf(
                    "util",
                    "helper",
                    "manager",
                )

            `when`("examining all main source files") {
                then("no file lives in a forbidden package") {
                    mainScope.files.assertTrue {
                        val pkg = it.packagee?.name ?: ""
                        val lastSegment = pkg.substringAfterLast(".")
                        lastSegment !in forbidden
                    }
                }
            }
        }
    })
