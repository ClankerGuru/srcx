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

## Repository modules

The root build is an aggregator; production code and its tests belong to the module that owns the published artifact.

```text
srcx/
├── srcx-gradle-plugin/       # SRCX analysis engine and Gradle settings plugin
├── docx-gradle-plugin/       # DOCX report configuration and Gradle settings plugin
├── docx-web/                 # Precompiled Kotlin/Wasm workspace report viewer
├── docx-service/             # Optional headless multi-workspace HTTP host
├── docx-index/               # Generation-versioned SQLite query index
├── workspace-report-model/  # Shared, renderer-neutral serialized workspace contract
└── build-logic/              # Private Java 17 conventions for the repository modules
```

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

## DOCX static report

DOCX consumes the renderer-neutral snapshot written by `srcx-context` and installs a verified static report at
`.docx/index.html`. The Kotlin/Wasm viewer is compiled and production-optimized when this repository builds; a consumer
workspace copies the distribution bundled in the DOCX plugin and does not compile Wasm or install npm dependencies.

```kotlin
// settings.gradle.kts
plugins {
    id("zone.clanker.gradle.srcx") version "<version>"
    id("zone.clanker.gradle.docx") version "<version>"
}

docx {
    preset.set(DocxPreset.FULL)
    updates {
        mode.set(DocxUpdateMode.ASYNC)
        liveReload.set(true)
    }
}
```

```bash
./gradlew docx-plan    # write the deterministic, serialized projection plan
./gradlew docx-site    # produce and atomically publish the static report
./gradlew docx-open    # serve it on an OS-assigned loopback port until Ctrl-C
./gradlew docx-status  # print the typed last-known publication status
```

The first vertical slice is static: `docx-site` is the synchronous generation path, and the update policy is recorded in
the plan for the later incremental lifecycle. Optional kRPC live mode is not started or required. JSON crossing the
SRCX, DOCX, and browser boundaries is encoded and decoded through the shared Kotlin serialization model.
The measured pre-incremental build and artifact baseline is retained in
[`docs/docx-first-slice-baseline.md`](docs/docx-first-slice-baseline.md).

For optional multi-workspace hosting, static deployment, update behavior, ports, authentication, and SQLite query
architecture, see [`docs/docx-workspace-service.md`](docs/docx-workspace-service.md). The service is headless and
optional; a generated `.docx/` directory remains independently deployable.

### Opt-in real composite demo

The repository includes a gated demo materializer for measuring DOCX against substantial local Gradle builds without
writing `.srcx`, `.docx`, or Gradle state into those source checkouts. It copies only source/configuration inputs into
`build/realCompositeDemo`, excluding generated and VCS directories. The `standard` profile uses the seven repositories
in `foo-bar-workspace-repos/dev`; `large` also includes `catalog` and `gort`.

```bash
JAVA_HOME=/path/to/jetbrains-jdk-17 ./gradlew :docx-web:prepareDocxRealCompositeDemo \
  -Pdocx.realComposite.root=/path/to/clanker \
  -Pdocx.realComposite.profile=large

JAVA_HOME=/path/to/jetbrains-jdk-17 /path/to/gradle -p build/realCompositeDemo \
  srcx-context docx-site --no-daemon --console=plain
```

This profile is deliberately opt-in: normal `build`, `check`, `srcx-context`, and `docx-site` tasks do not materialize
or scan these repositories. Both commands fail fast unless Gradle itself is running on JetBrains JDK 17. The first
scan may need network access to populate the normal Gradle dependency cache; later unchanged scans can add
`--offline`. Reuse the materialized directory between scans because rematerializing intentionally removes copied
build outputs. The generated `.docx/` remains a static, deployable report after the scan completes.

## Generated reports

