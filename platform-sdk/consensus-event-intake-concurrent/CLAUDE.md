# Consensus Layer Knowledge Base

`consensus-event-intake-concurrent` is part of the **consensus layer**. When working here, consult the
consensus-layer knowledge base at [`../docs/consensus-layer/`](../docs/consensus-layer/) —
it documents the current implementation as canonical, anchored to specific files,
classes, and methods.

**Most relevant to this module:**

- [`architecture/topics/event-intake.md`](../docs/consensus-layer/architecture/topics/event-intake.md) — validation, deduplication, topological ordering, branch detection, birth-round filtering, and emission downstream.
- [`architecture/topics/wiring-framework.md`](../docs/consensus-layer/architecture/topics/wiring-framework.md) — how the concurrent intake stages are wired and backpressured.
- [`concepts/event-lifecycle.md`](../docs/consensus-layer/concepts/event-lifecycle.md), [`concepts/branching.md`](../docs/consensus-layer/concepts/branching.md), [`concepts/birth-round.md`](../docs/consensus-layer/concepts/birth-round.md) — the mental models intake depends on.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); rationale in
[`decisions/`](../docs/consensus-layer/decisions/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/); tunable parameters in
[`tunables.md`](../docs/consensus-layer/tunables.md); known incidents and diagnostics in
[`scenarios/`](../docs/consensus-layer/scenarios/) and
[`heuristics/`](../docs/consensus-layer/heuristics/).
