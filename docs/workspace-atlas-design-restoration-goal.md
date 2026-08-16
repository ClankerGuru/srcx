# Workspace Atlas Design Restoration Goal

## Objective

Restore the established SRCX/DOCX report experience and improve only the interactive Workspace Atlas chart inside
that existing design. The current full-screen map replacement is not acceptable: it removed the surrounding report,
notes, explanations, navigation context, and visual hierarchy. The finished report must look and read like the
original report while the Atlas chart becomes smoother, scalable, and Google-Earth-like in its navigation behavior.

This file is the authoritative execution contract. Continue until every completion condition is proven.

## Binding baseline

The user-designated design and document-structure baseline is the published DOCX viewer:

- version: `0.48.0-dev-001`;
- Maven-local plugin JAR:
  `/Users/slop/.m2/repository/zone/clanker/plugin-docx/0.48.0-dev-001/plugin-docx-0.48.0-dev-001.jar`;
- plugin JAR SHA-256: `09883529525a3ea3c00c9eac08009d2bcf1fb33bddaec607e56f1824e62c0d32`;
- embedded `docx-web-distribution.zip` SHA-256:
  `ef172bb80be8f15003aa4bec7b16ed3d37109c6e8112597aa2a7e3c38b3082ee`.

This artifact is authoritative because the user explicitly selected `0.48.0-dev-001`. Later development versions
must not be substituted as the visual baseline even if they appear newer or structurally similar.

The baseline is binding for the **entire visible output**, including the report shell, document structure, visual
language, surrounding content, and the Atlas chart's established look and feel. The `dev-001` Atlas presentation was
accepted and must not be visually reinvented. This is not an instruction to roll its implementation or data
architecture back to `dev-001`: preserve the new bounded, typed, canonical, lazy, worker-backed guarantees beneath
the accepted presentation.

The binding view is the normal default **embedded report view** from `dev-001`: the reader begins at the report
header, scrolls through the report's summary, notes, health/build/project/finding/cycle/evidence content, encounters
Workspace Atlas as one section within that document, and may explicitly enter Atlas fullscreen. It is not the
fullscreen Atlas view and not the `dev-006` map-only page.

Build a reproducible reference site by combining the exact `dev-001` viewer distribution with compatible captured
report data in an isolated directory. Serve it separately from the implementation under repair. Retain its URL,
artifact hashes, full-page screenshots, viewport screenshots, and interaction notes as comparison evidence.

## Product boundary

- Preserve the original report shell, header, sections, prose, notes, legends, metrics, disclosures, and evidence.
- Preserve the existing warm paper-and-ink neo-brutalist visual language, typography, spacing, borders, shadows, and
  information density.
- Do not replace the whole report with a map.
- Do not hide the report's non-Atlas sections with CSS or remove them from normal document flow.
- Restrict functional and performance changes to the Workspace Atlas chart viewport, its directly associated chart
  controls, and the underlying data/runtime paths required to support them. This is not permission for a visual
  redesign of the chart or report.
- Do not redesign the chart's accepted visual identity. Regions, labels, routes, colors, borders, spacing, typography,
  paper/ink treatment, and the relationship between the chart and the document must remain recognizably `dev-001`.
- Controls may change modestly where required to expose the new hierarchy, layers, filters, fullscreen, and navigation,
  but they must look native to the same report rather than like a replacement application.
- Reuse the current typed data, bounded graph service, exact evidence, search, session, Canvas, and worker work where
  it improves the chart without changing the report's established identity.
- Do not use Python. Use the Kotlin/Gradle/JavaScript/CSS implementation already owned by the project.

## Required experience

### Restore the report

1. Recover the established report composition visible before the full-screen-map replacement:
   - report header and summary;
   - explanatory notes and guidance;
   - health, build, project, finding, cycle, and evidence sections;
   - the original readable section widths and vertical document flow;
   - the original Atlas heading, description, legend, and surrounding context.
