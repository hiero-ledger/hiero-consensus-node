# consensus-gui

A GUI that visualizes the hashgraph: the DAG, rounds, witnesses, and judges. Not part of the
runtime module graph.

## Architecture

Tooling module — not part of the runtime module graph. The GUI reads internal hashgraph state
for visualization, which requires depending on `consensus-hashgraph-impl`. For the concepts it
renders, see [hashgraph-dag](../docs/consensus-layer/concepts/hashgraph-dag.md),
[rounds-and-witnesses](../docs/consensus-layer/concepts/rounds-and-witnesses.md),
[judges](../docs/consensus-layer/concepts/judges.md),
[strongly-seeing](../docs/consensus-layer/concepts/strongly-seeing.md), and the
[hashgraph topic](../docs/consensus-layer/architecture/topics/hashgraph.md).

## Dependency Rules

As tooling, may depend on any consensus-layer module including impl modules. However:
- `swirlds-common`, `swirlds-platform-core` must not be added — legacy, being eliminated
- The known dependency on `consensus-hashgraph-impl` is intentional; do not let impl
dependencies from this module leak into production modules.
