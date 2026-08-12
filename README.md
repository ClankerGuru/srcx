# srcx

[![Build](https://github.com/ClankerGuru/srcx/actions/workflows/build.yml/badge.svg)](https://github.com/ClankerGuru/srcx/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/zone.clanker/plugin-srcx?label=Maven%20Central)](https://central.sonatype.com/artifact/zone.clanker/plugin-srcx)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-purple)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.4.1-green)](https://gradle.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow)](https://opensource.org/licenses/MIT)

**Source symbol extraction, architecture analysis, and LLM-ready context generation for Gradle projects.**

Scans your codebase — including all included builds in a workspace — and generates structured Markdown plus a
self-contained static dashboard from the same typed workspace model. Designed for humans and AI agents that need
current codebase context.

> **Recommended skill:** Use the plugin and task guides in [`skills/`](skills/README.md) when generating or consuming
> SRCX context from an AI coding agent.

## Quick start

```kotlin
// settings.gradle.kts
plugins {
    id("zone.clanker.gradle.srcx") version "0.47.0"
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
| `cross-build.md` | Resolved source relationships and build edges across active builds |
| `relationships/index.md` | Important symbols ranked by cumulative workspace relationships |
| `relationships/<symbol>-<scope-hash>.md` | Evidence-backed incoming and outgoing relationships for one important symbol |
| `site/index.html` | Self-contained static workspace dashboard with an interactive D3 relationship graph |
| `site/report.html` | Scoped HTML fragment for Kotlin notebooks or an existing page |

Project scans remain independent and bounded, but the root task resolves their raw declarations and references together.
The root report is therefore authoritative for cumulative usage across the active workspace.
Open `.srcx/site/index.html` directly or serve the `.srcx/site/` directory from an authenticated static host.

## Workspace-cumulative relationships

`srcx-context` follows one deterministic pipeline:

```text
source -> extracted PSI facts -> workspace symbol resolution -> derived relationships -> reports
```

Each declaration and reference retains build, project, source set, project-relative file, and line ownership. The root
index resolves references only when source evidence and workspace scope identify one declaration. Duplicate or
ambiguous targets remain unresolved rather than being assigned arbitrarily.

Relationship pages distinguish:

- **Local inbound**: resolved consumers in the declaration's own build and Gradle project.
- **Workspace inbound**: resolved consumers across every active root and included-build project.
- **Cross-build inbound**: resolved consumers owned by another Gradle build.

A declaration with zero local inbound and non-zero workspace inbound is workspace-used. Conversely, zero resolved
workspace inbound is not proof of semantic unusedness: generated code, reflection, dependency injection, and runtime
lookup may be invisible to static PSI analysis.

SRCX selects a bounded set of important symbols with one documented policy. Cross-build inbound is the strongest
signal, followed by high connectivity, multiple interface implementations, exact entry-point/cycle evidence, and
other cumulative graph signals. The default limit is 100 relationship pages. The HTML atlas shows at most 42 files
and 42 symbols. It opens in the file lens and provides symbol, exact-problem, and resolved-cycle lenses; selecting a
node or edge reveals declarations, findings, source evidence, and an inbound/outbound flow without shrinking the map.

Evidence labels are explicit: `DIRECT` for source facts, `DERIVED` for deterministic resolution composed from direct
facts, and `HEURISTIC` where syntax alone is approximate. Import facts are retained but do not count as usage.

## wrkx worktree integration

`srcx` scans the included builds selected by Gradle, including branch worktrees managed by `wrkx`:

```bash
./gradlew wrkx-worktree -Pwrkx.branch=feature/example-name
./gradlew build srcx-context -Pwrkx.branch=feature/example-name
```

When WRKX enables only a subset of its repository catalog, SRCX scans only that enabled subset because only those
repositories are included in the Gradle composite. Disabled repositories may retain bare clones, but they do not need
worktrees and do not appear in the root SRCX workspace report.

Use `wrkx-worktree` to prepare those included builds; WRKX has no separate checkout alias. It fetches first and creates
a missing local base branch from its remote counterpart, or from the fetched remote default when the base is absent on
both sides. The new local base is not pushed.

The included-build name and canonical directory are task inputs. Switching the same repository from one worktree path
to another invalidates `srcx-context`, regenerates reports in the selected worktrees, and updates dashboard links.

## DSL reference

```kotlin
srcx {
    outputDir.set(".srcx")                    // output directory (default: .srcx)
    autoGenerate.set(true)                    // make assemble depend on srcx-context
    excludeDepScopes.add("kotlinScriptDef")   // dependency scopes to skip
    forbiddenPackages("utils", "helpers")      // additional forbidden package names
    forbiddenClassPatterns("Helper", "Mgr")    // additional forbidden class suffixes
}
```

**Default forbidden packages:** `util`, `utils`, `helper`, `helpers`, `manager`, `managers`, `misc`, `base`

**Default forbidden class patterns:** `Helper`, `Manager`, `Utils`, `Util`

## Package structure

### `model/`

Data types include scoped workspace symbols/references, resolved relationships, cumulative usage, important-symbol
reasons, project summaries, and analysis summaries.

### `parse/`

PSI-based source parsing using the Kotlin compiler embeddable.

- **PsiEnvironment** — manages a shared `KotlinCoreEnvironment` instance. Thread-safe singleton — the IntelliJ platform is initialized exactly once and reused across all analysis calls.
- **PsiParser** — extracts declarations and references from `.kt`, `.java`, and `.gradle.kts` files.
- **SourceScanner** — discovers source directories across Java, Kotlin JVM, and KMP projects.
- **SymbolIndex** — reusable PSI symbol/reference infrastructure.

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
- **WorkspaceRelationshipsRenderer** — root relationship index and collision-safe important-symbol pages.
- **WorkspaceArchitectureGraphRenderer** — bounded D3 graph data from the cumulative relationship index.
- **InterfacesRenderer** — interface coverage with implementation counts (excludes mocks).
- **ProjectReportRenderer** — per-project symbol and dependency tables.
- **IncludedBuildRenderer** — per-build context for included builds.

### `scan/`

Gradle model integration.

- **ProjectScanner** — discovers source sets and projects using the Gradle API.
- **SymbolExtractor** — extracts deterministic per-project summaries plus raw, owned PSI facts.
- **WorkspaceIndexBuilder** — resolves all active project facts into one cumulative workspace index.

### `task/`

- **ContextTask** — orchestrates project scans, cumulative resolution, important-symbol selection, and all root reports.
- **CleanTask** — deletes all `.srcx` output directories.

## How it works

1. Plugin reads DSL configuration at settings evaluation time
2. `ContextTask` extracts declarations, references, and compatible project summaries per project
3. The root task resolves all active project facts in one ownership-aware workspace index
4. Cumulative usage, build edges, hubs, and important symbols derive from resolved workspace relationships
5. Relationship Markdown and the D3 graph render from that same typed evidence
6. Existing project/build reports and the root HTML/Markdown dashboard are written
7. The shared PSI environment is closed after generation

Analysis failures (OOM, classpath conflicts) log actionable errors instead of silently returning empty results.

## Dependencies

- `org.jetbrains.kotlin:kotlin-compiler-embeddable` — PSI parsing for Kotlin, Java, and Gradle scripts
- `org.webjars.npm:d3` — bundled D3 runtime in self-contained generated HTML; no CDN request
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