2. Keep the Atlas embedded in that report by default. Fullscreen is an explicit reversible chart action, not the
   document's permanent layout.
3. Ensure closing fullscreen returns to the exact report position, dimensions, focus, and surrounding notes.
4. Remove CSS rules that blanket-hide report sections or force the entire application into a permanent `100dvh`
   map-only surface.

### Extend and accelerate the accepted Atlas

1. Preserve the accepted `dev-001` Atlas appearance while extending it into a Google-Earth-style semantic map
   embedded inside the document:
   - workspace/application group is the world;
   - builds are major regions;
   - projects are subregions;
   - source sets, packages, files, types, and members appear through semantic drill-down;
   - relationship routes and evidence remain bounded and truthful.
2. This is one continuous map surface, not a wizard, a sequence of separate pages, or several disconnected views.
   Zooming and opening a region progressively reveals deeper semantic detail in place. Zooming out restores broader
   context. Levels may coexist when useful, with label/detail density adapting smoothly to camera scale.
3. Embedded and fullscreen are two presentations of the same live Atlas session. Entering fullscreen enlarges the
   chart without resetting camera, scope, filters, layers, selections, history, or evidence. Exiting returns that
   exact state to the embedded chart and restores the document's scroll position and focus.
4. The embedded viewport must feel fully interactive: direct grab-to-pan, pointer-centered wheel/pinch zoom, smooth
   animated fly-to, Fit/Home, semantic open/drill, Back/Forward, hover, selection, multi-selection, resizing, and
   evidence inspection. Interaction must remain responsive while the surrounding report continues to scroll
   normally; the chart captures wheel/pointer input only when the gesture is clearly intended for the map.
5. Every visible region must have a readable semantic name by default. A user must never encounter anonymous empty
   boxes. Labels, boundaries, relationships, and detail appear progressively without visual discontinuities.
6. Fit must use the available chart viewport at every supported aspect ratio. It must not leave a small island in a
   large empty canvas.
7. Pan, wheel zoom, button zoom, fit, reset, keyboard navigation, node selection, multi-selection, box selection,
   drag, container resize, semantic open/drill, Back/Forward/Home, and fullscreen must work with visible feedback.
8. Cursor affordances must clearly communicate panning, dragging, resizing, and selectable elements.
9. Preserve scope while loading evidence. Evidence drawers must expose notes, exact source, occurrence paging, and
   relationship explanations without resetting the map.
10. Search must preserve the established search presentation, support typed categories, multi-selection, preview,
   and fly-to without silently replacing scope.
11. Large build/project/source-set lists must remain virtualized and bounded.
12. Add the requested semantic layers without visually replacing the original chart: builds, projects, source sets,
    package prefixes, files, classes/types, members/methods, problems, cycles, and captured relationships. Layers that
    are unavailable in captured data must be visibly unavailable rather than fabricated.
13. Layer and filter controls must compose. A user can keep structural context while showing or hiding files,
    classes/types, members, problems, cycles, and relationship families. Enabling a layer must not trigger unbounded
    loading, create thousands of controls, or freeze the page.
14. Preserve the useful interaction behavior from `dev-001` unless an explicit requirement replaces it. Any control
    change must have a concrete reason, retain familiar placement/visual language where practical, and be verified
    against the reference rather than justified as a general redesign.

### Do not restore removed anti-patterns

Restoring the `dev-001` report does not authorize restoring obsolete Atlas internals or behavior. Specifically:

- do not restore unbounded workspace/build/project loading or serialize the whole workspace into the browser;
- do not restore thousands of graph records as DOM/SVG elements or synchronous full-graph layout on the main thread;
- do not restore duplicated mutable authority for scope, camera, filters, selection, layout, or history;
- do not make search, evidence, or drill-down silently replace scope or reset the camera;
- do not use sampled relationship IDs as a substitute for exact, pageable evidence;
- do not discard cancellation, stale-response rejection, virtualization, byte-bounded caches, lazy source loading,
  stable semantic identity, Canvas rendering, or worker-based layout;
