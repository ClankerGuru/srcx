# Workspace Atlas Semantic Map

## Agent directive

Implement this brief completely in the SRCX repository. Treat it as the authoritative interaction,
architecture, performance, and acceptance contract for Workspace Atlas.

Before changing code:

1. Inspect the entire dirty worktree and identify unfinished Atlas work already present.
2. Preserve unrelated user changes. Do not reset, overwrite, or discard them.
3. Reconcile the interrupted workspace-lens, controller, source-catalog, evidence, and fullscreen edits into a
   compile-coherent baseline.
4. Report the exact files and behavior retained, replaced, or removed.
5. Use JetBrains JBR 17 for every Gradle process:
   `JAVA_HOME=/Users/slop/.sdkman/candidates/java/17.0.14-jbr`.

Continue autonomously through implementation, focused validation, publication under a new local development
version, consumer regeneration, and real browser verification. Do not stop at a mockup or a partial controller
rewrite. Do not use `Desktop.browse()`, AWT, Swing, or a Java desktop launcher. Do not mutate unrelated local
repositories when constructing scale fixtures.

The user experience must remain one continuous map at every checkpoint. Internal implementation slices must
not become separate user-facing modes, pages, or temporary replacement interfaces.

## Product outcome

Workspace Atlas is a full-viewport, continuously navigable map of a software workspace. The workspace is the
world. Application groups, builds, projects, source sets, packages, files, types, and members become progressively
visible as the camera moves deeper. The existing cream, black, accent-color, hard-border, square-control,
neo-brutalist visual language remains intact. The interaction model becomes spatial, smooth, searchable,
multi-selectable, and bounded at repository scale.

The map is the product. Controls float over it; they never consume permanent chart space or force page-level
horizontal or vertical scrolling.

## Critical assessment of the current implementation

The current control matrix behaves like a database form placed above a graph:

- Large build, project, and source-set cards turn repository size directly into horizontal scrolling.
- Scope, focus, inspection, graph filtering, and camera navigation are coupled.
- Files, Symbols, Problems, and Cycles are exclusive modes even though users need them as composable layers.
- Search filters the currently loaded frame instead of searching the complete indexed workspace.
- Duplicate source-set names are rendered once per project instead of as meaningful grouped choices.
- A large project frame can attempt to mount thousands of SVG elements.
- Parent rectangles are visual layout regions rather than fully interactive hierarchical containers.
- Selection state is divided between Wasm, DOM attributes, and the graph bridge.
- Some navigation paths reset scope, lens, viewport, or document scroll as a side effect of loading evidence.
- The all-workspace frame is not currently a truthful bounded representation for every layer.

Do not polish these structural problems in place. Replace them with the unified state and bounded map contract
below while retaining working source evidence, relationship evidence, fullscreen, and static-report behavior.

## One continuous hierarchy

Semantic zoom changes the level of detail inside the same map:

| Map level | Primary visible geometry | Rolled-up relationships |
| --- | --- | --- |
| Workspace | Application groups or builds | Build dependencies and cross-build source facts |
| Application group | Builds | Inter-build relationships |
| Build | Projects, variants, and tasks | Project and task relationships |
| Project | Source sets and package prefixes | Package and source-set relationships |
| Package | Files and types | File and type relationships |
| File or type | Classes, interfaces, objects, enums | Exact symbol relationships |
| Type or member | Methods, functions, properties | Exact calls, references, inheritance, and evidence |

Rules:

- Children remain spatially inside their authoritative ancestors.
- Hidden child relationships roll up to the nearest visible ancestors.
- Aggregated relations retain exact fact counts and bounded deterministic evidence samples.
- Zoom thresholds use hysteresis so entities do not flicker between levels.
- Manual expansion may pin a deeper level independently of geometric zoom.
- Stable semantic IDs preserve selection, history, and layout across level-of-detail changes.
- A missing fact family is shown as unavailable, not as an apparently measured empty result.

Application groups must be explicit configured facts. Algorithmically suggested groups may be supported later,
but they must be visibly labelled as derived suggestions. If no groups exist, the workspace contains builds
directly.

Tasks, variants, dependency upgrades, and other lateral facts are layers and relations. Do not force them into a
false containment hierarchy unless the Gradle producer captures an authoritative owner.

