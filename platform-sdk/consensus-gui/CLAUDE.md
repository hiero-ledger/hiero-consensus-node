# Consensus Layer Knowledge Base

`consensus-gui` is consensus-layer **tooling** — a GUI that visualizes the hashgraph (the DAG,
rounds, witnesses, and judges). It is not part of the runtime layer the knowledge base
documents, but rendering the hashgraph faithfully requires the consensus-layer mental models,
so consult the knowledge base at [`../docs/consensus-layer/`](../docs/consensus-layer/). It
documents the current implementation as canonical, anchored to specific files, classes, and
methods.

**Most relevant to this module:**

- The concepts this GUI renders: [`hashgraph-dag.md`](../docs/consensus-layer/concepts/hashgraph-dag.md), [`rounds-and-witnesses.md`](../docs/consensus-layer/concepts/rounds-and-witnesses.md), [`judges.md`](../docs/consensus-layer/concepts/judges.md), [`strongly-seeing.md`](../docs/consensus-layer/concepts/strongly-seeing.md).
- [`architecture/topics/hashgraph.md`](../docs/consensus-layer/architecture/topics/hashgraph.md) — the algorithm whose structure the GUI displays.

**Dependency Rules.** Category: **tooling** — not part of the runtime module graph; dependency
rules are relaxed compared to production modules.

Tooling may depend on any consensus-layer module including impl modules. Known dependency on
`consensus-hashgraph-impl` is intentional: the GUI reads internal hashgraph state for
visualization. Do not let impl dependencies from this module leak into production modules.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); rationale in
[`decisions/`](../docs/consensus-layer/decisions/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/).
