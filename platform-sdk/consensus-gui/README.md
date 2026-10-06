# consensus-gui

A GUI that visualizes the hashgraph: the DAG, rounds, witnesses, and judges. Not part of the
runtime module graph.

## Architecture

Tooling module — not part of the runtime module graph. The GUI reads internal hashgraph state
for visualization, which requires depending on `consensus-hashgraph-impl`. For how the modules
it visualizes fit into the layer, see the
[architecture overview](../docs/consensus-layer/architecture/overview.md).

## Dependency Rules

As tooling, may depend on any consensus-layer module including impl modules. However:
- `swirlds-common`, `swirlds-platform-core` must not be added — legacy, being eliminated
- The known dependency on `consensus-hashgraph-impl` is intentional; do not let impl
dependencies from this module leak into production modules.
