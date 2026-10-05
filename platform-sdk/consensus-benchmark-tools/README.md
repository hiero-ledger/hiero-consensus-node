# consensus-benchmark-tools

Classes and scripts shared by the consensus-layer performance tests. Not part of the runtime
module graph.

## Architecture

Tooling module — support code for the performance tests in
[`consensus-otter-tests`](../consensus-otter-tests), [`consensus-sloth`](../consensus-sloth),
[`consensus-network-simulation`](../consensus-network-simulation), and
the JMH benchmarks (`src/jmh`) of other modules. For the modules these tests exercise, see the
[architecture overview](../docs/consensus-layer/architecture/overview.md).

## Dependency Rules

May depend on:
- Any consensus-layer module except structural-transitional ones, including impl modules

Must not depend on:
- Any structural-transitional module — those are on their way out of the consensus layer, and
benchmark tooling must not pin them in place
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated

May be depended on only by:
- `consensus-otter-tests`
- `consensus-sloth`
- `consensus-network-simulation`, in its tests only
- The JMH source sets (`src/jmh`) of any module
