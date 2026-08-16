# DOCX workspace service

The DOCX viewer has two independent operating modes. `docx-site` publishes a self-contained directory that can be
copied to any static host and opened without Gradle, a source checkout, SQLite, or a JVM. The optional
`docx-service` process adds stable workspace URLs, multi-workspace discovery, generation notifications, and bounded
SQLite queries. It never analyzes source code and never runs Gradle on its own.

## Generate, serve, and update

```text
srcx-context -> docx-site -> generated .docx/ directory
                                  |               |
                                  | static host   +-> browser (no service)
                                  v
                         optional docx-service -> browser + bounded queries/events
                                  ^
                    watches manifest/status publication
```

Generating SRCX facts remains the expensive source-analysis step. `docx-site` projects those facts into an atomic
static generation. A running service watches only each registered generated directory; when its manifest generation
changes, the service imports the immutable shards into SQLite, switches the active indexed generation, and emits an
SSE update. If neither source facts nor the generated site changed, starting the service does not rebuild either one.

Build and start the foreground service from this repository:

```bash
./gradlew :docx-service:installDist
./docx-service/build/install/docx-service/bin/docx-service serve
```

The default host is `127.0.0.1` and the default port is `0`, so the operating system chooses a free loopback port.
The process prints the real URL and writes it with a generated bearer token to
`~/.srcx/docx-service/endpoint.json`. It is a headless terminal service: it sets `java.awt.headless=true`, does not
call `Desktop.browse()`, and does not create an AWT or macOS Dock application. Ctrl-C performs a clean shutdown and
removes the endpoint file if this process still owns it. A service exists only while that command (or an OS service
manager running it) is alive.

Register an already-generated site without invoking Gradle:

```bash
docx-service register --id foo-bar --site /absolute/path/to/foo-bar/.docx
docx-service list
docx-service unregister --id foo-bar
```

Alternatively, a consumer build can generate and register in one explicit command:

```kotlin
docx {
    service {
        workspaceId.set("foo-bar")
        // url/token are optional when the default endpoint file discovers a local service.
    }
}
```

```bash
./gradlew docx-publish
```

`docx-publish` depends on `docx-site`, then registers the published directory. It does not make the service mandatory:
`docx-site` and static deployment remain valid on their own.

## One process or several

One service process is designed to host many generated repositories at `/w/{workspaceId}/`. Stable IDs are supplied
at registration and are deliberately not random. To run independent processes, give each one a different registry,
endpoint, and SQLite file; `--port 0` safely gives each process its own port:

```bash
docx-service serve --port 0 \
  --registry /var/tmp/docx-a/registry.json \
  --endpoint /var/tmp/docx-a/endpoint.json \
  --index /var/tmp/docx-a/index.sqlite

docx-service serve --port 0 \
  --registry /var/tmp/docx-b/registry.json \
  --endpoint /var/tmp/docx-b/endpoint.json \
  --index /var/tmp/docx-b/index.sqlite
```

The registry is file-locked, so two processes cannot accidentally own the same registration set. For background
operation, run the installed executable under `launchd`, `systemd`, or another process supervisor; no desktop
integration is required.

## HTTP and index boundary

The first service API is intentionally small:

- `GET /healthz`
- `GET|POST /api/v1/workspaces`
- `GET|DELETE /api/v1/workspaces/{id}`
- `GET /api/v1/events` (server-sent generation updates)
- `GET /api/v1/workspaces/{id}/symbols?q=...&buildId=...&projectId=...&sourceSetId=...&kind=...&limit=...`
- `GET /api/v1/workspaces/{id}/relationships?buildId=...&projectId=...&sourceSetId=...&kind=...`
- `GET /api/v1/workspaces/{id}/findings?buildId=...&projectId=...&sourceSetId=...&severity=...`
- `GET|HEAD /w/{id}/...` (generated static files with ETag revalidation)

