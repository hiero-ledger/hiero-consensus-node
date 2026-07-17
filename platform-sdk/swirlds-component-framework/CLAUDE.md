# Consensus Layer Knowledge Base

`swirlds-component-framework` is the **wiring framework** that composes the consensus-layer
runtime: components, wires, and soldering, with backpressure applied at the wire level. When
working here, consult the consensus-layer knowledge base at
[`../docs/consensus-layer/`](../docs/consensus-layer/) — it documents the current
implementation as canonical, anchored to specific files, classes, and methods.

**Most relevant to this module:**

- [`architecture/topics/wiring-framework.md`](../docs/consensus-layer/architecture/topics/wiring-framework.md) — how components, wires, and soldering compose the consensus runtime, and how wire-level backpressure throttles upstream producers.
- [`architecture/topics/health-monitor-and-backpressure.md`](../docs/consensus-layer/architecture/topics/health-monitor-and-backpressure.md) — the flow-control behavior the framework enables.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); rationale in
[`decisions/`](../docs/consensus-layer/decisions/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/); tunable parameters in
[`tunables.md`](../docs/consensus-layer/tunables.md); known incidents and diagnostics in
[`scenarios/`](../docs/consensus-layer/scenarios/) and
[`heuristics/`](../docs/consensus-layer/heuristics/).
