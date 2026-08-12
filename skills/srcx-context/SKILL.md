---
name: srcx-context
description: Use when generating, refreshing, or interpreting SRCX source-symbol and architecture reports for a root Gradle build and its active included builds.
---

# SRCX context

## Commands

```bash
./gradlew srcx-context
./gradlew srcx-context --rerun-tasks
```

With WRKX-selected worktrees:

```bash
./gradlew wrkx-worktree -Pwrkx.branch=feature/example-name
./gradlew build srcx-context -Pwrkx.branch=feature/example-name
```

The branch property belongs to WRKX. SRCX analyzes whichever included builds Gradle selects for that invocation.
Use `wrkx-worktree`; there is no separate checkout alias. Before creating worktrees, WRKX fetches and creates a missing
local base from `origin/<baseBranch>`, or from the fetched remote default when that base is absent remotely too. It does
not push the new local base.

## Behavior

- Parses Kotlin and Java source under conventional source-set directories with PSI; compilation is not required.
- Treats build scripts as task inputs and uses limited literal artifact matching for included-build edges, but does not
  analyze Gradle scripts as source symbols.
- Extracts each project independently, then resolves all declarations/references in one root-owned cumulative index.
- Requires explicit/import-derived qualification before an unqualified relationship may cross a build boundary.
- Retains build, project, source set, file, line, relationship kind, and evidence strength where PSI supplies them.
- Uses source/build files, project identities, configuration, and included-build paths as Gradle task inputs.
- Becomes up-to-date when those inputs and the configured output are unchanged.
- Only the root output directory is a declared Gradle output. Use `--rerun-tasks` when an included-build report is
  missing or stale even though Gradle reports the task up-to-date.

## Root output

The default `.srcx/` directory contains:

| File | Interpretation |
|---|---|
| `context.md` | Workspace dashboard, counts, warnings, and links |
| `hub-classes.md` | Highly referenced classes and dependency trees |
| `entry-points.md` | Naming-based class classification; ordinary classes are categorized as application entries |
| `anti-patterns.md` | Structural smells, cycles, forbidden names, and DI findings |
| `interfaces.md` | Naming-based interface, implementation, and test-double candidates |
| `cross-build.md` | Heuristic artifact-name edges plus concatenated per-project hubs and cycles; these are not semantic cross-build reference analysis |
| `relationships/index.md` | Ranked important symbols with local/workspace/cross-build usage counts |
| `relationships/<symbol>-<scope-hash>.md` | Cumulative consumers, dependencies, cross-build edges, and source evidence |
| `site/index.html` | Self-contained static dashboard rendered directly from the typed workspace report |
| `site/report.html` | Scoped static fragment for notebook embedding |
| `root/context.md` | Detailed root-project context |
| `<project>/context.md` | Detailed subproject context |

Each included build also receives its own configured output directory with a dashboard and per-project reports.

## Interpretation rules

- Start with `context.md`, then open focused reports linked from the dashboard.
- Start relationship exploration at `relationships/index.md`, then open only relevant symbol pages and cited source.
- Read local, workspace, and cross-build inbound separately. Local zero with workspace inbound is still workspace-used.
- Treat zero resolved workspace inbound as absence of observed evidence, not proof that a symbol is unused.
- The D3 atlas opens with scoped files. `A -> B` means source in A contains a resolved relationship to B. Switch to
  Symbols for declarations, Problems for exact file findings, or Cycles for resolved file SCCs. Select a node or edge
  for declarations, relationship kinds, evidence locations, and inbound/outbound flow; dashed edges are heuristic.
- Do not treat `entry-points.md`, `interfaces.md`, or `cross-build.md` as authoritative semantic analysis; each uses
  naming or dependency-coordinate heuristics and can contain omissions or false positives.
- Treat anti-pattern findings as review prompts. Inspect the cited source before changing architecture.
- A missing WRKX repository is expected when it is disabled and therefore absent from the Gradle composite.

## Relationship evidence and limits

- `DIRECT` means the source extractor observed the syntax directly.
- `DERIVED` means SRCX deterministically composed direct facts, such as resolving one explicit import.
- `HEURISTIC` means syntax is approximate and the relationship needs review.
- Import-only facts do not count as usage.
- Duplicate or ambiguous declarations stay unresolved; SRCX does not choose an arbitrary target.
- PSI parsing is static and does not model reflection, generated code, runtime DI graphs, Android lifecycle, or dynamic
  dispatch. Java/Kotlin support is limited to relationship kinds the current extractors can identify reliably.
- Relationship pages are capped at 100 important symbols. Each interactive file/symbol projection is capped at 42
  nodes and favors important, connected source evidence.

## Automatic generation

Set `autoGenerate.set(true)` to make the root `assemble` lifecycle depend on `srcx-context`. Incremental task inputs
skip regeneration when the workspace sources have not changed. The standard `clean` lifecycle delegates to
`srcx-clean`, so `clean assemble` removes stale reports before recreating the complete Markdown and HTML output.