| File | Description |
|------|-------------|
| `context.md` | Dashboard: symbol counts, warnings, links to included builds |
| `hub-classes.md` | Most-depended-on classes with dependency trees across all builds |
| `entry-points.md` | App, test, and mock entry points classified by kind |
| `anti-patterns.md` | Structural review prompts: oversized classes, deep inheritance, cycles, forbidden names, and missing tests |
| `interfaces.md` | Interface coverage with exact implementation relationships when workspace evidence is available |
| `cross-build.md` | Resolved source relationships and build edges across active builds |
| `relationships/index.md` | Important symbols ranked by cumulative workspace relationships |
| `relationships/<symbol>-<scope-hash>.md` | Evidence-backed incoming and outgoing relationships for one important symbol |
| `site/index.html` | Self-contained static workspace dashboard with an interactive D3 relationship graph |
| `site/report.html` | Scoped HTML fragment for embedding in an existing page |

Project scans remain independent and bounded, but the root task resolves their raw declarations and references together.
The root report is therefore authoritative for cumulative relationship records across the active workspace.
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

- **Local inbound**: resolved relationship records whose source is in the declaration's build and Gradle project.
- **Workspace inbound**: resolved relationship records from every active root and included-build project.
- **Cross-build inbound**: resolved relationship records whose source declaration belongs to another Gradle build.

A declaration with zero local inbound and non-zero workspace inbound is workspace-referenced. Conversely, zero resolved
workspace inbound is not proof of semantic unusedness: generated code, reflection, dependency injection, and runtime
lookup may be invisible to static PSI analysis.

SRCX selects a bounded set of important symbols with one documented policy. Applicable reason weights are additive and
rank the bounded output; they are not percentages, quality grades, confidence, severity, or risk. Cross-build inbound
intentionally outweighs every combination of local-only signals. Relationship thresholds count resolved non-import
records, not unique source symbols or files. The default limit is 100 relationship pages.

The unified Build comparison matrix counts one **source-set record** for each analyzed Gradle project/source-set
summary, such as `:app / main`; it is not a file, dependency, or relationship record. Bars scale independently by
metric, and the Column maximums cards print the exact largest value used by each metric column.

### Reading the Workspace Atlas

The HTML Atlas opens with Files, one globally bounded overview of at most 42 relationship-connected files or files with
exact findings. 42 is the All seed, Symbols-lens cap, and hub-survival budget — not a pager. Its navigator narrows
Build → Project → Source set. Selecting a scope refills Files from the complete typed source catalog instead of
filtering the global overview. Exact file findings are reserved ahead of relationship-heavy files so Problems stays
first-class and does not drop them. Omitted relationship targets stay as source-line evidence; the map does not invent
graph nodes for them.

Symbols refills from the typed declaration and exact non-import relationship catalogs for the selected scope, then
applies the same 42 hub-survival cap in the All overview. A cross-scope endpoint or hub may remain so an edge always
keeps both endpoints. Indexed, available, and displayed counts stay separate; none is presented as a runtime call
count.

An arrow points from source to dependency. Routes bend around node rings and labels, and the badge on an arrow is the
number of displayed, non-import relationship records in that direction; a heavier arrow means more such records. Kind
filters separate the relationship categories present in the projection. One relationship record is an addressed static
fact: source declaration—or the owning build/project/source-set/file for an import—to target declaration at one source
line. Facts with that same address collapse to one record, retaining the most-specific relationship kind and then the
strongest evidence. “Call / construct records” are captured source occurrences resolved as a call or construction
relationship; the number is not a count of distinct callers or runtime executions, and one source line can contribute
more than one distinct record. Files aggregates records between files in the current projection. Symbols shows the
exact edges assigned to that projection.
`DIRECT` means SRCX observed the source syntax, `DERIVED` means it resolved a target by
deterministically composing source facts, and `HEURISTIC` means approximate syntax evidence needs review. None of these
labels claims compiler-semantic or runtime certainty. Import facts may help resolution, but they are currently excluded
from relationship counts and arrows.

Node radius uses attached totals rather than only the edges currently drawn. File nodes use their payload's total
workspace inbound, outbound, and internal records; symbol nodes use inbound and outbound records reconstructed across
the available symbol catalog. Radius is not a measure of importance, severity, quality, traffic, or runtime frequency.