## Canonical session state

Create one immutable Atlas session model and one reducer/event loop. Keep these concepts independent:

```text
generation    current immutable report generation
camera        position, scale, viewport, and transition state
focus         the hierarchical location being explored
scope         selected IDs at each hierarchy level
inspection    primary selection, secondary selections, hover, and evidence
layers        enabled node, fact, problem, cycle, task, and relationship layers
filters       typed search, declaration, relationship, direction, and count filters
overlay       closed, search, scope chooser, layers, or relationship settings
gesture       idle, pan, zoom, box select, node drag, container drag, or resize
history       restorable backward and forward navigation entries
layout        calculated geometry plus user pins and overrides
request       revision, pending slice, cancellation, error, and settled state
```

State rules:

- Empty scope selection means All; never serialize a synthetic `all` entity ID.
- Effective descendants are derived hierarchically and deterministically.
- Projects are constrained by selected builds.
- Source sets are constrained by selected projects, then builds, then workspace.
- Selecting a source-set name such as `main` resolves to every matching source-set ID in the effective projects.
- Changing an ancestor removes impossible descendant selections atomically and makes the operation undoable.
- Scope changes the working set. Focus changes the camera context. Inspection highlights evidence. None silently
  mutates another.
- Loading evidence must not change scope, lens, camera, or document scroll.
- Obsolete requests are cancelled and their responses are discarded by revision and generation.

## Full-viewport map HUD

The map owns the viewport. Keep a small set of fixed overlays:

- Top-left: a global search and command field.
- Top-center: compact Builds, Projects, and Sources triggers with selection summaries.
- Top-right: Layers, Zoom in, Zoom out, Fit, Reset, Fullscreen, and Hide controls.
- Bottom-left: Back, Forward, Home, and the active hierarchy breadcrumb.
- Bottom or corner: bounded loading/progress status that never blocks map interaction.
- Right side: an evidence inspector only while an entity or relation is selected.

Use square buttons, hard borders, offset shadows, compact typography, and the existing palette. Do not replace
the visual language with Material pills or generic rounded cards.

The control chrome may fade when idle and return on pointer movement, focus, or keyboard input. In fullscreen,
Hide controls leaves only the map and one persistent Show controls affordance.

## Custom scope sheets

Builds, Projects, and Sources are disclosure triggers, not tabs or native dropdowns. Activating one opens a custom
anchored sheet inside the map. The same trigger, Escape, or an outside click closes it. Selecting an item does not
close it. Only one overlay sheet may be open.

Every scope sheet contains:

- One local search field.
- Selected entries pinned first.
- A virtualized list or grid with at most five visible rows.
- Compact multi-select buttons with selected, partial, and unselected states.
- A clear All action that empties that level's selection set.
- Deterministic keyboard navigation.
- No page-level or panel-level horizontal scrolling.

Project choices update from effective build selection. Source choices are grouped by source-set name across the
effective projects: one `main`, one `test`, one `commonMain`, and so on. The group displays its matched project
count and toggles the underlying set of stable source-set IDs. A partial state is shown when only some matching IDs
are selected.

Packages, files, types, and members do not need more permanent top-level buttons. Search and the active breadcrumb
open the same chooser pattern for deeper hierarchy levels.

## Global search and fly-to

Search is indexed workspace navigation, not merely a destructive filter over the loaded frame.

Group bounded, virtualized results by:

- Application group
- Build
- Project
- Source set
- Package
- File and file extension
- Class, interface, object, enum, and type
- Method, function, property, and symbol
- Task
- Dependency, problem, and cycle

Each result contains its short name, type, ownership breadcrumb, and useful relationship/problem badges.

Interaction:

- `/` focuses global search.
- Arrow keys navigate results.
- Enter selects and flies to the result.
- Shift+Enter or the platform additive-selection gesture adds a result to the current selection.
- Hover or keyboard focus previews a result without committing state.
- Escape closes search and restores focus.
- Prefixes such as `build:`, `project:`, `file:`, `class:`, `method:`, and `ext:kt` narrow categories quickly.
- Typing never hides the map by itself. Filtering the map to results is an explicit action.
- Search may temporarily reveal an entity below the current semantic-zoom threshold.
- A fly-to operation adds one restorable history entry.

