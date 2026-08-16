# DOCX implementation goal

Status: accepted implementation direction
Canonical output directory: `.docx/`
Runtime baseline: JetBrains Java 17
Last decision update: 2026-08-14

## Goal

Extract human-facing report generation from SRCX into the sibling DOCX Gradle plugin and replace the monolithic,
server-rendered dashboard with a precompiled Kotlin/Wasm application. A developer applies DOCX at the root settings
build, runs normal Gradle work, and can open the current workspace report from `.docx/` without compiling a frontend,
installing JavaScript packages, selecting a port, or manually coordinating a server.

The system must remain useful for workspaces with many included builds and thousands of files and symbols. A routine
build must not repeat a full-workspace analysis or wait for an expensive report render. DOCX consumes a stable,
renderer-neutral snapshot produced by SRCX, updates only changed shards, and atomically replaces the last valid site.

## Accepted Atlas parity amendment (2026-08-14)

The user explicitly approved one bounded exception to the original deferred-library rule: DOCX may bundle and use
**D3 7.9.0** from the repository's existing non-transitive WebJar dependency to reproduce the established interactive
workspace atlas. This approval applies only to D3 7.9.0; no other deferred library is approved.

The generated SRCX report at `.srcx/site/index.html` is the visual and interaction acceptance reference. DOCX must
reuse the canonical SRCX `theme.css` and complete ordered `dashboard.css` cascade from one source rather than maintain
an approximate or copied theme. The Atlas masthead, hero, metrics, navigator, graph chrome, responsive layouts,
fullscreen behavior, selection evidence drawer, graph semantics, and interaction states must match that reference.

Kotlin/Wasm remains the application, state, serialization, accessibility, and product-logic owner. JavaScript remains
minimal: the locally packaged D3 vendor runtime, a narrow `docx-atlas-d3.js` mount/update/command/snapshot/destroy
bridge, and the compiler-generated Wasm bootstrap are permitted, but the legacy `architecture-graph.js` controller is
not carried into DOCX. The bridge may use the real DOM/SVG host required for exact graph behavior. Canvas/WebGL2
remains available for measured high-volume rendering work after parity is established.

The bridge has one narrowly validated serialization exception: it may call native `JSON.parse` exactly once to
consume the ephemeral `AtlasFrame` envelope already encoded by `kotlinx.serialization`, and `JSON.stringify` exactly
once to return its ephemeral renderer snapshot. It must not fetch or parse report, manifest, or shard JSON, hand-build
JSON text, or use JSON strings for DOM state. Report data boundaries remain owned by Kotlin serialization.

This dated amendment supersedes the earlier “do not add a JavaScript graph framework initially” direction only for
the approved D3 7.9.0 Atlas implementation. All other dependency, performance, static-hosting, and verification
guardrails remain in force.

## Product contract

1. `.docx/index.html` is the stable entry point.
2. The browser application is compiled and optimized when DOCX itself is released, not in every consumer workspace.
3. The deployed report is static. A server is not required to read a completed report from a static host.
4. Local live refresh is optional. When enabled, it uses a precompiled JVM agent and `kotlinx.rpc` kRPC.
5. The default update policy is asynchronous: a successful producer task enqueues a small change description and the
   build returns after acknowledgement. The last valid report remains readable while the next generation is built.
6. CI and release workflows can select strict freshness and wait for a verified generation.
7. Multiple workspaces may build at the same time. State, queues, snapshots, and status must be isolated by workspace
   identity, with no fixed-port assumption.
8. SRCX owns extraction and architecture facts. DOCX owns configuration, projection, rendering, report interaction,
   publication, and optional live delivery. DOCX must not duplicate PSI analysis.

## Repository shape

The repository remains one root Gradle build containing sibling Slop projects:

```text
srcx/
├── srcx-gradle-plugin/       # Extracts source facts and produces the canonical workspace snapshot
├── workspace-report-model/  # Versioned renderer-neutral snapshot and Kotlin serialization boundary
├── docx-gradle-plugin/       # Root settings DSL, task wiring, site installation, open/status commands
├── docx-web/                 # Precompiled Kotlin/Wasm viewer and bounded Atlas renderer
├── docx-index/               # Generation-scoped SQLite query index
├── docx-live-contract/       # Small KMP @Rpc contract shared by browser and JVM adapters
├── docx-service/             # Optional headless Java 17 multi-workspace host
└── build-logic/              # Private Java 17/Kotlin/publishing/test conventions
```

