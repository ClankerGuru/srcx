# DOCX first-slice baseline

Measured: 2026-08-13
Checkpoint: first static Kotlin/Wasm vertical slice
Live mode: not implemented or measured

This is the pre-incremental baseline required by the accepted DOCX implementation goal. It records observed values,
including misses, and is not a claim that later release gates have been met.

## Environment

- MacBook Pro `Mac16,8`
- Apple M4 Pro, 12 cores (8 performance and 4 efficiency)
- 24 GB memory
- macOS 27.0, arm64
- JetBrains Runtime 17.0.14 (`JBR-17.0.14+1-1367.22-jcef`)
- Gradle 9.4.1
- Kotlin 2.3.0

`JAVA_HOME` was not set or hardcoded by the measurement commands. The repository and current shell selected the
JetBrains Java 17 runtime.

## Root build

Commands were run from the repository root with `/usr/bin/time -p`.

| State | Command | Result | Wall time |
|---|---|---:|---:|
| Clean | `./gradlew clean build -q` | success | 144.76 s |
| Unchanged | `./gradlew build -q` | success | 1.70 s |

The clean result is 54.76 seconds slower than the accepted goal's later cold-workspace release gate of 90 seconds.
It is therefore baseline debt for Phase 2, not a passing performance result. The unchanged build is a repository-wide
verification time; it does not isolate DOCX overhead or establish a p95.

## Static viewer artifact

The initial production distribution contains one root launcher, production JavaScript and Wasm assets, one typed
workspace summary, one manifest, and one project shard.

| Measurement | Value |
|---|---:|
| ZIP size | 4,167,719 bytes |
| Uncompressed regular-file bytes | 12,101,579 bytes |
| Regular files | 11 |

The Kotlin/Wasm compiler uses the module directory as its relative-path base. The packaged Wasm therefore retains a
useful relative source reference such as `src/wasmJsMain/.../Main.kt` without embedding the checkout path.

## Deferred measurements

The tiny first-slice fixture is deliberately not the representative composite-workspace benchmark required before
replacing the existing report. The following release measurements remain Phase 2 work:

- unchanged normal-build DOCX overhead and enqueue acknowledgement p95;
- changed-file and changed-project generation latency;
- representative-workspace cold generation;
- loaded build/project/source/filter interaction p95;
- dense-graph frame rate and main-thread stalls;
- peak agent and browser memory.

## Browser measurement status

The settled static distribution was served unchanged on an operating-system-assigned loopback port for the required
in-app browser check. The prescribed browser runtime reported `Browser is not available: iab`, and its one permitted
availability inspection returned an empty browser list. No standalone or substitute browser was used. Viewer
first-useful-scope timing, interaction latency, rendered graph behavior, shard requests, and console state therefore
remain explicitly unverified rather than being inferred from HTTP or unit-test timings.

No incremental pipeline, kRPC dependency, live contract, live agent, Ktor transport, or database was added while
collecting this baseline.
