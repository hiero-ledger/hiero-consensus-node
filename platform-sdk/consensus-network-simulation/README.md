# consensus-network-simulation

A simulation harness that exercises consensus modules under deterministic, simulated network
conditions. Not part of the runtime module graph.

## Architecture

Tooling module — not part of the runtime module graph. For the module map and event flow this
simulation reproduces, see the
[architecture overview](../docs/consensus-layer/architecture/overview.md).

## Dependency Rules

As tooling, may depend on any consensus-layer module including impl modules. Keep impl
dependencies confined to test sources.
- `swirlds-common`, `swirlds-platform-core` must not be added — legacy, being eliminated
