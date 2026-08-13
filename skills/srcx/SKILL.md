---
name: srcx
description: Use when installing or configuring the SRCX Gradle settings plugin, enabling automatic context generation, filtering dependency scopes, or choosing an SRCX task.
---

# SRCX setup

## When to use

- Preparing LLM-readable source and architecture context for a Gradle workspace.
- Scanning Kotlin and Java source without compiling it first.
- Including all active composite builds in one workspace-level analysis.
- Configuring report output, automatic generation, or dependency-scope filtering.

## Setup

Apply SRCX in the root `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("zone.clanker.gradle.srcx") version "0.47.0"
}

srcx {
    outputDir.set(".srcx")
    autoGenerate.set(false)
    excludeDepScopes.add("myInternalScope")
}
```

Version `0.47.0` is published on Maven Central.

## Configuration

| Setting | Default | Purpose |
|---|---|---|
| `outputDir` | `.srcx` | Simple child report directory in the root and each included build |
| `autoGenerate` | `false` | Make the root `assemble` lifecycle depend on `srcx-context` |
| `excludeDepScopes` | Internal Gradle/Kotlin scopes | Exclude noisy root-build dependency configurations |

Use `excludeDepScopes.add(...)` to preserve defaults. Use `.set(...)` only when intentionally replacing the complete
default set. Included-build dependency extraction currently uses built-in exclusions rather than custom values.

Keep the default `.srcx` output directory. Never configure `.`, `..`, an absolute path, or traversal segments: context
generation and cleanup do not fully enforce containment. Included-build dashboard links also assume `.srcx`. The
extension exposes `forbiddenPackages(...)` and `forbiddenClassPatterns(...)`, but current analysis uses built-in
defaults; do not rely on custom forbidden-name values until the task wiring supports them.

## Tasks

```bash
./gradlew srcx-context
./gradlew srcx-clean
```

`srcx-context` generates Markdown plus `site/index.html` and an embeddable `site/report.html` from the same typed
workspace model. Root output also contains `relationships/index.md` and bounded important-symbol pages with cumulative
local/workspace/cross-build relationship evidence. The HTML embeds D3 locally for a file-first Atlas with build,
project, and source-set scope controls plus deterministic Files and Symbols pages.
Its unified Build comparison counts one source-set record per analyzed Gradle project/source-set summary and labels the
exact maximum used to scale each metric column. It also provides one globally bounded, at-most-42-node Files overview.
Selecting a build/project/source-set refills Files and Symbols from their complete typed catalogs and deterministically
pages at most 42 unique file or declaration/endpoint nodes. Hubs and incident cross-scope endpoints may repeat, while
every exact relationship edge is assigned to exactly one page. Exact-file Problems,
observed file cycles, and distinct analyzer-inferred component-cycle routes remain separate lenses. Its resizable source
pane shows one active evidence location at a time. Import facts may assist resolution but are excluded from the displayed
relationship-record counts. The report does not require a CDN. `srcx-clean` removes the configured output directory from
the root and included builds. When the Gradle lifecycle `clean` task exists, it also depends on `srcx-clean`.

## Rules

- Run SRCX from the workspace root; it discovers active included builds automatically.
- Only builds included by Gradle are scanned. Disabled WRKX catalog entries are not part of SRCX analysis.
- Use the root relationship index as the authoritative cumulative view; included-build reports remain local summaries.
- A local inbound count of zero does not mean unused when workspace inbound is non-zero.
- `DIRECT`, `DERIVED`, and `HEURISTIC` describe static source-evidence strength, not compiler-semantic certainty.
  Ambiguous targets remain unresolved.
- Treat findings as source-analysis evidence, not absolute runtime truth; reflection and generated code may be invisible.
- Generated directories contain `.gitignore` with `*`; do not manually maintain report content.
- A relationship record is keyed by source declaration (or import-owner scope), target declaration, and source line.
  Duplicate keys collapse to one record, retaining the most-specific relationship kind and then strongest evidence.
- Atlas node radius uses attached totals, not only currently drawn edges: file nodes use their payload's total workspace
  inbound, outbound, and internal records; symbol nodes use records reconstructed across available symbol pages.
- Files opens with one globally bounded overview of at most 42 nodes; selected scopes refill into pages of at most 42.
  Symbols pages have the same unique-node limit. Hubs and incident cross-scope endpoints may repeat, but every exact edge
  appears on one page. Keep indexed, available, displayed, and not-on-page counts distinct.
- Embedded Atlas source is the full typed source-file set supplied by the immutable workspace report. Declaration lines
  keep subtle location marks; only the active declaration, relationship occurrence, or finding receives a strong
  highlight.