Selecting a file opens its complete embedded source in a horizontally resizable pane capped at half of the Atlas width.
The source payload is the full, typed set of exact source files supplied by the immutable workspace report. The HTML
renderer does not perform arbitrary filesystem reads. Declaration lines retain subtle location marks; selecting a
relationship lightly marks all its occurrence lines in the open file, while only the active declaration, relationship
occurrence, or finding receives a strong highlight. Hovering a relationship may preview that evidence. Wider source
remains horizontally scrollable, and repeated records can be paged without leaving the report.

The cycle views deliberately keep two evidence models separate. An **observed file cycle** is a strongly connected
component of the complete available file-relationship catalog. An **analyzer-inferred component cycle** is a closed,
directed route of qualified analysis components and may include participants without matched typed source or outside
the current projection. Analyzer route arrows are explanatory overlays, not resolved relationship records, so they do not
increase the arrow badges. The Atlas lists the complete typed route even when it cannot draw every participant.

Findings deep-link into the Problems map or source pane only when the report carries typed evidence such as an exact file
location, qualified component identity, or typed component-cycle route. Project-scoped review prompts without that
evidence remain readable findings but do not pretend that the Atlas can identify a source location.

## Findings policy

Concrete dependencies are normal by default, and one local interface with one implementation is neutral. SRCX does not
recommend extracting an interface merely because a concrete class is referenced, nor removing an interface merely
because it has one implementation. An interface becomes architecturally notable when source evidence shows an actual
boundary, multiple implementations, or explicit substitutability. Composition roots may construct concrete
implementations. Cycle guidance recommends reversing an edge or moving shared policy/data; it does not prescribe an
interface without independent evidence.

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

Data types include scoped workspace symbols/references, cumulative relationship records, important-symbol
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
- **AntiPatternDetector** — detects oversized classes, deep inheritance, forbidden names, missing tests, and cycles.
- **DiagramGenerator** — generates Mermaid diagrams from the dependency graph.
- **SourceFileMetadata** — lightweight structural metadata extraction.

### `report/`

Markdown report generators.

- **DashboardRenderer** — workspace-level overview with included build table and split file links.
- **HotClassesRenderer** — hub classes ranked by dependent count with dependency trees.
- **EntryPointsRenderer** — app/test/mock entry point classification.
- **AntiPatternsRenderer** — per-build anti-pattern findings grouped by severity.
- **CrossBuildRenderer** — resolved build edges plus workspace-level hub and project-scoped analyzer-cycle summaries.
- **WorkspaceRelationshipsRenderer** — root relationship index and collision-safe important-symbol pages.
- **WorkspaceArchitectureGraphRenderer** — bounded D3 graph and scoped source-viewer data from cumulative evidence.
- **InterfacesRenderer** — interface coverage with implementation counts (excludes mocks).
- **ProjectReportRenderer** — per-project symbol and dependency tables.
- **IncludedBuildRenderer** — per-build context for included builds.

### `scan/`

Gradle model integration.

- **ProjectScanner** — discovers source sets and projects using the Gradle API.
- **SymbolExtractor** — extracts deterministic per-project summaries, exact scoped source text, and owned PSI facts.
- **WorkspaceIndexBuilder** — resolves all active project facts into one cumulative workspace index.

### `task/`

- **ContextTask** — orchestrates project scans, cumulative resolution, important-symbol selection, and all root reports.
- **CleanTask** — deletes all `.srcx` output directories.

## How it works

1. Plugin reads DSL configuration at settings evaluation time
2. `ContextTask` extracts declarations, references, and compatible project summaries per project
3. The root task resolves all active project facts in one ownership-aware workspace index
4. Cumulative relationship records, build edges, hubs, and important symbols derive from the resolved workspace index
5. Relationship Markdown, bounded source files, and the D3 graph render from that same typed evidence
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