Registration and deletion require `Authorization: Bearer <token>`. The default endpoint file is written owner-only
where POSIX permissions are available. Loopback is the safe default; binding beyond loopback needs an explicit host
plus an authenticated TLS reverse proxy. The token boundary is ready for a stronger identity provider without
changing generated sites.

SQLite is an optional acceleration layer, not the source of truth. It imports versioned generated shards once and
serves bounded indexed queries, avoiding repeated parsing of large JSON files and avoiding unbounded browser frames.
If SQLite cannot open or a generation cannot index, the service reports index status while static files keep working.
The browser can also be deployed with no database at all. kRPC, CBOR, or another transport should be introduced only
when a measured bottleneck requires it; the current HTTP/JSON/SSE contract is already bounded and interoperable.

The JDK adapter uses a fixed worker count and a bounded accepted/request queue. When that queue fills, the dispatcher
applies caller-runs backpressure instead of allocating more threads or dropping accepted work. Live SSE subscriptions
are capped below the worker count so they cannot occupy every handler; excess subscribers receive HTTP 503 with a
short retry hint. `--http-threads`, `--queued-requests`, and `--event-subscribers` expose those bounds when a local
deployment needs tuning. A future coroutine-native Ktor CIO adapter may support many more idle streams, but changing
frameworks is not required for bounded correctness in this checkpoint.

## Package and JVM edges

The module has one-way domain dependencies, guarded by a Konsist test:

```text
service (Main/config/server composition)
  +-> client (CLI, endpoint discovery, HTTP client)
  +-> http (JDK HTTP adapter, static/SSE/query routes)
        +-> index (query port + SQLite adapter)
        +-> workspace (registry, watcher, events, generation lifecycle)
  +-> index -> workspace
```

`com.sun.net.httpserver` is confined to `service.http`; replacing it with Ktor later changes a real adapter boundary
instead of spreading framework types through registry and index code. Java file/NIO types are likewise kept at this
JVM executable's configuration, client, workspace-persistence, index, and HTTP-static-file edges.

`docx-live-contract` now owns the transport-neutral Kotlin/JVM and WasmJs `WorkspaceObservation` boundary: one
suspending catalog request and one cancellable update `Flow`. It deliberately contains no Ktor, socket, registry, or
database code. A later adapter can bind this contract to kRPC over Ktor WebSockets without changing registry,
generation indexing, or bounded query ports. kRPC is therefore an additive live protocol, not a replacement for the
HTTP engine, SQLite index, or independently deployable static report.

The contract pins both the kRPC Gradle/compiler plugin and the generic RPC core runtime at 0.10.3. The explicit core
version is required because the plugin does not add one to that bare contract-only dependency in this KMP aggregation
path. kRPC 0.10.3 supports the repository's Kotlin 2.3 line as well as Kotlin 2.4; an unrelated compiler upgrade is
not required for this boundary. Its published core metadata requires coroutines 1.10.2 and serialization 1.9.0, so
the repository's serialization consumers are aligned to 1.9.0 explicitly rather than relying on Gradle to reconcile
different transitive versions.

`kotlinx-io 0.8.1` is not adopted in this checkpoint. Its filesystem API is not yet a stable replacement for the JDK
surface this daemon needs, its source/sink objects are not a shared-thread synchronization mechanism, and it does not
replace `WatchService`, `FileLock`, POSIX permission handling, or `HttpExchange` integration. Wrapping those APIs in a
nominal abstraction would add code without creating a usable multiplatform core.

Workspace and generation IDs remain deterministic domain/content IDs so the same generated data keeps the same
identity. Event sequences and subscription cursors remain monotonic `Long` values because they describe ordering
inside one process, not global identity. The repository uses Kotlin 2.3, before Kotlin 2.4's UUIDv7 support, and this
domain does not require random or time-ordered UUIDs in either case.

This slice is read-only with respect to source repositories. Editing source, refactoring, Git control, and LSP/IDE
write operations require a separate authorization and correctness design; registration does not implicitly grant
those capabilities.