Each project owns one architectural concern. A project may expose a small root facade, but implementation files belong
to domain packages with one-way dependencies; a root package must not become a general holding area.

### Viewer package boundaries

The viewer separates portable projection from browser orchestration:

```text
catalog / site                 -> workspace-report-model
atlas.projection               -> workspace-report-model
state                          -> site + workspace-report-model
atlas                          -> atlas.projection + state
finding                        -> atlas + state + workspace-report-model
ui                             -> Compose-only loading and failure presentation
probe                          -> atlas + state
atlas.dom.{controls,dashboard,detail}
                               -> atlas + state + catalog
application                    -> site + atlas + atlas.dom + finding + probe + ui
```

The `application` package is the composition root. DOM, D3, Compose, fetch, and browser event types must not leak into
portable `commonMain` projection packages. The superseded Compose Canvas graph is not retained beside the active D3
surface; keeping two renderers would duplicate state, interaction, and layout behavior.

### Index package boundaries

`docx-index` exposes `WorkspaceReportIndex` and typed query models from its root package. Its implementation is split
by responsibility:

```text
database    -> JDBC connection policy and schema only
generation  -> immutable generation lifecycle and retention
importing   -> ordered shard decode and staged writes
query       -> bounded indexed reads
root facade -> database + generation + importing + query
```

SQLite/JDBC types remain internal. Browser, HTTP, Gradle, and report-rendering types do not enter the index module.

### Publication boundary

- `plugin-srcx` and `plugin-docx` are public Gradle plugin artifacts.
- `workspace-report-model` is a public, versioned library so producers and consumers can evolve independently.
- `docx-web`, `docx-index`, and `docx-service` are packaged implementation artifacts distributed by DOCX; they
  are not public APIs merely because Maven is used to assemble or transport them.
- `docx-live-contract` may be an internal KMP artifact. Publish it publicly only if an independent client needs it.

## Output layout

The installed result is generation-based and atomically published:

```text
.docx/
├── index.html                 # Stable launcher; never points at a partial generation
├── status.json                # Current, updating, stale, or failed state and generation identity
├── assets/
│   ├── atlas/
│   │   ├── theme.css          # Exact canonical SRCX theme, staged from one source
│   │   └── dashboard.css      # Complete ordered Atlas component/state cascade
│   ├── vendor/d3-7.9.0/
│   │   ├── d3.min.js          # Approved local D3 runtime from the existing WebJar
│   │   └── LICENSE
│   ├── docx-atlas-d3.js       # Narrow D3 lifecycle bridge; Kotlin/Wasm owns application state
│   ├── docx-viewer.wasm       # Precompiled and production-optimized
│   └── docx-viewer.js         # Minimal compiler-generated bootstrap/interop loader
├── data/
│   ├── manifest.json          # Schema, workspace identity, generation, and shard catalog
│   ├── workspace.json         # Small workspace/build/project summary
│   ├── atlas-overview.json    # Bounded initial all-workspace Atlas frame
│   └── shards/                # Lazy content-addressed graph, finding, and source shards
└── generations/               # Staging/previous generations; implementation detail and bounded retention
```

The viewer loads the bounded `atlas-overview.json` frame for the initial All-workspace map, then lazy-loads the selected
build/project/source scope. Project shards remain lazy. The viewer must not parse or retain one enormous workspace JSON
document in browser memory. Large source bodies and detailed relationship occurrences belong in separate shards.

An optional portable single-file export may be added later. It is not the default because base64 inflates Wasm/data,
prevents effective browser caching, and forces large eager parsing.

## Approved bill of materials

### Required

- JetBrains Java 17, configured by repository build conventions; never hardcode `JAVA_HOME`.
- Kotlin supported by the repository and `kotlinx.rpc 0.10.3` (the current repository Kotlin 2.3 line is supported).
- Kotlin Multiplatform with the `wasmJs` browser target.
- Compose Multiplatform for application UI and state.
- `kotlinx.serialization` for every JSON boundary. Manual JSON parsing is forbidden.
- `kotlinx.coroutines` for structured concurrency and streams.
- Gradle typed tasks, incremental inputs, build cache, configuration cache, and Worker API.
- Browser Canvas/WebGL2 APIs remain available for measured high-volume graph rendering.
- D3 7.9.0 is the sole approved JavaScript graph dependency for exact Atlas parity, packaged locally from the existing
  non-transitive WebJar as specified by the 2026-08-14 amendment.

