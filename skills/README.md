# SRCX skills

These Agent Skills are the recommended guide for configuring SRCX, generating codebase context, and cleaning generated
reports.

| Skill | Use it for |
|---|---|
| [`srcx`](srcx/SKILL.md) | Install and configure the settings plugin and choose the correct task |
| [`srcx-context`](srcx-context/SKILL.md) | Generate and interpret root and included-build context reports |
| [`srcx-clean`](srcx-clean/SKILL.md) | Delete generated SRCX output after validating the configured path |

Run SRCX tasks from the root Gradle workspace. The root invocation discovers and analyzes included builds automatically.
