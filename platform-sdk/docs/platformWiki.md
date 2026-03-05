# Swirlds Platform Wiki

This document contains information about the Swirlds Platform. It is currently a work in progress.

The platform code is split into three categories:

- Base: common utilities and libraries; logging, configuration, metrics, etc.
- Core: gossip, consensus, data flow, and various algorithms
- Data: merkle data structures for holding the ledger state

## Base

This code is maintained by the "Platform Base" team.

- [Overview of Platform Base](./base/base.md)
- [Configuration](./base/configuration/configuration.md)
- [Context](./base/context/context.md)
- [Metrics](./base/metrics/metrics.md)
  - Prometheus
  - [Busy time metrics](base/metrics/metric-types/busy-time-metric.md)
- Logging
- Thread Management
- [Test Support](./base/test-support/test-support.md)
- [toString-Builder](./base/to-string/to-string.md)
- [Null handling](./base/null-handling/null-handling.md)

## Core

This code is maintained by the "Platform Hashgraph" team.

- [System Startup Sequence](./core/system-startup-sequence.svg)
- [Platform Status](./core/platform-status.md)
- [Threads](./core/core-platform-threads.drawio.svg)
- Components
  - [Framework](components/componentFramework.md)
  - [Networking](core/network/network.md)
  - [Gossip](./core/gossip/gossip.md)
    - [Sync gossip algorithm](core/gossip/syncing/sync-protocol.md)
    - [Out of order gossip algorithm](core/gossip/OOG/OOG-protocol.md)
  - Hashgraph
  - State management
    - [Rules for using SignedState objects](./core/signed-state-use.md)
    - State snapshots
    - Hashing
    - State Signing
    - ISS Detection
  - Reconnect
  - Transaction Handling
  - BLS
  - Application Communication
- Event Flow
  - Event Intake
  - [Pre-consensus event stream](core/preconsensusEventStream.svg)
  - Post-consensus event stream
  - Threading Diagram
- [Freeze](core/freeze/freeze.md)
- WIP
  - [Address book management](core/address-book-management.md)
- [External docs](https://drive.google.com/drive/folders/161GObnZVBWXKy4MHulBZKFcBDsNTU5FB?usp=drive_link)

## Data

This code is maintained by the "Platform Data" team.

- Merkle APIs
  - Fast Copies
  - Mutability
  - Reference Counting
  - Hashing
  - Serialization
- Data Structures
  - VirtualMap
    - MerkleDB
- Reconnect

## Process

### Testing

### Pull Requests

### Documentation

#### Markdown Wiki

#### Mindmap
