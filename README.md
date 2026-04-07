# srcx

[![Build](https://github.com/ClankerGuru/srcx/actions/workflows/build.yml/badge.svg)](https://github.com/ClankerGuru/srcx/actions/workflows/build.yml)
[![Gradle Plugin](https://img.shields.io/badge/Gradle%20Plugin-0.1.0-blue)](https://github.com/ClankerGuru/srcx)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-purple)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.4.1-green)](https://gradle.org)
[![Coverage](https://img.shields.io/badge/Coverage-%E2%89%A595%25-brightgreen)](https://github.com/ClankerGuru/srcx)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow)](https://opensource.org/licenses/MIT)

**Source symbol extraction for LLM-ready context.**

Extract symbols from your Gradle projects and generate structured Markdown reports. Feed the output to LLMs as context about your codebase -- classes, functions, properties, dependencies, and project structure, all in one place.

## Why srcx

LLMs work better when they understand your codebase structure. Instead of dumping raw source files into a prompt, srcx extracts the important symbols -- classes, functions, properties, packages -- and organizes them into concise Markdown reports. One command generates a full dashboard covering every project in your build, including composite builds.

```text
srcx-generate  = extract symbols from all projects in parallel
srcx-dashboard = generate root index linking to all project reports
.srcx/         = output directory (auto-gitignored)
```

## Quick start

### settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("zone.clanker.gradle.srcx") version "latest"
}

rootProject.name = "my-project"
include(":app", ":lib", ":core")
```

### Then

```bash
./gradlew srcx-generate     # extract symbols from all projects
./gradlew srcx-dashboard    # generate root dashboard index
```

## Generated output

### .srcx/index.md (dashboard)

```markdown
# Source Dashboard

## Projects
| Project | Symbols | Dependencies | Report |
|---------|---------|-------------|--------|
| :app | 45 | 12 | [view](app/symbols.md) |
| :lib:core | 23 | 5 | [view](lib/core/symbols.md) |

## Included Builds
| Build | Report |
|-------|--------|
| gort | [view](gort/index.md) |
```

### .srcx/app/symbols.md (per-project)

```markdown
# :app

## Symbols
| Kind | Name | Package | File | Line |
|------|------|---------|------|------|
| CLASS | MyService | com.example | MyService.kt | 5 |
| FUNCTION | process | com.example | MyService.kt | 10 |
| PROPERTY | version | com.example | Config.kt | 3 |

## Dependencies
| Scope | Artifact | Version |
|-------|----------|---------|
| implementation | org.jetbrains.kotlin:kotlin-stdlib | 2.1.20 |

## Build
- Build file: build.gradle.kts
- Source dirs: src/main/kotlin, src/main/java
- Subprojects: :app:api, :app:impl
```

## DSL reference

The `srcx { }` block controls output configuration:

```kotlin
srcx {
    outputDir = ".srcx"    // default output directory
}
```

| Property | Default | Description |
|----------|---------|-------------|
| `outputDir` | `".srcx"` | Directory for generated reports (relative to root project) |

## Tasks

| Task | Description |
|------|-------------|
| `srcx-generate` | Extract symbols and generate reports for all projects (parallel) |
| `srcx-generate-<name>` | Generate report for a single project |
| `srcx-dashboard` | Generate root dashboard index.md |

```bash
./gradlew srcx-generate           # all projects in parallel
./gradlew srcx-generate-app       # just :app
./gradlew srcx-dashboard          # root index only
```

## How it works

1. Plugin applies at settings evaluation time
2. After project evaluation, tasks are registered for each project
3. `srcx-generate` runs symbol extraction across all projects in parallel (4 threads)
4. For each project, `SymbolExtractor` scans `src/main/kotlin/` and `src/main/java/`
5. `DependencyExtractor` reads project configurations (api, implementation, etc.)
6. `ProjectReportRenderer` writes per-project Markdown to `.srcx/<project>/symbols.md`
7. `DashboardRenderer` writes the root index at `.srcx/index.md`
8. `.srcx/.gitignore` is auto-created with `*` to keep reports out of version control

## Install globally

```bash
bash install.sh
# or
curl -fsSL https://raw.githubusercontent.com/ClankerGuru/srcx/main/install.sh | bash
# uninstall
bash install.sh --uninstall
```

Writes an init script to `~/.gradle/init.d/` so every Gradle project gets srcx tasks.

---

## Contributing

### Requirements

- JDK 17+ (JetBrains Runtime recommended)
- Gradle 9.4.1 (included via wrapper)

### Clone and set up

```bash
git clone git@github.com:ClankerGuru/srcx.git
cd srcx
git config core.hooksPath config/hooks
```

Git hooks enforce:
- **pre-commit**: runs `./gradlew build` (compile + test + detekt + ktlint + coverage)
- **pre-push**: blocks direct pushes to `main` (forces PRs)

### Build

```bash
./gradlew build
```

This single command runs everything:

| Step | Task | What it checks |
|------|------|---------------|
| Compile | `compileKotlin` | Kotlin source compiles |
| Detekt | `detekt` | Static analysis against `config/detekt.yml` |
| ktlint | `ktlintCheck` | Code formatting against `.editorconfig` |
| Unit tests | `test` | Model, extractor, report, and plugin behavior |
| Architecture tests | `slopTest` | Konsist: naming, packages, boundaries, forbidden patterns |
| Coverage | `koverVerify` | Line coverage >= 95% enforced |
| Plugin validation | `validatePlugins` | Gradle plugin descriptor is valid |

### Common commands

```bash
./gradlew build                    # full build (everything)
./gradlew assemble                 # just compile
./gradlew test                     # unit tests
./gradlew detekt                   # static analysis only
./gradlew ktlintCheck              # formatting check only
./gradlew ktlintFormat             # auto-fix formatting
./gradlew slopTest                 # architecture tests (Konsist)
./gradlew check                    # all verification tasks
./gradlew publishToMavenLocal      # publish to ~/.m2 for local testing
```

### Code coverage

Coverage is enforced at **95% minimum** line coverage via [Kover](https://github.com/Kotlin/kotlinx-kover).

```bash
# Check coverage threshold (fails if below 95%)
./gradlew koverVerify

# Print coverage summary to terminal
./gradlew koverLog

# Generate HTML report
./gradlew koverHtmlReport
open build/reports/kover/html/index.html

# Generate XML report (for CI integration)
./gradlew koverXmlReport
# output: build/reports/kover/report.xml
```

No classes are excluded from coverage. All code is tested directly or through Gradle TestKit.

### Static analysis

**Detekt** runs with the configuration at `config/detekt.yml`:
- Max issues: 0 (zero tolerance)
- Warnings treated as errors
- Max line length: 120
- Cyclomatic complexity threshold: 15
- Nested block depth: 4
- Magic numbers enforced (except -1, 0, 1, 2)

```bash
./gradlew detekt
# report: build/reports/detekt/detekt.html
```

**ktlint** enforces formatting rules from `.editorconfig`:
- ktlint official style
- Trailing commas required
- 120 char line length
- No wildcard imports

```bash
./gradlew ktlintCheck              # check
./gradlew ktlintFormat             # auto-fix
```

### Architecture tests

Architecture is enforced via [Konsist](https://docs.konsist.lemonappdev.com/) in `src/slopTest/`:

| Test | Enforces |
|------|----------|
| `PackageBoundaryTest` | Models never import from extractors or reports. Extractors never import from reports. |
| `NamingConventionTest` | Extractor classes end with `Extractor`. Report classes end with `Renderer`. No generic suffixes (Helper, Manager, Util, etc.). |
| `TaskAnnotationTest` | No standalone Task classes exist (all tasks registered inline). |
| `ForbiddenPackageTest` | No junk-drawer packages (utils, helpers, common, misc, shared, etc.). |
| `ForbiddenPatternTest` | No try-catch (use runCatching). No standalone constant files. No wildcard imports. |

```bash
./gradlew slopTest
# report: build/reports/tests/slopTest/index.html
```

### Convention plugins (build-logic)

All build configuration is managed through precompiled script plugins:

| Plugin | Provides |
|--------|----------|
| `clkx-conventions` | Applies all conventions below |
| `clkx-module` | `java-library` + Kotlin JVM + JUnit Platform |
| `clkx-toolchain` | JDK toolchain configuration |
| `clkx-plugin` | `java-gradle-plugin` setup |
| `clkx-publish` | Maven Central publishing via Vanniktech |
| `clkx-testing` | Kotest + Kover + Konsist + slopTest source set |
| `clkx-detekt` | Detekt static analysis with `config/detekt.yml` |
| `clkx-ktlint` | ktlint formatting with `.editorconfig` rules |

The main `build.gradle.kts` is minimal:

```kotlin
plugins {
    id("clkx-conventions")
}
```

### Project structure

```text
srcx/
├── .github/workflows/
│   ├── build.yml                <- CI: build + test on push/PR to main
│   └── release.yml              <- Publish to Maven Central on release
├── config/
│   ├── detekt.yml               <- Detekt static analysis rules
│   └── hooks/
│       ├── pre-commit           <- Runs ./gradlew build before every commit
│       └── pre-push             <- Blocks direct push to main
├── build-logic/                 <- Convention plugins (clkx-*)
│   ├── build.gradle.kts         <- Plugin dependencies
│   ├── settings.gradle.kts
│   └── src/main/kotlin/         <- 9 convention plugin scripts
├── src/
│   ├── main/kotlin/zone/clanker/gradle/srcx/
│   │   ├── Srcx.kt             <- SettingsPlugin + SettingsExtension + constants
│   │   ├── SrcxDsl.kt          <- Settings.srcx {} type-safe extension function
│   │   ├── model/
│   │   │   ├── SymbolEntry.kt          <- Symbol name, kind, package, file, line
│   │   │   ├── DependencyEntry.kt      <- group, artifact, version, scope
│   │   │   └── ProjectSummary.kt       <- Aggregated project analysis result
│   │   ├── extractor/
│   │   │   ├── SymbolExtractor.kt      <- Regex-based source symbol extraction
│   │   │   └── DependencyExtractor.kt  <- Gradle configuration dependency extraction
│   │   └── report/
│   │       ├── DashboardRenderer.kt    <- Root .srcx/index.md generation
│   │       └── ProjectReportRenderer.kt <- Per-project .srcx/<name>/symbols.md
│   ├── test/kotlin/             <- Unit + plugin tests (Kotest BDD)
│   └── slopTest/kotlin/         <- Architecture tests (Konsist)
│       ├── PackageBoundaryTest.kt
│       ├── NamingConventionTest.kt
│       ├── TaskAnnotationTest.kt
│       ├── ForbiddenPackageTest.kt
│       └── ForbiddenPatternTest.kt
├── .editorconfig                <- ktlint + editor formatting rules
├── build.gradle.kts             <- Plugin registration + clkx-conventions
├── settings.gradle.kts          <- Three lines: build-logic, clkx-settings, root name
├── gradle.properties            <- Version, Maven coordinates, POM metadata
└── install.sh                   <- Global installer via Gradle init script
```

## License

[MIT](LICENSE)
