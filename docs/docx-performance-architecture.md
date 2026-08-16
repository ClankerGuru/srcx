# DOCX performance architecture

DOCX supports two deployment modes that share the same generated site.

## Static snapshot

`docxSite` writes a self-contained HTML, Kotlin/Wasm, JavaScript, CSS, and data directory. The directory can be
served by any static host after generation. Gradle, the source checkout, and a JVM service are not required to
browse that snapshot. A static deployment does not change until a newer generated directory is published.

The browser loads the workspace catalog first and project data lazily. It must not load the full repository or
render every repository symbol in one DOM/SVG frame. Search and filters select a bounded visual frame while exact
details remain addressable by stable IDs.

## Live indexed workspace

The optional headless workspace service mounts multiple generated sites under stable workspace IDs. It watches each
registered site for an atomically published generation, keeps static serving available even if indexing fails, and
emits generation events to connected clients. One process can serve many workspaces; independent processes use
different registry, database, and port values.

SQLite is the durable query index for this mode. A generation is staged one project-shard transaction at a time,
then an active-generation pointer is switched only after every shard imports successfully. Source
bodies stay in lazy content files instead of being duplicated into every query record. Indexes cover the actual
interactive access paths: scoped symbol search, source-set and declaration filters, relationship endpoints, and
finding severity/scope. Query plans are tested so an accidental table scan fails before release.

SQLite is not the rendering engine and is not the only memory layer. The service may keep bounded hot adjacency and
search results in primitive in-memory structures, while the browser receives compact result frames. This avoids both
re-reading large JSON shards for every interaction and trying to turn 100,000 symbols into 100,000 SVG nodes.

## Update flow

```text
source edit
    -> SRCX extracts changed workspace facts
    -> docxSite atomically publishes a new static generation
    -> workspace service observes the generation
    -> SQLite imports immutable project shards
    -> active generation switches
    -> clients receive an update event and refresh bounded queries
```

The current static JSON transport remains the portable fallback. Binary transports such as CBOR or ProtoBuf should
only replace an API boundary after retained benchmarks show that encoding or transfer is the limiting cost; indexed,
bounded queries remove substantially more work than changing the encoding of an oversized payload.

## Scale acceptance

Scale is gated in two tiers. The representative tier has at least 100,000 symbols and two million logical source
lines. The target tier has at least 80 builds, 2,000 projects, and ten million logical source lines, with a deliberately
skewed distribution that includes large builds and projects. Both gates must record cold and incremental generation,
query p50/p95/p99, transferred bytes, long tasks, frame time, renderer memory, service RSS, index size/import time,
and memory after repeated workspace switches. The target tier must additionally prove that a one-file edit does not
rescan unrelated builds or projects and that parallel workers stay inside a configured memory budget.

Functional smoke tests and a visually dense screenshot are not performance evidence.
