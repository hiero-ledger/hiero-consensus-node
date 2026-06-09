# Consensus Layer Knowledge Base

`consensus-event-stream` is part of the **consensus layer** — a supporting module that writes
the consensus event-stream files. When working here, consult the consensus-layer knowledge
base at [`../docs/consensus-layer/`](../docs/consensus-layer/) — it documents the current
implementation as canonical, anchored to specific files, classes, and methods.

**Most relevant to this module:**

- [`architecture/topics/hashgraph.md`](../docs/consensus-layer/architecture/topics/hashgraph.md) — the consensus rounds and ordered events this module serializes.
- [`concepts/event-lifecycle.md`](../docs/consensus-layer/concepts/event-lifecycle.md) — what an event carries once it reaches consensus.

**Dependency Rules.** Category: **supporting**.

Allowed consensus-layer dependencies: `consensus-model`, `consensus-concurrent`,
`consensus-metrics`, `consensus-utility`.

Prohibited: all functional-api modules, all `*-impl` modules (including `swirlds-*-impl`).

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
