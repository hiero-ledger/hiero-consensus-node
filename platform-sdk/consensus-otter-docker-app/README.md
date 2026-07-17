# consensus-otter-docker-app

The containerized app that runs a consensus node for Otter tests. Not part of the runtime
module graph.

## Architecture

Tooling module — the Docker app that wires up and drives the consensus layer for Otter tests.
For how the modules compose into a running platform, see the
[architecture overview](../docs/consensus-layer/architecture/overview.md).

## Dependency Rules

As tooling, may depend on any consensus-layer module including impl modules. Keep impl
dependencies confined to test sources.
- `swirlds-common`, `swirlds-platform-core` must not be added — legacy, being eliminated