- do not restore anonymous boxes, abrupt level swaps, disconnected per-level screens, or controls that only mutate
  hidden state without visible map feedback;
- do not make fullscreen the default document layout or implement embedded and fullscreen as separate sessions.

When the historical presentation conflicts with a removed anti-pattern, preserve the historical visual result and
reader workflow while implementing it through the current bounded architecture.

## Responsive requirements

Verify the real rendered report, not only source strings or hidden DOM state, at all of these viewport classes:

1. Ultra-wide desktop: `2048x858` (matching the reported failure).
2. Standard desktop: `1440x900`.
3. Compact desktop/tablet: `1024x768`.
4. Narrow/mobile: `720x900`.

At every size:

- the original report remains readable and scrollable;
- the embedded chart has an intentional height and fills its own viewport;
- labels remain readable;
- HUD controls do not become microscopic, overlap, clip, or create horizontal page scrolling;
- the chart can be panned and zoomed;
- Layers/Filters/Scope/Search sheets remain usable;
- notes, legends, and explanatory content remain visible outside the chart;
- fullscreen uses the whole screen and exits cleanly back into the report.

Use CSS `clamp()`/container-aware sizing and explicit responsive layouts where appropriate. Do not solve ultra-wide
screens by globally scaling the entire page or by stretching line lengths without bounds.

## Visual fidelity workflow

1. Treat the previously published working report—not the current replacement—as the authoritative visual and
   structural baseline. Locate its exact Maven-local/plugin artifact, generated distribution, report output, Git
   revision, or retained browser artifact. Record the version and SHA-256 provenance used for comparison.
2. Run or serve that exact earlier publication. Do not rely on memory, source inspection alone, or a single static
   screenshot.
3. Use the earlier report as a reader would:
   - scroll from the header through the entire document and back;
   - open and close its disclosures;
   - use its report navigation;
   - inspect its notes, prose, legends, tables, metrics, charts, findings, and evidence;
   - exercise its embedded Atlas controls and detail drawer;
   - enter and exit its fullscreen behavior if present.
4. Capture full-page and viewport screenshots of the earlier publication at the required widths before editing the
   restored experience.
5. Compare the restored report and earlier publication side by side, section by section. Any non-Atlas difference
   must be explicitly justified by an existing user requirement; otherwise preserve the earlier behavior and design.
6. Capture and inspect screenshots at all four required viewport sizes in embedded-report mode.
7. Capture and inspect ultra-wide and standard fullscreen screenshots.
8. Capture and inspect at least these interaction states:
   - report overview with Atlas embedded;
   - build selected;
   - project/source-set drill-down;
   - search results open;
   - relationship evidence drawer with source lines;
   - fullscreen chart;
   - restored report after fullscreen exit.
9. Do not accept screenshots containing blank/anonymous regions, tiny unreadable controls, clipped sheets, excessive
   unused chart space, or missing report notes.

## Responsiveness and performance

The report must feel immediate during real use, not merely become eventually correct.

1. Measure cold report readiness, warm chart interaction, build/project/source-set selection, semantic drill-down,
   search result display, evidence opening, fullscreen entry/exit, and resize settlement.
2. Record pointer-to-visible-update latency for pan, wheel zoom, button zoom, selection, and drag.
3. Use trusted browser events for pointer, wheel, keyboard, resize, scroll, and fullscreen verification.
4. Exercise continuous scrolling through the full report and confirm the page does not hitch, jump, lose its place,
   or allow the chart to steal document scroll unexpectedly.
5. Exercise repeated pan/zoom/resize/drill operations and at least 100 chart mount/unmount cycles. Measure long tasks,
   frame intervals, network transfer, and retained heap.
