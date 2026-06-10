# consensus-sloth

A performance experiment framework that probes consensus behavior directly: anti-selfishness,
max creation rate, max other-parents, broadcast, and signature experiments. Not part of the
runtime module graph.

## Architecture

Tooling module — performance experiments that stress the event-creator and gossip subsystems.
For the parameters these experiments sweep, see [tunables](../docs/consensus-layer/tunables.md),
[event-creator topic](../docs/consensus-layer/architecture/topics/event-creator.md),
[gossip topic](../docs/consensus-layer/architecture/topics/gossip.md), and
[health monitor and backpressure](../docs/consensus-layer/architecture/topics/health-monitor-and-backpressure.md).

## Dependency Rules

As tooling, may depend on any consensus-layer module including impl modules. Keep impl
dependencies confined to test sources.
- `swirlds-common`, `swirlds-platform-core` must not be added — legacy, being eliminated
