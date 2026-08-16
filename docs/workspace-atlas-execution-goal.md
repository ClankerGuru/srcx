# Workspace Atlas Execution Goal

## Objective

Complete the implementation of
`/Users/slop/dev/clanker/foo-bar-workspace-repos/dev/srcx/docs/workspace-atlas-semantic-map.md`
end to end. Continue from the current dirty worktrees; do not restart or discard existing work.

The work is complete only when the current production sources compile and pass their gates, a new unique local
plugin version is published, FooBar is regenerated from that exact publication, and the regenerated report is
verified interactively in a real browser.

## Worktrees

- Source: `/Users/slop/dev/clanker/foo-bar-workspace-repos/dev/srcx`
- Consumer: `/Users/slop/dev/clanker/foo-bar-workspace`

Both worktrees contain intentional user changes. Preserve them. Do not reset, clean, overwrite, or discard
unrelated modifications.

## Current checkpoint

The following implementation is already present and must be retained and completed:

- Canonical `AtlasSession` ownership for scope, filters, inspection, camera, layout, and history.
- Continuous semantic hierarchy from workspace through build, project, source set, package, file, type, and member.
- Virtualized multi-select Build, Project, and grouped Source Set sheets.
- Composable layers, typed search, relationship filters, semantic zoom, history, drag, resize, and fullscreen HUD.
- Bounded static and SQLite-backed live graph slices and mounted service graph routes.
- Stable symbol identity and exact UTF-16 declaration and occurrence ranges.
- Generation-pinned exact declaration, reverse-usage, and relationship-occurrence evidence with bounded keyset pages.
- Content-addressed lazy source bodies, static evidence artifacts, and byte-bounded caches.
- Canvas renderer and off-main-thread layout worker with cancellation and fallback.
- Trusted Chrome/CDP proof infrastructure for native fullscreen, screenshots, transfer data, long tasks, frame
  intervals, and mount/unmount heap retention.

Exact-evidence and worker-focused gates are green. The current immediate blocker was exposed by the production
browser smoke: build selection reaches the canonical Canvas host
(`data-docx-atlas-selected-build-ids=build:fixture`) while legacy root interaction-probe attributes remain stale.
Repair the canonical-session-to-probe publication path without reintroducing duplicate state ownership, then
continue through every remaining gate below.

## Required execution

1. Reconcile the unfinished worktree and finish exact-evidence runtime integration and architecture tests.
2. Finish the Canvas selection-presentation contract:
   - solid primary badges;
   - numbered secondary badges and double outlines;
   - hover halo and search-preview marker;
   - saturated selected-neighbor routes with endpoint markers;
   - ancestor header and containment tint;
   - dashed, strongly dimmed context-only endpoints.
3. Run and fix the complete source gates, including model, SRCX plugin, DOCX plugin, web, index, service, and live
   contract checks. Do not stop at focused tests.
4. Run the trusted real-Chrome fixture proof and inspect the retained wide, compact, native-fullscreen, and
   evidence-open screenshots and JSON/CSV measurements.
5. Publish the unique local version `0.48.0-dev-006` only after the production gates are green. Publish the model,
   SRCX plugin and marker, and DOCX plugin and marker. Ensure the DOCX publication embeds the newly built web
   distribution.
6. Verify Maven-local artifacts and metadata contain no dependency on the previous development version. Verify
   that the distribution ZIP hash equals the embedded ZIP in both the built and Maven-local DOCX plugin JARs.
7. Regenerate `/Users/slop/dev/clanker/foo-bar-workspace/.docx` using the exact published version from a fresh
   Gradle process. Assert that the generation ID changes and that the manifest includes the overview bundle,
   search catalog, evidence catalog, layout worker, layout core, Canvas map, and all content-addressed assets.
8. Verify every installed report asset against the just-built distribution by path and SHA-256 hash.
9. Start a fresh headless DOCX service without destroying the currently working service first. Mount the regenerated
   FooBar report and verify status, static assets, graph queries, search, exact declaration evidence, reverse usage,
   and relationship-occurrence paging.
10. Run the trusted Chrome proof against the regenerated live FooBar URL. Exercise scope sheets, grouped source-set
    selection, semantic zoom, layers, filters, global search, exact source evidence, relationship evidence,
    multi-selection, history, drag/resize, and native fullscreen.
11. Retain and inspect final live wide, compact, native-fullscreen, and evidence-open screenshots plus performance
    artifacts. Report every measured gate honestly; do not claim an unmeasured gate passed.
12. Stage all requested source and consumer changes. Run cached diff checks in both worktrees. Do not commit or push
    unless the user explicitly asks.

## Completion conditions

Do not mark this goal complete until all of the following are true:

- The complete source gate is green, or any genuine remaining failure is reported with exact evidence.
- `0.48.0-dev-006` exists coherently in Maven local and contains the current web distribution.
- FooBar has a new generation produced by that exact version.
- Served assets match the current distribution byte for byte.
- The regenerated live FooBar report passes the trusted Chrome workflow.
- Final screenshots and metrics exist and have been inspected.
- Both worktrees contain the intended staged changes with clean cached diff checks.
- The final report includes the exact live URL, commands executed, test results, publication hashes, generation ID,
  retained artifact paths, measurements, staged-file summary, and any honest limitation.

## Operating constraints

- Preserve all existing user changes and the current neo-brutalist visual language.
- Use one continuous map surface; do not reintroduce the legacy report layout or exclusive-lens model.
- Keep browser data bounded; do not load every project shard for build selection or mount all repository entities.
- Evidence loading must never change map scope, filters, camera, layout, or document scroll.
- Do not use Desktop, AWT, or Swing.
- Do not add attribution or assistant branding to repository content.
- Provide visible progress updates while the goal is active.
