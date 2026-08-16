package zone.clanker.docx.web

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class PackageBoundaryTest :
    BehaviorSpec({
        // Konsist 0.17.3 treats "main" as the literal src/main directory. This KMP
        // application has three production source sets, so scan those explicitly.
        val productionScope =
            Konsist.scopeFromSourceSet(
                COMMON_MAIN_SOURCE_SET,
                JVM_MAIN_SOURCE_SET,
                WASM_MAIN_SOURCE_SET,
            )
        val webFiles =
            productionScope.files.filter { file ->
                file.packagee?.name?.startsWith(WEB_PACKAGE) == true
            }

        given("a layered DOCX web application") {
            then("production code stays inside the declared packages") {
                webFiles
                    .mapNotNull { file -> file.packagee?.name }
                    .toSet() shouldBe ALLOWED_PACKAGES
            }

            then("common production code remains platform-neutral") {
                webFiles
                    .filter { file -> file.sourceSetName == COMMON_MAIN_SOURCE_SET }
                    .assertTrue { file ->
                        file.imports.none { dependency -> dependency.name.isPlatformSpecificImport() }
                    }
            }

            then("the status UI remains a dependency leaf") {
                webFiles
                    .filter { file -> file.packagee?.name == UI_PACKAGE }
                    .assertTrue { file -> file.imports.none { dependency -> dependency.name.isDocxWebImport() } }
            }

            then("package imports follow the application dependency direction") {
                webFiles.assertTrue { file ->
                    val sourcePackage = requireNotNull(file.packagee?.name)
                    val allowedImports = ALLOWED_IMPORTS_BY_PACKAGE.getValue(sourcePackage)
                    file.imports
                        .mapNotNull { dependency -> dependency.name.docxWebPackage() }
                        .all { targetPackage -> targetPackage == sourcePackage || targetPackage in allowedImports }
                }
            }

            then("fixture production code remains JVM-only") {
                webFiles
                    .filter { file -> file.packagee?.name == FIXTURE_PACKAGE }
                    .assertTrue { file -> file.sourceSetName == JVM_MAIN_SOURCE_SET }
            }
        }
    })

private fun String.isPlatformSpecificImport(): Boolean =
    startsWith("java.") ||
        startsWith("javax.") ||
        startsWith("kotlinx.browser.") ||
        startsWith("org.w3c.") ||
        startsWith("kotlin.js.")

private fun String.isDocxWebImport(): Boolean = this == WEB_PACKAGE || startsWith("$WEB_PACKAGE.")

private fun String.docxWebPackage(): String? =
    PACKAGES_BY_SPECIFICITY.firstOrNull { candidate ->
        this == candidate || startsWith("$candidate.")
    }

private const val WEB_PACKAGE: String = "zone.clanker.docx.web"
private const val CATALOG_PACKAGE: String = "$WEB_PACKAGE.catalog"
private const val SITE_PACKAGE: String = "$WEB_PACKAGE.site"
private const val EVIDENCE_PACKAGE: String = "$WEB_PACKAGE.evidence"
private const val FINDING_PACKAGE: String = "$WEB_PACKAGE.finding"
private const val PROJECTION_PACKAGE: String = "$WEB_PACKAGE.atlas.projection"
private const val HIERARCHY_PACKAGE: String = "$WEB_PACKAGE.atlas.hierarchy"
private const val SESSION_PACKAGE: String = "$WEB_PACKAGE.atlas.session"
private const val SOURCE_PACKAGE: String = "$WEB_PACKAGE.atlas.source"
private const val STATE_PACKAGE: String = "$WEB_PACKAGE.state"
private const val ATLAS_PACKAGE: String = "$WEB_PACKAGE.atlas"
private const val DOM_PACKAGE: String = "$ATLAS_PACKAGE.dom"
private const val SEARCH_DOM_PACKAGE: String = "$DOM_PACKAGE.search"
private const val CONTROLS_PACKAGE: String = "$DOM_PACKAGE.controls"
private const val DASHBOARD_PACKAGE: String = "$DOM_PACKAGE.dashboard"
private const val DETAIL_PACKAGE: String = "$DOM_PACKAGE.detail"
private const val PROBE_PACKAGE: String = "$WEB_PACKAGE.probe"
private const val UI_PACKAGE: String = "$WEB_PACKAGE.ui"
private const val APPLICATION_PACKAGE: String = "$WEB_PACKAGE.application"
private const val FIXTURE_PACKAGE: String = "$WEB_PACKAGE.fixture"
private const val COMMON_MAIN_SOURCE_SET: String = "commonMain"
private const val JVM_MAIN_SOURCE_SET: String = "jvmMain"
private const val WASM_MAIN_SOURCE_SET: String = "wasmJsMain"

