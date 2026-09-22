# Consensus Layer Knowledge Base

`swirlds-cli` is consensus-layer **tooling** — the `pcli` command-line tool that operates on
consensus artifacts, including event-stream recovery (`pcli/recovery`) and hashgraph graph
generation (`pcli/graph`). It is not part of the runtime layer the knowledge base documents, but
its consensus-facing commands require the consensus-layer mental models, so consult the
knowledge base at [`../docs/consensus-layer/`](../docs/consensus-layer/). It documents the
current implementation as canonical, anchored to specific files, classes, and methods.

**Most relevant to this module:**

- [`architecture/topics/restart-and-pces.md`](../docs/consensus-layer/architecture/topics/restart-and-pces.md) — the Pre-Consensus Event Stream and event-stream files the recovery commands read.
- [`architecture/interfaces/consensus-execution-boundary.md`](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md) — the offline `EventRecoveryWorkflow` path (`SwirldMain`, `onNewRecoveredState`) these commands drive.
- [`architecture/topics/hashgraph.md`](../docs/consensus-layer/architecture/topics/hashgraph.md) and [`concepts/hashgraph-dag.md`](../docs/consensus-layer/concepts/hashgraph-dag.md) — the structure the graph commands render.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); rationale in
[`decisions/`](../docs/consensus-layer/decisions/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/).
