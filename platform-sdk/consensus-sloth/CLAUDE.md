# Consensus Layer Knowledge Base

`consensus-sloth` is consensus-layer **tooling** — a performance framework whose experiments
(anti-selfishness, max creation rate, max other-parents, broadcast, signature) probe consensus
behavior directly. It is not part of the runtime layer the knowledge base documents, but
designing and reading these experiments requires the consensus-layer mental models, so consult
the knowledge base at [`../docs/consensus-layer/`](../docs/consensus-layer/). It documents the
current implementation as canonical, anchored to specific files, classes, and methods.

**Most relevant to this module:**

- [`tunables.md`](../docs/consensus-layer/tunables.md) — the parameters these experiments sweep (creation rate, other-parent count, etc.).
- [`architecture/topics/event-creator.md`](../docs/consensus-layer/architecture/topics/event-creator.md) and [`architecture/topics/gossip.md`](../docs/consensus-layer/architecture/topics/gossip.md) — the subsystems most of the experiments stress.
- [`architecture/topics/health-monitor-and-backpressure.md`](../docs/consensus-layer/architecture/topics/health-monitor-and-backpressure.md) — the flow-control behavior under load that performance work observes.

**Dependency Rules.** Category: **tooling** — a performance experiment framework; not part of
the runtime module graph. Dependency rules are relaxed: tooling may depend on any
consensus-layer module including impl modules. Keep impl dependencies confined to test sources.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); rationale in
[`decisions/`](../docs/consensus-layer/decisions/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/); known incidents and diagnostics in
[`scenarios/`](../docs/consensus-layer/scenarios/) and
[`heuristics/`](../docs/consensus-layer/heuristics/).