### Optional live mode only

- `org.jetbrains.kotlinx.rpc.plugin:0.10.3`.
- kRPC client/server and Ktor transport artifacts managed at the same version by the RPC plugin.
- Ktor WebSockets and a JVM server engine solely to host loopback kRPC and local static preview.
- Kotlin serialization JSON for small control messages.

Use kRPC, not gRPC: both endpoints are Kotlin, the browser is WasmJs, and gRPC does not support WasmJs. Large report
snapshots never travel through RPC.

### Explicitly deferred

- SQLite, Exposed, `kotlinx-io` persistence, or another database.
- React, Vue, another JavaScript UI framework, or any JavaScript graph framework other than the approved D3 7.9.0
  exception.
- A public or remote application server.
- A custom network protocol or manual serializer.

Adding a deferred dependency requires an observed benchmark or product requirement and explicit approval.

## Update lifecycle

### Asynchronous mode (default)

```text
successful SRCX snapshot task
        -> DOCX writes one small atomic change journal entry
        -> local workspace process receives/coalesces the generation request
        -> changed project/file shards are analyzed in parallel
        -> manifest and site are verified in staging
        -> `.docx` switches atomically to the new generation
        -> an open viewer receives an optional update notification
```

The enqueue path has no PSI parsing, graph construction, Wasm compilation, HTML rendering, or large JSON encoding. If
the process is unavailable, the plugin may start the packaged headless Java 17 process and enqueue once it is ready.
Failure to start live mode must not corrupt `.docx` or fail unrelated compilation/tests; status must explain that the
last valid generation is stale.

This mode provides eventual, observable freshness. It does not claim the new report is complete when the Gradle build
returns.

### Strict mode

Strict mode is for CI, publishing, and an explicit developer request. It waits for the requested generation, validates
the site and snapshot schema, and fails when freshness cannot be established.

### Why there are two modes

A report cannot be both guaranteed complete before Gradle exits and perform substantial work after Gradle exits.
Asynchronous mode protects the build's critical path; strict mode provides deterministic release evidence.

## Multi-workspace live agent

One user-level agent may serve several workspaces concurrently:

- Bind only to `127.0.0.1` on an operating-system-assigned port.
- Store its protocol version, process identity, chosen endpoint, and authentication secret beneath the Gradle user
  home, not in a fixed global port or repository source file.
- Derive `WorkspaceId` from the canonical workspace root plus stable root-build identity.
- Give every workspace its own bounded queue, cancellation scope, cache namespace, generation counter, and output
  lock.
- Coalesce superseded requests. A newer snapshot may cancel an obsolete generation before publication.
- Bound total parallelism and memory across workspaces; one large workspace must not starve every other workspace.
- Keep service calls read-only or generation-scoped. Never expose arbitrary filesystem paths to a browser client.
- Use a random local token and reject incompatible protocol/schema versions.

Proposed minimal common contract:

```kotlin
@Rpc
interface DocxLiveService {
    suspend fun enqueue(change: WorkspaceChange): EnqueueReceipt
    suspend fun status(workspaceId: WorkspaceId): WorkspaceStatus
    fun updates(workspaceId: WorkspaceId): Flow<WorkspaceUpdate>
}
```

`WorkspaceChange` carries identities, content hashes, changed/removed shard keys, and requested generation metadata.
It does not carry complete source files or the graph. `updates` is a non-suspending top-level `Flow`, as required by
kRPC. All payload types are immutable and `@Serializable` in `commonMain`.

## Performance architecture

The current multi-minute full regeneration is unacceptable. Wasm improves browser-side interaction, but it does not
fix redundant JVM analysis. Both sides need explicit performance work.

### Producer and generation pipeline

- Make every analysis task typed, deterministic, cacheable where valid, and configuration-cache compatible.
- Declare source inputs with relative path sensitivity and consume `InputChanges`.
- Cache immutable extraction results per file using content hash + analyzer version + schema version.
- Aggregate file results into independently replaceable project/source-set shards.
- Recompute cross-file/build indexes only for affected identities and their incident relationships.
- Use Gradle Worker API or the live agent's structured worker pool for independent CPU-heavy shards.
- Never use unbounded coroutines, threads, queues, or per-workspace heaps.
- Reuse SRCX's snapshot. DOCX must not rescan source merely to render it.
- Keep ordering canonical so unchanged logical inputs produce byte-identical output.
- Copy the precompiled viewer only when its artifact version changes.
- Publish with staging + verification + atomic rename/pointer update.

