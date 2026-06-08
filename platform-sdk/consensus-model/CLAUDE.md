# Consensus Layer Knowledge Base

`consensus-model` is part of the **consensus layer** — the shared data model (events, rounds,
judges, roster metadata, `PlatformStatus`, notifications) consumed by every consensus module.
When working here, consult the consensus-layer knowledge base at
[`../docs/consensus-layer/`](../docs/consensus-layer/) — it documents the current
implementation as canonical, anchored to specific files, classes, and methods.

**Most relevant to this module:**

- [`concepts/`](../docs/consensus-layer/concepts/) and [`glossary.md`](../docs/consensus-layer/glossary.md) — the canonical definitions for the types modelled here (event, round, birth-round, judge, witness, roster).
- [`architecture/interfaces/consensus-execution-boundary.md`](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md) — many of these types are the payloads that travel across the consensus / execution boundary.
- [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md) — how the model underpins all eleven topics.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); rationale in
[`decisions/`](../docs/consensus-layer/decisions/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/); tunable parameters in
[`tunables.md`](../docs/consensus-layer/tunables.md); known incidents and diagnostics in
[`scenarios/`](../docs/consensus-layer/scenarios/) and
[`heuristics/`](../docs/consensus-layer/heuristics/).
