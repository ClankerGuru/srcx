# Live workspace graph API

The service exposes the same bounded `WorkspaceGraphRequest` / `WorkspaceGraphSlice` transport used by the static viewer:

```text
POST /api/v1/workspaces/{registeredWorkspaceId}/graph
Authorization: Bearer {service token}
Content-Type: application/json
```

The path ID identifies the service registration. The request's `workspaceId` identifies the source workspace captured in the generated site; both are validated. A request may pin `generationId`. If it does, the service rejects the request after the active generation changes instead of mixing generations.

The first live implementation supports the `SOURCE` facet. It returns only the requested scope, expansion children, focus/ancestor closure, and bounded relationship endpoint closure. It never returns the full stored snapshot. Request limits are enforced by the shared transport model and cannot exceed 2,000 nodes, 5,000 aggregate relations, or 20 sampled fact IDs per relation. Relationship facts stay in generation-scoped SQLite tables and only deterministic bounded samples cross HTTP.

SQLite materializes integer-keyed hierarchy, closure, typed-search, directional-degree, and relation-fact tables when a generation becomes ready. Queries run in one read-only WAL snapshot, so node hierarchy and relationships always come from the same generation while a replacement generation is imported concurrently. The route is read-only and authenticated; static site hosting remains available if indexing is disabled, but the graph route then reports `503 Service Unavailable`.

When an existing database predates graph materialization, observing the same immutable site generation rebuilds that registered generation once instead of reporting an empty graph.

Current availability is explicit in every response. Structure and captured build dependencies are complete. Package, declaration, code-relationship, problem, and cycle facts are partial legacy-snapshot coverage when project shards were captured, or unavailable when they were not indexed. Task, variant, upgrade, ownership, and dependency families are reported as unavailable rather than synthesized.
