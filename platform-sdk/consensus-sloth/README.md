# consensus-sloth

A performance experiment framework that probes consensus behavior directly: anti-selfishness,
max creation rate, max other-parents, broadcast, and signature experiments. Not part of the
runtime module graph.

## Architecture

Tooling module — performance experiments that stress the event-creator and gossip subsystems.
For how the modules these experiments stress fit into the layer, see the
[architecture overview](../docs/consensus-layer/architecture/overview.md).

## Dependency Rules

As tooling, may depend on any consensus-layer module including impl modules. Keep impl
dependencies confined to test sources.
- `swirlds-common`, `swirlds-platform-core` must not be added — legacy, being eliminated
