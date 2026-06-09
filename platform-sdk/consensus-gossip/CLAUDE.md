# Consensus Layer Knowledge Base

`consensus-gossip` is part of the **consensus layer**. When working here, consult the
consensus-layer knowledge base at [`../docs/consensus-layer/`](../docs/consensus-layer/) —
it documents the current implementation as canonical, anchored to specific files,
classes, and methods.

**Most relevant to this module:**

- [`architecture/topics/gossip.md`](../docs/consensus-layer/architecture/topics/gossip.md) — peer-to-peer communication, neighbor discipline, falling-behind detection, buffering for catch-up.
- [`architecture/topics/reasons-not-to-gossip.md`](../docs/consensus-layer/architecture/topics/reasons-not-to-gossip.md) — the conditions under which a node refuses to gossip events.
- [`architecture/topics/health-monitor-and-backpressure.md`](../docs/consensus-layer/architecture/topics/health-monitor-and-backpressure.md) — keeping the consensus layer bounded under load.

**Dependency Rules.** Category: **functional-api**.

Allowed consensus-layer dependencies: `consensus-model`, `consensus-state`, `consensus-utility`.

Prohibited: all other functional-api modules (API modules must not depend on each other); any
`*-impl` module.

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