## Composable layers and filters

Files, Symbols, Problems, Cycles, Tasks, Dependencies, Variants, and Upgrades are independently composable layers,
not exclusive lenses. This must support combinations such as:

- Classes with problems.
- Interfaces participating in cycles.
- Files with more than five outgoing relationships.
- Classes with more than five incoming dependents.
- Projects connected through task dependencies.
- Builds affected by an available dependency upgrade.

Add a Layers sheet with compact presets inspired by map-detail controls:

- Focus: minimal containment and the selected relationships.
- Explore: ownership, labels, and important relationships.
- Everything: all available facts within the bounded frame.
- Custom: the exact user-selected combination.

Relationship filtering supports type, direction, strict count threshold, and whether context-only inverse endpoints
remain visible. Problems and cycles remain independently toggleable from declaration-type filters.

## Map interaction contract

- Hover shows a temporary halo and relationship preview without mutating session state.
- Single click selects and highlights without moving the camera or page.
- Additive click toggles secondary selections.
- Shift-drag performs box selection.
- Double-click, Enter, or an explicit Open affordance drills into a container and fits it.
- Background click clears inspection only; it preserves scope, layers, and camera.
- Wheel and pinch zoom around the pointer.
- Background drag pans.
- Edge click opens exact evidence in the inspector and highlights the source line.
- Parent-header click selects the complete build, project, source set, or package and exposes rolled-up relations.
- Breadcrumb click navigates to an ancestor while preserving a history entry.
- Back and Forward restore camera, focus, scope, layers, filters, and inspection.
- No selection gesture changes document scroll position.
- Camera motion occurs only for explicit zoom, fit, fly-to, drill, history, or reset actions.

## Exact source evidence and reverse usage

Source location is a primary Atlas interaction, not supplemental metadata. Every selectable file, declaration,
symbol, member, and relationship must resolve to typed source evidence whenever that evidence was captured.

- Selecting a file opens its complete available source without changing map scope, focus, camera, or layers.
- Selecting a class, interface, object, enum, method, function, property, or symbol opens the owning file and
  highlights its complete declaration range. Multi-line declarations highlight every line in the range.
- Selecting a call or reference highlights every exact occurrence represented by the selected relationship. When
  occurrences span files, the inspector groups them by file and provides deterministic previous/next navigation.
- The active occurrence is visually strongest, receives `aria-current`, and is scrolled into view inside the
  evidence inspector without moving the document or map.
- Method and function inspection shows both the declaration and bounded categorized reverse evidence: callers,
  constructor invocations, overrides or implementations, and other references.
- Property inspection shows the declaration plus bounded reads, writes when they can be distinguished truthfully,
  type references, and other usages. If the producer cannot distinguish read from write, label the facts as
  references rather than guessing.
- Aggregate map relations retain stable underlying fact IDs so opening a rolled-up route can display every exact
  captured occurrence through a virtualized, paged evidence list.
- Missing source bodies, declaration ranges, or occurrence locations produce a precise unavailable state; never
  fabricate a line from a name or path.
- Highlighting uses source offsets or validated start/end line and column ranges from the snapshot. Line numbers
  alone are a compatibility fallback and must not silently claim column precision.
- Search fly-to, Problems, and Cycles use the same evidence resolver, so identical fact IDs always open identical
  files and ranges regardless of the path used to reach them.

The evidence path must be indexed rather than reconstructed by scanning loaded project shards. Maintain
generation-scoped maps from declaration ID to source range, relationship fact ID to occurrence range, target
declaration ID to incoming usage IDs, and source file ID to a lazy content blob. Browser state keeps only bounded
result pages and the currently viewed source body.

## Selection presentation

Do not rely on color alone:

- Primary selection: heavy black outline, accent fill, and a solid badge.
- Secondary selection: double outline and numbered badge.
- Hover: temporary outer halo.
- Search preview: crosshair or reduced-motion-safe pulse.
- Direct relationship neighbor: saturated route and endpoint marker.
- Context-only endpoint: muted fill and dashed outline.
- Ancestor of selection: tinted header and containment border.
- Partial grouped selection: striped or half-filled marker plus accessible partial text.
- Filtered-out entity: absent.
- Useful but out-of-focus context: strongly dimmed.

