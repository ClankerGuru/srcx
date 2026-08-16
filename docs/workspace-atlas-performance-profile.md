# Workspace Atlas performance profile

Run the opt-in repository-topology profile with:

```shell
./gradlew :docx-index:workspaceAtlasPerformanceProfile
```

Override the default 30 measured samples with `-Pdocx.performance.samples=<5..200>`. The task uses the build's
Java 17 toolchain, generates an isolated deterministic site, creates a fresh SQLite database, and always executes;
it is not part of ordinary `check`.

The `repository` fixture represents 80 builds, 2,000 projects, and 10 million logical source lines. It materializes
only 32,000 deterministic source lines so topology/index proof remains practical. Consequently, the run measures
bounded hierarchy projection and SQLite behavior at the requested ownership scale, but does not claim a physical
10-million-line decode or a real composite repository of that size.

Artifacts are retained under `docx-index/build/reports/workspaceAtlasPerformance/repository/`:

- `summary.md` records measured component checks and every unmeasured acceptance gate.
- `samples.csv` contains every raw static, drill, import, live-query, search, and summary latency sample.
- `artifact-sizes.csv` inventories every generated static file.
- `payload-sizes.csv` records encoded transfer sizes and labeled UTF-16 decode estimates.
- `memory.csv` records process RSS when the host exposes it and JVM heap at each stage.
- `workspace-atlas.sqlite` is the generated live index used by the samples.

Timing values are deliberately not asserted by ordinary unit tests. Structural checks do assert static/live slice
parity and both default and hard node/route/evidence bounds. The production browser smoke independently asserts
that each scope chooser mounts no more than seven rows (five visible rows plus one overscan row on either side).
