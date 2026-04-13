# srcx

[![Build](https://github.com/ClankerGuru/srcx/actions/workflows/build.yml/badge.svg)](https://github.com/ClankerGuru/srcx/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/zone.clanker/plugin-srcx?label=Maven%20Central)](https://central.sonatype.com/artifact/zone.clanker/plugin-srcx)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-purple)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.4.1-green)](https://gradle.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow)](https://opensource.org/licenses/MIT)

**Source symbol extraction, architecture analysis, and LLM-ready context generation for Gradle projects.**

Scans your codebase — including all included builds in a workspace — and generates structured Markdown reports: hub classes, entry points, anti-patterns, interfaces, and cross-build dependencies. Designed for AI agents that need codebase context.

## Quick start

```kotlin
// settings.gradle.kts
plugins {
    id("zone.clanker.gradle.srcx") version "latest"
}

srcx {
    outputDir.set(".srcx")
    forbiddenPackages("legacy", "compat")
    forbiddenClassPatterns("Base", "Impl")
}
```

```bash
./gradlew srcx-context      # generate context report
./gradlew srcx-clean        # delete all .srcx output
```

## Generated reports

| File | Description |
|------|-------------|
| `context.md` | Dashboard: symbol counts, warnings, links to included builds |
| `hub-classes.md` | Most-depended-on classes with dependency trees across all builds |
| `entry-points.md` | App, test, and mock entry points classified by kind |
| `anti-patterns.md` | Code smells: god classes, circular deps, forbidden names, DI violations |
| `interfaces.md` | Interface coverage: implementations, missing mocks |
| `cross-build.md` | Shared classes referenced across build boundaries |

Reports are aggregated from per-build analysis — works reliably on large repos with many included builds.

## DSL reference

```kotlin
srcx {
    outputDir.set(".srcx")                    // output directory (default: .srcx)
    autoGenerate.set(true)                    // regenerate on every compile
    excludeDepScopes.add("kotlinScriptDef")   // dependency scopes to skip
    forbiddenPackages("utils", "helpers")      // additional forbidden package names
    forbiddenClassPatterns("Helper", "Mgr")    // additional forbidden class suffixes
}
```

**Default forbidden packages:** `util`, `utils`, `helper`, `helpers`, `manager`, `managers`, `misc`, `base`

**Default forbidden class patterns:** `Helper`, `Manager`, `Utils`, `Util`

## Package structure

### `model/`

Data types: value classes with validation (`SymbolName`, `PackageName`, `FilePath`, `ProjectPath`), extraction types (`SymbolEntry`, `DependencyEntry`, `ProjectSummary`), and analysis summaries (`AnalysisSummary`, `HubClass`, `Finding`).

### `parse/`

PSI-based source parsing using the Kotlin compiler embeddable.

- **PsiEnvironment** — manages a shared `KotlinCoreEnvironment` instance. Thread-safe singleton — the IntelliJ platform is initialized exactly once and reused across all analysis calls.
- **PsiParser** — extracts declarations and references from `.kt`, `.java`, and `.gradle.kts` files.
- **SourceScanner** — discovers source directories across Java, Kotlin JVM, and KMP projects.
- **SymbolIndex** — cross-referenced index of all symbols and references.

### `analysis/`

Architecture analysis on parsed source metadata.

- **ComponentClassifier** — classifies source files by role (Controller, Service, Repository, Entity) using annotations and naming conventions.
- **DependencyAnalyzer** — builds dependency graphs, finds hub classes, detects circular dependencies.
- **AntiPatternDetector** — detects god classes, forbidden names, DI violations, missing tests, circular deps.
- **DiagramGenerator** — generates Mermaid diagrams from the dependency graph.
- **SourceFileMetadata** — lightweight structural metadata extraction.

### `report/`

Markdown report generators.

- **DashboardRenderer** — workspace-level overview with included build table and split file links.
- **HotClassesRenderer** — hub classes ranked by dependent count with dependency trees.
- **EntryPointsRenderer** — app/test/mock entry point classification.
- **AntiPatternsRenderer** — per-build anti-pattern findings grouped by severity.
- **CrossBuildRenderer** — shared hub classes and cycles across build boundaries.
- **InterfacesRenderer** — interface coverage with implementation counts (excludes mocks).
- **ProjectReportRenderer** — per-project symbol and dependency tables.
- **IncludedBuildRenderer** — per-build context for included builds.

### `scan/`

Gradle model integration.

- **ProjectScanner** — discovers source sets and projects using the Gradle API.
- **SymbolExtractor** — extracts symbols and dependencies from Gradle project data. Runs `analyzeProject()` per build with OOM error handling.

### `task/`

- **ContextTask** — generates all reports. Aggregates hub classes and entry points from per-build analysis (no monolithic cross-build parse). Cleans up PSI environment when done.
- **CleanTask** — deletes all `.srcx` output directories.

## How it works

1. Plugin reads DSL configuration at settings evaluation time
2. `ContextTask` runs symbol extraction per project in parallel
3. Per-build analysis runs `analyzeProject()` (PSI parsing → component classification → dependency graph → anti-patterns → hub classes)
4. Results aggregated at workspace level — hub classes merged and ranked across all builds
5. Split detail files written alongside the dashboard
6. PSI environment shared as a thread-safe singleton (one init, reused, closed at end)

Analysis failures (OOM, classpath conflicts) log actionable errors instead of silently returning empty results.

## Dependencies

- `org.jetbrains.kotlin:kotlin-compiler-embeddable` — PSI parsing for Kotlin, Java, and Gradle scripts
- Kotest 5.9.1 + Konsist 0.17.3 — testing and architecture enforcement
- Kover — 95% minimum line coverage enforcement

## Contributing

```bash
git clone git@github.com:ClankerGuru/srcx.git
cd srcx
git config core.hooksPath config/hooks
./gradlew build     # compile + test + detekt + ktlint + coverage
```

## License

[MIT](LICENSE)