## Containment and editable layout

Every visible child belongs to real geometric ancestors.

- Child movement updates the parent's calculated bounds live with stable padding.
- Parent movement translates every visible descendant and its stored home.
- Parent resize changes its minimum user size; Auto-fit restores calculated sizing.
- Collision resolution occurs after a drag with an Undo action.
- Collapsed parents retain their internal layout.
- Manual positions, pins, sizes, and locks are stored separately from source facts under stable semantic IDs.
- Layout overrides may be local/session state initially and can later become shareable workspace metadata.
- Moving a class visually never silently edits source code. A future edit mode must preview and confirm an actual
  refactor as a separate authenticated command.

## Bounded data architecture

Use the typed bounded `WorkspaceGraphRequest` and `WorkspaceGraphSlice` family as the end-to-end query boundary.
Extend it only through focused, versioned value objects and truthful availability metadata.

Live mode:

- A generation-versioned SQLite index provides global hierarchy, FTS search, scope, relationship, problem, and
  cycle queries.
- Integer internal node and relationship keys support efficient joins; stable external IDs stay at boundaries.
- One service process may mount many workspaces under stable IDs and one OS-assigned or configured port.
- Live generation updates invalidate bounded slices without transferring full snapshots.

Static mode:

- The report remains deployable without Gradle or a permanent server.
- Small catalogs and bounded, immutable, content-addressed hierarchy/graph shards replace monolithic frames.
- Static and live modes expose equivalent query and interaction semantics.

Browser runtime:

- Kotlin/Wasm owns the canonical session, typed requests, response reconciliation, and interaction commands.
- Decode, response transformation, and layout run off the main thread where supported.
- Use Canvas or WebGL for high-volume geometry. Retain SVG only below a measured small-frame threshold.
- Keep a byte-bounded LRU for slices and evidence; never retain every visited project indefinitely.
- Source bodies are fetched lazily per evidence request and are not embedded in graph slices.
- D3 may remain useful for bounded layout math or small SVG frames, but it must not produce one large DOM subtree
  per repository fact.

Default slice limits should remain close to 400 nodes and 800 aggregated routes. Hard responses must not exceed
approximately 2,000 nodes and 5,000 routes. Large repositories reveal more facts through semantic zoom, search,
expansion, and new bounded requests—not through an unbounded page.

## Implementation order inside the same surface

These are internal vertical cuts. Every completed cut must preserve one working map rather than expose a temporary
alternate UI.

1. Reconcile the interrupted draft and make the current source tree compile-clean.
2. Establish the canonical session reducer and remove split ownership between Wasm, DOM attributes, and D3.
3. Replace large scope rails with the floating HUD and virtualized scope sheets.
4. Implement semantic zoom and rolled-up relations for workspace, build, and project.
5. Integrate bounded SQLite and static hierarchy/search queries.
6. Extend hierarchy through source set, package, file, type, and member with lazy evidence.
7. Convert exclusive lenses into composable layers and typed relationship filters.
8. Move decode/layout off the main thread and add Canvas/WebGL rendering under measured thresholds.
9. Add camera history, keyboard/touch parity, user layout overrides, resizing, and Undo.
10. Publish a new local plugin development version, regenerate the FooBar consumer, start the headless service or
    static preview, and verify the real browser surface interactively.

## Functional acceptance scenarios

1. The initial workspace map fills the viewport with no page-level scrolling and no chooser open.
2. Builds opens only the build sheet; activating it again, clicking outside, or pressing Escape closes it.
3. Selecting two builds immediately constrains project choices in deterministic build/project order.
4. Selecting `main` once toggles all matching source-set IDs across effective projects; partial selection is visible.
5. Ten thousand projects mount only the visible five-row virtualized choices.
6. Searching for a class returns its ownership breadcrumb and Enter flies to it without resetting scope.
7. Selecting a node preserves document scroll and camera unless an explicit navigation command is used.
8. Zooming from workspace to member level progressively replaces aggregated geometry with descendants.
9. Selecting a project highlights its container, ancestors, and incoming/outgoing rolled-up relationships.
10. Problems and Classes can be enabled together to show classes with problems.
11. A strict relationship threshold retains exact opposite endpoints as context where requested.
12. Edge inspection displays every exact captured occurrence, highlights all lines in the active range, and
    navigates deterministically between occurrences and files.
