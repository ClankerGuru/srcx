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

## Behavior

- Parses Kotlin and Java source under conventional source-set directories with PSI; compilation is not required.
- Treats build scripts as task inputs and uses limited literal artifact matching for included-build edges, but does not
  analyze Gradle scripts as source symbols.
- Analyzes root projects and active included builds independently, then combines their summaries and heuristic edges.
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
| `unused.md` | Production classes with no visible source references |
| `root/context.md` | Detailed root-project context |
| `<project>/context.md` | Detailed subproject context |

Each included build also receives its own configured output directory with a dashboard and per-project reports.

## Interpretation rules

- Start with `context.md`, then open focused reports linked from the dashboard.
- Prioritize hub classes when assessing high-impact changes, but verify references in source.
- Do not treat `entry-points.md`, `interfaces.md`, or `cross-build.md` as authoritative semantic analysis; each uses
  naming or dependency-coordinate heuristics and can contain omissions or false positives.
- Verify every `unused.md` candidate before deletion. Reflection, generated code, framework registration, external
  consumers, and dynamic loading may not appear in source references.
- Treat anti-pattern findings as review prompts. Inspect the cited source before changing architecture.
- A missing WRKX repository is expected when it is disabled and therefore absent from the Gradle composite.

## Automatic generation

Set `autoGenerate.set(true)` to run `srcx-context` after matching Kotlin and Java compile tasks in the root build. It is
not attached to compile tasks inside included builds. Incremental task inputs keep unchanged root reports up-to-date;
use manual invocation when deterministic workspace report timing matters.
