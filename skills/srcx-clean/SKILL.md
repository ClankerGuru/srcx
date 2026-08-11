---
name: srcx-clean
description: Use when deleting generated SRCX report directories from the root Gradle build and every active included build.
---

# SRCX clean

## Commands

```bash
./gradlew srcx-clean
```

When the standard Gradle lifecycle `clean` task exists, SRCX wires it to depend on `srcx-clean`, but `clean` also
deletes normal build outputs. Use `srcx-clean` when only SRCX reports should be removed.

## Behavior

- Deletes the configured `outputDir` from the root build.
- Deletes the corresponding output directory from each active included build.
- Performs a string-prefix canonical-path check before deletion. This is not a complete containment guarantee.
- With the default `.srcx` output, deletes reports without modifying source files, Gradle configuration, or Git state.

## Rules

- Run from the workspace root so active included builds are cleaned in the same invocation.
- Keep `outputDir` at the default `.srcx` or another simple child directory. Never use `.`, `..`, an absolute path, or
  traversal segments; current validation can accept unsafe values.
- Regenerate reports afterward with `./gradlew srcx-context`.
- Before an autonomous agent runs this task, inspect `srcx.outputDir` and stop if it is not a simple child path.

## Expected result

After success, root and included-build SRCX report directories are absent. A later `srcx-context` invocation recreates
the directory, its `.gitignore`, dashboard, aggregate reports, and per-project context files.