13. File inspection displays complete available source content without changing map scope.
14. Method, function, and property inspection highlights the complete declaration and returns bounded indexed
    callers or usages with exact source ranges.
15. Back and Forward restore the exact prior camera, scope, layers, filters, and selection.
16. Dragging a child expands or repacks its parent; dragging a parent moves all descendants.
17. Fullscreen with hidden controls contains only the full-size map and the Show controls affordance.
18. The complete workflow is operable by keyboard, pointer, and touch with reduced-motion support.
19. Static and service-backed reports produce equivalent visible results for the same generation and request.

## Performance acceptance gates

Validate on a deterministic representative fixture and a real composite approaching 80 builds, 2,000 projects,
and 10 million lines of code.

- Cold first useful workspace map p95 at or below 2 seconds.
- Warm indexed search, layer, scope, and filter interaction p95 at or below 100 milliseconds.
- Cold drill/query p95 at or below 500 milliseconds for bounded slices.
- Pan and zoom target 60 frames per second with no input-blocking main-thread task over 50 milliseconds.
- No map action mounts all repository leaf entities or all scope choices.
- One source selection transfers only the required content blob.
- Warm declaration-to-source and relationship-to-occurrence lookup p95 is at or below 50 milliseconds.
- Opening a declaration with thousands of callers transfers and mounts only one bounded, virtualized result page;
  requesting more occurrences never rebuilds the graph frame.
- Rapid navigation cancels obsolete loads and only the final request may settle visibly.
- One hundred drill/back and mount/unmount cycles retain less than 10 percent additional browser heap.
- Browser heap remains within a documented bounded budget; service memory is measured and bounded independently.
- A one-file change does not rescan or rewrite unrelated projects.
- Doubling representative facts must remain close to linear producer/index time rather than reveal quadratic scans.
- Record raw samples for latency, long tasks, frame intervals, heap, process RSS, transfer bytes, decoded bytes, and
  generated artifact sizes.

## Browser and publication proof

Do not validate only source resources or a development fixture. The final proof must:

1. Build and test the current production Wasm distribution under JBR 17.
2. Publish a new, unique local plugin development version.
3. Update the FooBar consumer to that exact version without disturbing unrelated consumer changes.
4. Run SRCX/DOCX generation from a fresh Gradle daemon so the plugin classloader contains the new distribution.
5. Verify served asset hashes and required DOM markers match the just-built distribution.
6. Exercise native fullscreen, scope sheets, semantic zoom, global search, source evidence, relationship evidence,
   multi-selection, layers, history, and container manipulation in a real browser.
7. Capture wide, compact, fullscreen, and evidence-open screenshots plus timing/memory artifacts.

## Accessibility contract

- Scope triggers use disclosure semantics with `aria-expanded` and `aria-controls`.
- Search uses combobox/listbox semantics with a deterministic active descendant.
- Overlay sheets are labelled regions and restore focus to their trigger on close.
- Arrow keys navigate, Space toggles, Enter opens or flies, and Escape closes the active overlay.
- Touch targets are at least 44 by 44 CSS pixels.
- Selection, partial selection, availability, focus, and context have textual equivalents.
- Reduced-motion mode replaces animated flights and pulses with immediate transitions.

## External interaction references

Use these only as interaction references; do not copy Google Earth's visual design:

- Google Earth navigation: https://developers.google.com/maps/documentation/earth/navigate-the-globe
- Google Earth search: https://support.google.com/earth/answer/148081?hl=en
- Google Earth basemap settings: https://developers.google.com/maps/documentation/earth/basemap-settings?hl=en
- Google Maps controls and overlays: https://developers.google.com/maps/documentation/javascript/controls

## Completion report

At completion, report:

- The final package/module ownership and why it is coherent.
- The old interaction/state paths removed or replaced.
- Static and live data-flow diagrams.
- Exact test, publication, generation, server, and browser commands executed under JBR 17.
- Measured performance and retained artifacts, including any gate that did not pass.
- The exact local URL for the regenerated FooBar report.
- All staged files and any intentionally unstaged user-owned changes.
