# Consensus Layer Knowledge Base

`consensus-network-simulation` is a **simulation harness for testing the consensus layer** — it
exercises consensus modules under deterministic, simulated network conditions. To write or read
simulations here you need the consensus-layer mental models, so consult the knowledge base at
[`../docs/consensus-layer/`](../docs/consensus-layer/). It documents the current implementation
as canonical, anchored to specific files, classes, and methods.

**Most relevant to this module:**

- [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md) — the module map and event flow the simulation reproduces.
- [`concepts/`](../docs/consensus-layer/concepts/) — the mental models (hashgraph DAG, rounds and witnesses, judges, birth-round, event lifecycle) a simulation must respect.
- [`scenarios/`](../docs/consensus-layer/scenarios/) and [`symptoms.md`](../docs/consensus-layer/symptoms.md) — edge cases and observable signatures worth reproducing or asserting against.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/); tunable parameters in
[`tunables.md`](../docs/consensus-layer/tunables.md); diagnostics in
[`heuristics/`](../docs/consensus-layer/heuristics/).
