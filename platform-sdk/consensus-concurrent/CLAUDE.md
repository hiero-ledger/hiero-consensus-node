# Consensus Layer Knowledge Base

`consensus-concurrent` is part of the **consensus layer** — a supporting module providing the
concurrency primitives the consensus modules build on. When working here, consult the
consensus-layer knowledge base at [`../docs/consensus-layer/`](../docs/consensus-layer/) — it
documents the current implementation as canonical, anchored to specific files, classes, and
methods.

**Most relevant to this module:**

- [`architecture/topics/wiring-framework.md`](../docs/consensus-layer/architecture/topics/wiring-framework.md) — how components, wires, and soldering compose the runtime and where backpressure is applied.
- [`architecture/topics/health-monitor-and-backpressure.md`](../docs/consensus-layer/architecture/topics/health-monitor-and-backpressure.md) — keeping the consensus layer bounded under load.

**Dependency Rules.** Category: **supporting**.

Allowed consensus-layer dependencies: `consensus-model`.

Prohibited: `consensus-metrics`, `consensus-utility`, `consensus-roster`, all functional-api
modules (`consensus-gossip`, `consensus-hashgraph`, `consensus-event-creator`,
`consensus-event-intake`, `consensus-pces`, `consensus-reconnect`), all `*-impl` modules
(including `swirlds-*-impl`). Supporting modules form a strict DAG; concurrent sits one level
above model and must not pull in higher-level helpers.

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
