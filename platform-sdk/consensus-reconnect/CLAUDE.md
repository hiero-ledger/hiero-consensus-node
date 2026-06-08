# Consensus Layer Knowledge Base

`consensus-reconnect` is part of the **consensus layer**. When working here, consult the
consensus-layer knowledge base at [`../docs/consensus-layer/`](../docs/consensus-layer/) —
it documents the current implementation as canonical, anchored to specific files,
classes, and methods.

**Most relevant to this module:**

- [`architecture/topics/reconnect.md`](../docs/consensus-layer/architecture/topics/reconnect.md) — recovering a node that has fallen too far behind for gossip to catch it up. The orchestration entry point lives in `swirlds-platform-core`.
- [`architecture/interfaces/consensus-execution-boundary.md`](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md) — `StateLifecycleManager` calls (`createStateFrom`, `initWithState`) exercised during reconnect.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); rationale in
[`decisions/`](../docs/consensus-layer/decisions/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/); tunable parameters in
[`tunables.md`](../docs/consensus-layer/tunables.md); known incidents and diagnostics in
[`scenarios/`](../docs/consensus-layer/scenarios/) and
[`heuristics/`](../docs/consensus-layer/heuristics/).