### Browser pipeline

- Load the small manifest and selected scope first.
- Decode/filter/layout graph work off the UI event path where browser APIs allow it.
- Use the approved D3 DOM/SVG path to establish exact Atlas parity. Introduce a WebGL2 rendering path only after a
  measured representative-workspace benchmark demonstrates that it is necessary, while preserving the same Kotlin
  state and interaction contract.
- Use spatial indexes for picking and selection; never scan every node on each pointer move.
- Batch GPU buffers and update only changed visibility/style ranges.
- Virtualize long findings, source, build, project, and symbol lists.
- Keep edges hidden or subdued by default in dense views; materialize emphasized incident edges for hover/selection.
- Cancel obsolete filtering/layout work when the selection changes.
- Do not eagerly deserialize source content or relationship occurrences outside the active detail.

### Initial measurable gates

Measure on the committed representative composite workspace, recording hardware and cold/warm state:

- Unchanged normal build DOCX overhead: p95 <= 250 ms.
- Enqueue acknowledgement with a warm agent: p95 <= 100 ms.
- One changed source file to atomically published warm generation: p95 <= 3 s.
- One changed project: target <= 10 s and must not trigger unrelated project extraction.
- Cold full representative workspace: initial release gate <= 90 s, followed by a documented path toward <= 30 s.
- Viewer first useful scope: <= 2 s on the representative generated report.
- Build/project/source/filter interaction after data is loaded: p95 <= 100 ms.
- Pan/zoom/drag on the representative dense graph: target 60 fps, with no multi-second main-thread stall.
- Peak agent and browser memory must be measured and bounded before release.

These are release gates, not marketing claims. Add repeatable benchmark tasks and retain results in CI artifacts.

## Gradle plugin contract

Keep one-command lifecycle tasks. Do not require users to invoke lint, detekt, or individual subproject tasks.

Planned tasks:

- `docx-plan`: existing deterministic serialized DSL plan.
- `docx-snapshot`: materialize/validate the renderer-neutral input when SRCX is available.
- `docx-enqueue`: perform the bounded asynchronous handoff.
- `docx-site`: synchronously produce and verify a complete generation.
- `docx-open`: ensure a local preview endpoint exists and open `.docx/index.html` through it.
- `docx-status`: print active/stale generation and agent state.
- `docx-stop`: unregister this workspace; stop the shared agent only when nothing else uses it.

The repository's root `build` task remains the comprehensive verification entry point. Consumer builds must not compile
`docx-web`; they copy the already-built production distribution bundled with the DOCX release.

## DSL direction

The existing preset, output, scope, and feature DSL is the baseline. Extend it with update/performance policy without
making users understand the transport:

```kotlin
// settings.gradle.kts
plugins {
    id("zone.clanker.gradle.srcx") version "<version>"
    id("zone.clanker.gradle.docx") version "<version>"
}

docx {
    preset.set(DocxPreset.FULL)
    autoGenerate.set(true)

    output {
        directory.set(".docx")
        formats.set(setOf(DocxOutputFormat.HTML))
    }

    scope {
        builds.includeAll()
        projects.includeAll()
        sourceSets.include("main", "test")
    }

    features {
        findings { enabled.set(true) }
        symbols {
            enabled.set(true)
            full()
            includeDisconnected.set(true)
        }
        dependencyInjection {
            enabled.set(true)
            frameworks.set(
                setOf(
                    DependencyInjectionFrameworkSelection.DAGGER,
                    DependencyInjectionFrameworkSelection.HILT,
                ),
            )
        }
    }

    updates {
        mode.set(DocxUpdateMode.ASYNC)
        liveReload.set(true)
        maxParallelism.set(4)
        debounceMilliseconds.set(250)
    }
}
```

`updates` is planned, not currently implemented. Defaults must be conservative and derived from available processors
and memory rather than assuming every machine can run the same concurrency.

When SRCX is absent, DOCX may render only capabilities supplied by another compatible `workspace-report-model`
producer. It must fail or warn according to DSL policy when an enabled feature requires missing facts; it must not
fabricate them.

## Implementation phases

### Phase 0: stabilize the extraction already in progress

