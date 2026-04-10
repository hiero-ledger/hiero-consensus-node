# Block Node Connection Components Design Documents

This folder contains documents describing the internal design and expected behavior
for various components of the Consensus Node (CN) to Block Node (BN) communication. Each document focuses on a single
class or component and its role, including interactions with other components.

## Contents

| Document                                                           |          Component           | Description                                                                                                    |
|:-------------------------------------------------------------------|------------------------------|:---------------------------------------------------------------------------------------------------------------|
| [BlockNodeConnectionManager.md](BlockNodeConnectionManager.md)     | BlockNodeConnectionManager   | Internal design and behavior of the BlockNodeConnectionManager class, managing node connections.               |
| [BlockNodeStreamingConnection.md](BlockNodeStreamingConnection.md) | BlockNodeStreamingConnection | Internal design and behavior of the BlockNodeStreamingConnection class, representing an individual connection. |
| [BlockState.md](BlockState.md)                                     | BlockState                   | Internal design of the BlockState component, managing state information for blocks.                            |
| [BlockBufferService.md](BlockBufferService.md)                     | BlockBufferService           | Internal design and responsibilities of BlockBufferService, handling stream state and synchronization.         |
| [block-nodes-json.md](block-nodes-json.md)                         | Configuration                | JSON structure and options for `block-nodes.json`, protocol overrides, and live reload behavior.               |

## Components Interaction Flow

The following diagram illustrates the main flow and interactions between these components:

```mermaid
sequenceDiagram
    participant Manager as BlockNodeConnectionManager
    participant Connection as BlockNodeStreamingConnection
    participant Buffer as BlockBufferService
    participant BlockState as BlockState

    Manager->>Connection: Initiate connection
    Connection-->>Manager: Confirm connection established
    Connection->>StateMgr: Send block stream data
    StateMgr->>BlockState: Update block state with new data
    BlockState-->>StateMgr: Confirm state update
    StateMgr-->>Connection: Request next block stream
    Connection->>Manager: Report errors or disconnection
    Manager->>Connection: Handle disconnect and cleanup
```

## Block Node Connection Initialization During Consensus Node Startup

During startup of the Consensus Node, if block node streaming is enabled, an asynchronous task will be spawned that will
select a Block Node to connect to and attempt to establish a bi-directional gRPC stream to that node.

### Initialization Flow

The connection initialization is handled by the `Hedera` class and occurs during the Consensus Node startup process.
The initialization flow includes:

1. Configuration Check
   - The primary configuration property that determines if block node streaming is enabled is `blockStream.writerMode`.
     If this property is set to `FILE_AND_GRPC` or `GRPC` then streaming is enabled. Any other value (e.g. `FILE`) means
     streaming is NOT enabled.
2. If streaming is enabled:
   - The connection manager singleton (`BlockNodeConnectionManager`) is retrieved and the startup method is invoked.
   - A background connection monitor is started alongside the connection manager starting that will periodically check
     whether new block node connection needs to be established. Since this is the first time the service is started, there
     will be no active connection and thus the monitor will begin the connection establishment process.
     - If there are no available block nodes, the monitor will continue to periodically wait for one to become available.
3. If streaming is NOT enabled, then nothing happens.

## Block Node and Block Stream Configurations

The following configurations are used to control the behavior of block node connections and block streaming.
These configurations ensure scalable, resilient, and tunable block node communication and streaming behavior.

## Block Node Connection Configurations

These settings control how the Consensus Node discovers and connects to Block Nodes, and define fallback behavior if connections fail.
These property names are formatted as `blockNode.[propertyName]`
- Connection File Path & Name: The node loads block node definitions from a file (e.g., `block-nodes.json`) located in a specified directory.
- Connection Management: Parameters like `maxEndOfStreamsAllowed` and `endOfStreamTimeFrame` manage retries and limits.
- Shutdown Behavior: If no connections are made and `shutdownNodeOnNoBlockNodes` is true, the node will shut down to avoid running in a degraded state.

## Block Stream Configurations

These define how the Consensus Node batches, stores, or streams blocks of data. These property names are formatted as:
`blockStream.[propertyName]`
- Streaming Mode: Controlled via `streamMode` and `writerMode`, the node can either write to local files or stream blocks to Block Nodes over gRPC.
- Performance Tuning: Settings like `blockPeriod`, `blockItemBatchSize`, and `buffer TTLs/pruning intervals` help manage throughput and resource usage.
- Block Formation: Parameters such as `roundsPerBlock` and `hashCombineBatchSize` govern how data is grouped into blocks.
