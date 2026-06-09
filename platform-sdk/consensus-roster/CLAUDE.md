# Consensus Layer Knowledge Base

`consensus-roster` is part of the **consensus layer**. When working here, consult the
consensus-layer knowledge base at [`../docs/consensus-layer/`](../docs/consensus-layer/) —
it documents the current implementation as canonical, anchored to specific files,
classes, and methods.

**Most relevant to this module:**

- [`architecture/topics/hashgraph.md`](../docs/consensus-layer/architecture/topics/hashgraph.md) — rosters are carried as round metadata so every module agrees which roster applies to which round.
- [`architecture/interfaces/consensus-execution-boundary.md`](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md) — where rosters cross the boundary (carried in state and as round metadata).

**Dependency Rules.** Category: **supporting** (transitional — will eventually move to the
execution layer).

Allowed consensus-layer dependencies: `consensus-model`.

Prohibited: `consensus-concurrent`, `consensus-metrics`, `consensus-utility`, all functional-api
modules, all `*-impl` modules (including `swirlds-*-impl`).

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