- Finish SRCX/DOCX/model module boundaries without losing existing analysis facts.
- Complete snapshot invariants, deterministic serialization, schema versioning, and compatibility tests.
- Preserve Java 17 and make root `build` the only required local verification command.

### Phase 1: first static Wasm vertical slice

- Add `docx-web` and package its production Wasm distribution into DOCX.
- Render workspace/build/project navigation, findings, and one scoped graph from snapshot shards.
- Add `docx-site` and `docx-open`.
- Match the generated SRCX Atlas shell and graph interaction contract exactly before migrating every legacy report
  feature.

### Phase 2: incremental and sharded generation

- Introduce file/project caches, incremental invalidation, deterministic shard manifests, and atomic generations.
- Add benchmark fixtures with thousands of symbols.
- Remove the monolithic embedded report payload after feature-parity validation.

### Phase 3: optional live workspace process

- Bind `docx-live-contract` into `docx-service` using kRPC 0.10.3 over loopback Ktor WebSockets.
- Implement multi-workspace registration, bounded scheduling, coalescing, status, reconnect, and stale-report behavior.
- Wire asynchronous enqueue after a successful producer snapshot.

### Phase 4: migration and hardening

- Reach feature parity for sources, exact relationship evidence, cycles, findings, and DI graphs.
- Add accessibility, browser compatibility, memory, interaction, and visual regression gates.
- Deprecate SRCX-owned HTML only after DOCX passes parity and performance criteria.

## Acceptance criteria

- `./gradlew build` succeeds under JetBrains Java 17 and validates every repository module.
- No module manually parses JSON; all snapshot, manifest, plan, status, and RPC payloads use Kotlin serialization.
- No consumer build compiles Wasm or installs npm dependencies.
- A completed `.docx` generation works from a static host with the live agent stopped.
- `docx-open` is a one-command local experience and does not require a user-selected port.
- Two workspaces can build and refresh concurrently without output, cache, queue, port, or authentication collisions.
- A failed/cancelled generation leaves the previous site intact and visibly reports stale/failed status.
- The browser requests only required shards for the active scope and remains responsive on the benchmark dataset.
- The DOCX shell and graph pass visual and interaction parity checks against the generated `.srcx/site/index.html`
  reference at wide, compact, fullscreen, and open-detail states.
- The published distribution contains byte-identical canonical `theme.css` and ordered `dashboard.css` assets plus
  the approved local D3 7.9.0 runtime; it does not contain the legacy `architecture-graph.js` controller.
- Async mode adds only the bounded enqueue cost to a normal build; strict mode is explicit.
- The benchmark gates above are automated and reported before replacing the existing report.
- Existing source evidence remains exact: every displayed occurrence links to its serialized file and valid line.
- The public snapshot schema has round-trip, unknown-field, compatibility, deterministic golden, and adversarial
  validation tests.

## Non-goals and guardrails

- Do not run report analysis on every browser interaction.
- Do not send the full graph over RPC.
- Do not make a fixed local server port part of the contract.
- Do not make compilation or tests wait for asynchronous rendering.
- Do not launch unmanaged work inside a Gradle BuildService and assume it survives build completion.
- Do not add a database before content-addressed file shards are benchmarked and shown inadequate.
- Do not upgrade the entire repository toolchain merely to adopt an optional library; isolate compatibility when
  necessary.
- Do not use Java 21, hardcoded Java installations, manual JSON parsing, or unapproved libraries.
- Do not remove the last-known-good report until its replacement is verified.

## First implementation checkpoint

The main implementation thread should begin with Phase 0 and deliver a single tested vertical slice rather than all
modules at once:

1. Audit the current dirty extraction and preserve unrelated work.
2. Make `workspace-report-model` lossless for the current SRCX report and lock its schema tests.
3. Complete the existing DOCX plan DSL and add the planned update policy types without starting a server.
4. Add a minimal `docx-web` Wasm application that reads a tiny serialized fixture and renders one build/project graph
   inside the exact Atlas shell using the approved D3 7.9.0 interop boundary.
5. Package that compiled distribution into `docx-gradle-plugin` and implement `docx-site`/`docx-open`.
6. Run the repository root `./gradlew build` on Java 17.
7. Measure the baseline before implementing incremental shards and kRPC live mode.

Do not begin Phase 3 until the static site, schema, and performance baseline are proven. RPC is an optional delivery
mechanism, not a prerequisite for viewing DOCX.
