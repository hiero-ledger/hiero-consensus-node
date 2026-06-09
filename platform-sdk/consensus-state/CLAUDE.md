# Consensus Layer Knowledge Base

`consensus-state` is part of the **consensus layer** — consensus-side state structures shared
at the boundary with the execution layer. When working here, consult the consensus-layer
knowledge base at [`../docs/consensus-layer/`](../docs/consensus-layer/) — it documents the
current implementation as canonical, anchored to specific files, classes, and methods.

**Most relevant to this module:**

- [`architecture/topics/signed-state-management.md`](../docs/consensus-layer/architecture/topics/signed-state-management.md) — round signing, state hashing, signature collection.
- [`architecture/interfaces/consensus-execution-boundary.md`](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md) — how state structures are shared across the boundary. Note: the proposed design moves state under the execution layer; this module sits on the consensus side today.

**Dependency Rules.** Category: **structural-transitional** — treated like an impl module until
this code moves to the execution layer. Nothing except test code and tooling may depend on it.

Allowed consensus-layer dependencies: `consensus-model`, `consensus-metrics`,
`consensus-platformstate`, `consensus-roster`.

Prohibited: any `consensus-*-impl` module; all functional-api modules.

No known violations.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); rationale in
[`decisions/`](../docs/consensus-layer/decisions/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/); tunable parameters in
[`tunables.md`](../docs/consensus-layer/tunables.md); known incidents and diagnostics in
[`scenarios/`](../docs/consensus-layer/scenarios/) and
[`heuristics/`](../docs/consensus-layer/heuristics/).
