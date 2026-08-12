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

`srcx-context` generates Markdown plus `site/index.html` and a notebook-safe `site/report.html` from the same typed
workspace model. Root output also contains `relationships/index.md` and bounded important-symbol pages with cumulative
local/workspace/cross-build usage and source evidence. The HTML embeds D3 locally for a file-first atlas with symbol,
exact-problem, and resolved-cycle lenses; it does not require a CDN. `srcx-clean` removes the configured output directory
from the root and included builds. When the
Gradle lifecycle `clean` task exists, it also depends on `srcx-clean`.

## Rules

- Run SRCX from the workspace root; it discovers active included builds automatically.
- Only builds included by Gradle are scanned. Disabled WRKX catalog entries are not part of SRCX analysis.
- Use the root relationship index as the authoritative cumulative view; included-build reports remain local summaries.
- A local inbound count of zero does not mean unused when workspace inbound is non-zero.
- `DIRECT`, `DERIVED`, and `HEURISTIC` labels describe source-evidence strength. Ambiguous targets remain unresolved.
- Treat findings as source-analysis evidence, not absolute runtime truth; reflection and generated code may be invisible.
- Generated directories contain `.gitignore` with `*`; do not manually maintain report content.