private val ALLOWED_PACKAGES: Set<String> =
    setOf(
        CATALOG_PACKAGE,
        SITE_PACKAGE,
        EVIDENCE_PACKAGE,
        FINDING_PACKAGE,
        PROJECTION_PACKAGE,
        HIERARCHY_PACKAGE,
        SESSION_PACKAGE,
        SOURCE_PACKAGE,
        STATE_PACKAGE,
        ATLAS_PACKAGE,
        DOM_PACKAGE,
        SEARCH_DOM_PACKAGE,
        CONTROLS_PACKAGE,
        DASHBOARD_PACKAGE,
        DETAIL_PACKAGE,
        PROBE_PACKAGE,
        UI_PACKAGE,
        APPLICATION_PACKAGE,
        FIXTURE_PACKAGE,
    )
private val PACKAGES_BY_SPECIFICITY: List<String> =
    ALLOWED_PACKAGES.sortedByDescending { packageName ->
        packageName.length
    }
private val ALLOWED_IMPORTS_BY_PACKAGE: Map<String, Set<String>> =
    mapOf(
        CATALOG_PACKAGE to emptySet(),
        SITE_PACKAGE to emptySet(),
        EVIDENCE_PACKAGE to setOf(SITE_PACKAGE),
        PROJECTION_PACKAGE to emptySet(),
        HIERARCHY_PACKAGE to emptySet(),
        SESSION_PACKAGE to emptySet(),
        SOURCE_PACKAGE to setOf(HIERARCHY_PACKAGE, SITE_PACKAGE),
        STATE_PACKAGE to setOf(SITE_PACKAGE),
        ATLAS_PACKAGE to setOf(HIERARCHY_PACKAGE, PROJECTION_PACKAGE, SESSION_PACKAGE),
        FINDING_PACKAGE to setOf(ATLAS_PACKAGE, STATE_PACKAGE),
        DOM_PACKAGE to
            setOf(
                ATLAS_PACKAGE,
                EVIDENCE_PACKAGE,
                STATE_PACKAGE,
                SEARCH_DOM_PACKAGE,
                CONTROLS_PACKAGE,
                DASHBOARD_PACKAGE,
                DETAIL_PACKAGE,
            ),
        SEARCH_DOM_PACKAGE to setOf(DOM_PACKAGE, STATE_PACKAGE),
        CONTROLS_PACKAGE to
            setOf(
                ATLAS_PACKAGE,
                DOM_PACKAGE,
                CATALOG_PACKAGE,
                HIERARCHY_PACKAGE,
                PROJECTION_PACKAGE,
                SESSION_PACKAGE,
            ),
        DASHBOARD_PACKAGE to setOf(DOM_PACKAGE, CATALOG_PACKAGE),
        DETAIL_PACKAGE to setOf(ATLAS_PACKAGE, DOM_PACKAGE, EVIDENCE_PACKAGE),
        PROBE_PACKAGE to setOf(ATLAS_PACKAGE, CATALOG_PACKAGE, STATE_PACKAGE),
        UI_PACKAGE to emptySet(),
        APPLICATION_PACKAGE to
            setOf(
                CATALOG_PACKAGE,
                SITE_PACKAGE,
                EVIDENCE_PACKAGE,
                FINDING_PACKAGE,
                HIERARCHY_PACKAGE,
                PROJECTION_PACKAGE,
                SESSION_PACKAGE,
                SOURCE_PACKAGE,
                STATE_PACKAGE,
                ATLAS_PACKAGE,
                DOM_PACKAGE,
                SEARCH_DOM_PACKAGE,
                CONTROLS_PACKAGE,
                DASHBOARD_PACKAGE,
                DETAIL_PACKAGE,
                PROBE_PACKAGE,
                UI_PACKAGE,
            ),
        FIXTURE_PACKAGE to
            setOf(
                CATALOG_PACKAGE,
                SITE_PACKAGE,
                FINDING_PACKAGE,
                HIERARCHY_PACKAGE,
                PROJECTION_PACKAGE,
                SESSION_PACKAGE,
            ),
    )