6. Keep graph decoding and full hierarchy layout off the main thread where Worker support exists. Preserve bounded
   requests, cancellation, stale-response rejection, virtualization, byte-bounded caches, and lazy evidence/source
   loading.
7. Establish explicit measured acceptance targets from the existing execution/performance contract and report every
   miss honestly. Do not convert a missed performance target into a non-required informational gate merely to finish.
8. Inspect performance at the actual ultra-wide and standard report layouts; a passing small fixture alone is not
   sufficient.
9. Establish an early performance baseline from `dev-001`, identify the measured sources of its slowness, and change
   the underlying loading, projection, layout, and drawing paths rather than masking delays with empty placeholders.
10. The first usable implementation checkpoint must restore the full `dev-001` report and accepted Atlas appearance
    with real labels and working pan/zoom before adding every advanced layer. Subsequent checkpoints add layers and
    controls incrementally so visual divergence or performance regressions are caught immediately.

## Validation and proof

1. Add regression tests proving non-Atlas report sections are visible and the Atlas remains embedded by default.
2. Add browser interaction assertions for actual camera movement after pan and scale change after wheel/button zoom.
3. Assert Fit occupies a meaningful percentage of the available chart viewport at `2048x858` and `1440x900`.
4. Assert visible semantic nodes have nonempty rendered labels under default layers.
5. Assert the report scrolls in embedded mode and does not scroll horizontally at every target viewport.
6. Assert fullscreen and exit restore document scroll position and focus.
7. Run complete model, SRCX plugin, DOCX plugin, web, index, service, and live-contract gates.
8. Publish a new unique development version after production gates are green. Do not overwrite `0.48.0-dev-006`.
9. Regenerate FooBar from that exact publication in a fresh Gradle process and verify installed asset hashes.
10. Start a fresh service and run the trusted browser workflow against the regenerated live report.
11. Retain and inspect all required screenshots and metrics.
12. Stage the requested source and consumer changes with clean cached diff checks. Do not commit or push.

## Completion conditions

Do not mark this goal complete until all of the following are true:

- The report visibly matches the established pre-replacement design outside the Atlas chart.
- The report's notes, prose, legends, and non-Atlas sections are present, visible, and usable.
- The Atlas is embedded by default and fullscreen only on request.
- The Atlas is labeled, pannable, zoomable, selectable, resizable, and semantically drillable.
- Ultra-wide `2048x858`, standard `1440x900`, compact `1024x768`, and narrow `720x900` browser workflows pass.
- Required embedded, interaction, evidence, fullscreen, and fullscreen-exit screenshots exist and have been visually
  inspected at original resolution.
- Complete source gates are green.
- A new unique version is coherently published and FooBar is regenerated from it.
- The regenerated live report passes the trusted browser workflow.
- Both worktrees contain the intended staged changes with clean cached diff checks.
- The final report identifies the live URL, publication version/hash, generation ID, commands, gate results,
  screenshot paths, staged-file summary, and any honest remaining limitation.

## Operating constraints

- Preserve all user changes.
- Do not restore a different historical design; restore the actual accepted report design from repository evidence.
- Do not weaken bounded-loading, exact-evidence, canonical-session, stable-identity, or worker guarantees.
- Do not use Desktop, AWT, Swing, or Python.
- Do not add assistant attribution or branding.
- Provide frequent visible progress and show real screenshots during implementation.
- During execution, provide a concrete progress report at least every 15 minutes. Each report must state what is
  visibly working, what was built or tested, the current blocker, the next action, and paths to any new screenshots
  or browser artifacts. Do not go silent during a long build or browser run.
- Keep a runnable checkpoint available as work progresses. Do not wait until the end to build or visually inspect the
  product. After restoring the baseline presentation, build and open it at the required viewport sizes before making
  further large changes.
- Do not claim completion from source inspection, DOM attributes, or tests alone. Completion requires the built,
  regenerated report to be opened, scrolled, manipulated, and visually compared with `dev-001` at original
  screenshot resolution.
