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
| `anti-patterns.md` | Structural review prompts for oversized classes, inheritance, cycles, forbidden names, and missing tests |
| `interfaces.md` | Exact interface implementation coverage when resolved evidence exists, with a legacy naming fallback |
| `cross-build.md` | Resolved cross-build edges plus aggregate hubs and project-scoped analyzer-cycle summaries |
| `relationships/index.md` | Ranked important symbols with local/workspace/cross-build inbound record counts |
| `relationships/<symbol>-<scope-hash>.md` | Inbound/outbound relationship records, cross-build edges, and source evidence |
| `site/index.html` | Self-contained static dashboard rendered directly from the typed workspace report |
| `site/report.html` | Scoped static fragment for embedding in an existing page |
| `root/context.md` | Detailed root-project context |
| `<project>/context.md` | Detailed subproject context |

Each included build also receives its own configured output directory with a dashboard and per-project reports.

## Interpretation rules

- Start with `context.md`, then open focused reports linked from the dashboard.
- Start relationship exploration at `relationships/index.md`, then open only relevant symbol pages and cited source.
- Read local, workspace, and cross-build inbound separately. Local zero with workspace inbound is still workspace-referenced.
- Treat zero resolved workspace inbound as absence of observed evidence, not proof that a symbol is unused.
- The Atlas narrows Build → Project → Source set. Files opens with one globally bounded overview of at most 42 files.
  42 is All seed + Symbols-lens cap + hub-survival, not a pager. Selecting a scope refills from the complete typed file
  catalog; it does not merely filter the global overview.
- Symbols likewise refills from the typed declaration and exact non-import relationship catalogs. The All overview uses
  the same 42 hub-survival cap. Hubs or cross-scope endpoints may remain to keep both endpoints together. Indexed,
  available, and displayed counts stay distinct. Exact file findings stay reserved so Problems does not drop them behind
  relationship-heavy files.
- In the Atlas, `A -> B` means source in A has a resolved relationship record to a declaration in B. Arrows route around
  labels and rings; each badge counts displayed non-import records in that direction, and kind controls filter the
  categories present in the projection. “Call / construct records” are captured call or construction occurrences, not
  distinct callers or runtime executions; one line may contribute multiple records. Files aggregates records assigned
  to the current Files or Symbols projection. Symbols shows only exact edges assigned to that
  projection. A heavier arrow represents more displayed records, not greater
  certainty. Imports are excluded.
- Selecting a file opens its complete embedded source in a horizontally resizable pane capped at 50% of the Atlas.
  Only the active symbol, relationship occurrence, or finding evidence is emphasized. Hover may preview one relationship
  occurrence, and Previous/Next pages repeated records; unrelated evidence remains unhighlighted.
- Cycles exposes two distinct models. Observed file cycles are resolved strongly connected components in the complete
  available file-relationship catalog. Analyzer-inferred component cycles are closed, directed routes through qualified
  analysis components and may contain participants without matched typed source or outside the current projection. Their
  explanatory route arrows are not resolved relationship records and do not contribute to relationship-count badges.
- A finding deep-links to the Problems map or source only when it carries typed file, component, or component-cycle
  evidence. A project-scoped review prompt without that evidence remains visible without inventing a graph target.
- Treat legacy naming or artifact-coordinate fallbacks in `entry-points.md`, `interfaces.md`, and `cross-build.md` as
  candidates rather than semantic proof. Exact scoped workspace relationships are identified separately.
- Treat anti-pattern findings as review prompts. Inspect the cited source before changing architecture.
- Concrete dependencies and one-implementation interfaces are neutral by default. Require actual boundary or
  substitutability evidence before adding or removing an abstraction.
- A missing WRKX repository is expected when it is disabled and therefore absent from the Gradle composite.

## Relationship evidence and limits

- `DIRECT` means the source extractor observed the syntax directly.
- `DERIVED` means SRCX deterministically composed direct facts, such as resolving one explicit import.
- `HEURISTIC` means syntax is approximate and the relationship needs review.
- These labels describe static source evidence; none means compiler-semantic or runtime certainty.
- Import-only facts may assist deterministic resolution but are currently excluded from relationship counts.
- Duplicate or ambiguous declarations stay unresolved; SRCX does not choose an arbitrary target.
- A relationship record is keyed by source declaration (or import-owner scope), target declaration, and source line.
  Duplicate keys collapse to one record, retaining the most-specific relationship kind and then strongest evidence.
- The unified Build comparison counts one source-set record per analyzed Gradle project/source-set summary; its column
  maxima are exact values, while bar lengths are independently scaled visual comparisons.
- Atlas node radius uses attached totals, not only currently drawn edges: file nodes use their payload's total workspace
  inbound, outbound, and internal records; symbol nodes use records reconstructed across the available symbol catalog.
- Embedded Atlas source is the full typed source-file set supplied by the immutable workspace report; the HTML renderer
  never performs arbitrary filesystem reads. Declaration lines keep subtle location marks; only the active declaration,
  relationship occurrence, or finding receives a strong highlight.
- PSI parsing is static and does not model reflection, generated code, runtime DI graphs, Android lifecycle, or dynamic
  dispatch. Java/Kotlin support is limited to relationship kinds the current extractors can identify reliably.
- Relationship pages are capped at 100 important symbols. The All-build Files overview is capped at 42 nodes; selected
  scopes refill from the typed catalog. The Symbols All overview also uses the 42 hub-survival cap. Hubs may remain so
  both endpoints stay together. The map does not invent omitted relationship targets.

## Automatic generation

Set `autoGenerate.set(true)` to make the root `assemble` lifecycle depend on `srcx-context`. Incremental task inputs
skip regeneration when the workspace sources have not changed. The standard `clean` lifecycle delegates to
`srcx-clean`, so `clean assemble` removes stale reports before recreating the complete Markdown and HTML output.
