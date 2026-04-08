# srcx

Source symbol extraction, architecture analysis, and LLM-ready context generation for Gradle projects.

## Package Structure

### `model/`

All data types used across srcx. Contains value classes with validation (`SymbolName`, `PackageName`, `FilePath`, `ArtifactGroup`, `ArtifactName`, `ArtifactVersion`, `ProjectPath`), basic extraction types (`SymbolEntry`, `SymbolKind`, `DependencyEntry`, `ProjectSummary`), and PSI-level types for deep source analysis (`Symbol`, `SymbolDetailKind`, `Reference`, `ReferenceKind`, `MethodCall`, `VerifyAssertion`).

### `parse/`

Source parsing and symbol indexing using the Kotlin compiler PSI. Handles `.kt`, `.java`, and `.gradle.kts` files.

- **PsiEnvironment** -- manages the `KotlinCoreEnvironment` lifecycle for PSI parsing outside IntelliJ.
- **PsiParser** -- extracts declarations and references from Kotlin, Java, and Gradle script files using PSI AST traversal.
- **SourceScanner** -- discovers source directories across Java, Kotlin JVM, and KMP projects.
- **SymbolIndex** -- cross-referenced index of all symbols and references. Powers find-usages, call-graph construction, and usage counting.

### `analysis/`

Architecture analysis operating on parsed source metadata. Classifies components by role, builds dependency graphs, detects anti-patterns, and generates Mermaid diagrams.

- **ComponentClassifier** -- classifies source files by architectural role (Controller, Service, Repository, Entity, etc.) using annotations and naming conventions.
- **DependencyAnalyzer** -- builds a directed dependency graph between components using import analysis and supertype resolution. Finds hub classes and detects circular dependencies.
- **AntiPatternDetector** -- detects code smells (Manager/Helper/Util classes), god classes, deep inheritance, single-implementation interfaces, circular dependencies, and missing tests.
- **DiagramGenerator** -- generates Mermaid flowchart and sequence diagrams from the dependency graph.
- **SourceFileMetadata** -- lightweight source file parser for structural metadata extraction.

### `report/`

Markdown report generators.

- **DashboardRenderer** -- renders the comprehensive context report with overview, build dependency graph, symbols, and problems.
- **ProjectReportRenderer** -- renders per-project symbol and dependency tables.
- **IncludedBuildRenderer** -- renders per-build context for included builds.

## Usage

```kotlin
// settings.gradle.kts
plugins {
    id("zone.clanker.gradle.srcx") version "0.1.0"
}

srcx {
    outputDir = ".srcx"
    autoGenerate = true  // regenerate on every compile
}
```

```bash
./gradlew srcx-context      # generate context report (symbols + analysis + diagrams)
./gradlew srcx-clean        # delete all .srcx output
```

## Dependencies

- `org.jetbrains.kotlin:kotlin-compiler-embeddable` (compileOnly) -- PSI parsing for Kotlin, Java, and Gradle scripts
- Kotest 5.9.1 + Konsist 0.17.3 -- testing and architecture enforcement
- Kover -- 95% minimum line coverage enforcement
