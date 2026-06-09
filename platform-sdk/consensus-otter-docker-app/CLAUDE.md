# Consensus Layer Knowledge Base

`consensus-otter-docker-app` is consensus-layer **tooling** — the containerized app that runs a
consensus node for Otter tests (`ConsensusNodeManager`, `ConsensusRoundListener`,
`EventMessageFactory`). It is not part of the runtime layer the knowledge base documents, but it
wires up and drives the consensus layer, so consult the knowledge base at
[`../docs/consensus-layer/`](../docs/consensus-layer/). It documents the current implementation
as canonical, anchored to specific files, classes, and methods.

**Most relevant to this module:**

- [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md) — the module map and how a running consensus node is composed.
- [`architecture/topics/wiring-framework.md`](../docs/consensus-layer/architecture/topics/wiring-framework.md) — how the modules are soldered into a running platform.
- [`architecture/interfaces/consensus-execution-boundary.md`](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md) — the boundary this app stands up around the consensus layer.

**Dependency Rules.** Category: **tooling** — the containerized Otter test app; not part of the
runtime module graph. Dependency rules are relaxed: tooling may depend on any consensus-layer
module including impl modules. Keep impl dependencies confined to test sources.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); rationale in
[`decisions/`](../docs/consensus-layer/decisions/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/).
