# Block Node Connection Components Design Documents

This folder contains documents describing the internal design and expected behavior
for various components of the Consensus node to Block Node communication. Each document focuses on a single
class or component and its role, including interactions with other components.

## Contents

| Document                                     | Component                  | Description                                                                                  |
|:---------------------------------------------|----------------------------|:---------------------------------------------------------------------------------------------|
| [BlockNodeConnectionManager.md](BlockNodeConnectionManager.md) | BlockNodeConnectionManager | Internal design and behavior of the BlockNodeConnectionManager class, managing node connections. |
| [BlockNodeConnection.md](BlockNodeConnection.md)               | BlockNodeConnection        | Internal design and behavior of the BlockNodeConnection class, representing an individual connection. |
| [BlockState.md](BlockState.md)                                 | BlockState                 | Internal design of the BlockState component, managing state information for blocks.          |
| [BlockStreamStateManager.md](BlockStreamStateManager.md)       | BlockStreamStateManager    | Internal design and responsibilities of BlockStreamStateManager, handling stream state and synchronization. |

## Component Interaction Flow

The following diagram illustrates the main flow and interactions between these components:

```mermaid
sequenceDiagram
    participant Manager as BlockNodeConnectionManager
    participant Connection as BlockNodeConnection
    participant StateMgr as BlockStreamStateManager
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

### Simplified Class Diagram

```mermaid
classDiagram
    class BlockNodeConnectionManager {
        -connections: Map<BlockNodeConfig, BlockNodeConnection>
        +handleConnectionError(connection: BlockNodeConnection, delay: Duration): void
        +scheduleRetry(connection: BlockNodeConnection, initialDelay: Duration): void
        +shutdown(): void
        +waitForConnection(timeout: Duration): boolean
        +openBlock(blockNumber: long): void
        +updateLastVerifiedBlock(blockNodeConfig: BlockNodeConfig, blockNumber: long): void
        +getLastVerifiedBlock(blockNodeConfig: BlockNodeConfig): long
        +selectBlockNodeForStreaming(): BlockNodeConnection
        +getNextPriorityBlockNode(): BlockNodeConnection
    }

    class BlockNodeConnection {
        -blockNodeConfig: BlockNodeConfig
        +updateConnectionState(newState: ConnectionState): void
        +handleStreamFailure(): void
        +sendRequest(request: PublishStreamRequest): void
        +close(): void
        +restartStreamAtBlock(blockNumber: long): void
        +jumpToBlock(blockNumber: long): void
        +onNext(response: PublishStreamResponse): void
        +onError(error: Throwable): void
        +onCompleted(): void
        +getConnectionState(): ConnectionState
    }

    class BlockStreamStateManager {
        -blockStreamQueue: Queue<BlockStreamQueueItem>
        -activeConnection: BlockNodeConnection
        -blockStates: Map<long, BlockState>
        +isBufferSaturated(): boolean
        +setBlockNodeConnectionManager(manager: BlockNodeConnectionManager): void
        +openBlock(blockNumber: long): void
        +addItem(blockNumber: long, blockItem: BlockItem): void
        +closeBlock(blockNumber: long): void
        +getBlockState(blockNumber: long): BlockState
        +isAcked(blockNumber: long): boolean
        +streamPreBlockProofItems(blockNumber: long): void
        +setLatestAcknowledgedBlock(blockNumber: long): void
        +getBlockNumber(): long
        +ensureNewBlocksPermitted(): void
        +getActiveConnection(): BlockNodeConnection
        +setActiveConnection(activeConnection: BlockNodeConnection): void
        +higherPriorityStarted(connection: BlockNodeConnection): boolean
    }

    class BlockState {
        -blockNumber: long
        -items: List<BlockItem>
        -requests: List<PublishStreamRequest>
        -requestsCompleted: boolean
        -completionTimestamp: long
        +BlockState(blockNumber: long)
        +blockNumber(): long
        +items(): List<BlockItem>
        +requests(): List<PublishStreamRequest>
        +requestsCompleted(): boolean
        +setRequestsCompleted(): void
        +setCompletionTimestamp(timestamp: long): void
        +createRequestFromCurrentItems(batchSize: int, forceCreation: boolean): void
    }

%% Relationships

BlockNodeConnectionManager "1" o-- "*" BlockNodeConnection : manages
BlockNodeConnection "*" --> "1" BlockStreamStateManager : uses
BlockStreamStateManager "1" --> "1" BlockState : updates

```