# Consensus Layer Knowledge Base

`consensus-hashgraph` is part of the **consensus layer**. When working here, consult the
consensus-layer knowledge base at [`../docs/consensus-layer/`](../docs/consensus-layer/) —
it documents the current implementation as canonical, anchored to specific files,
classes, and methods.

**Most relevant to this module:**

- [`architecture/topics/hashgraph.md`](../docs/consensus-layer/architecture/topics/hashgraph.md) — the consensus algorithm, round production with judges and timestamps, roster-and-config changes carried as round metadata.
- The concepts the algorithm rests on: [`hashgraph-dag.md`](../docs/consensus-layer/concepts/hashgraph-dag.md), [`rounds-and-witnesses.md`](../docs/consensus-layer/concepts/rounds-and-witnesses.md), [`strongly-seeing.md`](../docs/consensus-layer/concepts/strongly-seeing.md), [`judges.md`](../docs/consensus-layer/concepts/judges.md), [`voting.md`](../docs/consensus-layer/concepts/voting.md), [`coin-rounds.md`](../docs/consensus-layer/concepts/coin-rounds.md), [`birth-round.md`](../docs/consensus-layer/concepts/birth-round.md).

**Dependency Rules.** Category: **functional-api**.

Allowed consensus-layer dependencies: `consensus-model`, `consensus-metrics`.

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
