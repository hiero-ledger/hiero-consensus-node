# Consensus Layer Knowledge Base

`consensus-otter-tests` is consensus-layer **tooling** — the Otter test framework that exercises
the consensus modules under simulated (Turtle) and containerized environments. It is not part
of the runtime layer the knowledge base documents, but writing and reading these tests requires
the consensus-layer mental models, so consult the knowledge base at
[`../docs/consensus-layer/`](../docs/consensus-layer/). It documents the current implementation
as canonical, anchored to specific files, classes, and methods.

**Most relevant to this module:**

- [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md) — the module map and event flow a test reproduces.
- [`concepts/`](../docs/consensus-layer/concepts/) — the mental models (hashgraph DAG, rounds and witnesses, judges, birth-round, event lifecycle) a test must respect.
- [`scenarios/`](../docs/consensus-layer/scenarios/) and [`symptoms.md`](../docs/consensus-layer/symptoms.md) — edge cases and observable signatures worth reproducing or asserting against.

**Dependency Rules.** Category: **tooling** — the Otter test framework; not part of the runtime
module graph. Dependency rules are relaxed: tooling may depend on any consensus-layer module
including impl modules. Keep impl dependencies confined to test sources.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/); known incidents and diagnostics in
[`scenarios/`](../docs/consensus-layer/scenarios/) and
[`heuristics/`](../docs/consensus-layer/heuristics/).
