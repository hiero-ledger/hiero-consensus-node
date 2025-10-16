# BlockNodeConnection.md

## Table of Contents

1. [Abstract](#abstract)
2. [Definitions](#definitions)
3. [Component Responsibilities](#component-responsibilities)
4. [Component Interaction](#component-interaction)
5. [State Management](#state-management)
6. [State Machine Diagrams](#state-machine-diagrams)
7. [Error Handling](#error-handling)

## Abstract

`BlockNodeConnection` represents a single connection between a consensus node and a block node.
It manages connection state, handles communication, and reports errors to the `BlockNodeConnectionManager`.

## Definitions

<dl>
<dt>BlockNodeConnection</dt>
<dd>A connection instance managing communication and state with a block node.</dd>

<dt>ConnectionState</dt>
<dd>Represents current connection status: UNINITIALIZED, PENDING, ACTIVE, CLOSING, CLOSED.</dd>
</dl>

## Component Responsibilities

- Establish and maintain the connection transport.
- Handle incoming and outgoing message flow.
- Report connection errors promptly.
- Coordinate with `BlockNodeConnectionManager` on lifecycle events.
- Notify the block buffer (via connection manager) when a block has been acknowledged and therefore eligible to be
  pruned.

## Component Interaction

- Communicates bi-directionally with `BlockNodeConnectionManager`.

## State Management

- Tracks connection lifecycle state.
- Handles status transitions.

### Connection States

- **UNINITIALIZED**: Initial state when a connection object is created. The bidi RequestObserver needs to be created.
- **PENDING**: The bidi RequestObserver is established, but this connection has not been chosen as the active one (priority-based selection).
- **ACTIVE**: Connection is active and the Block Stream Worker Thread is sending `PublishStreamRequest`s to the block node through the async bidirectional stream. Only one connection can be ACTIVE at a time.
- **CLOSING**: The connection is being closed. This is a terminal state where only cleanup operations are permitted. No more requests can be sent.
- **CLOSED**: Connection has been fully closed and the pipeline terminated. This is a terminal state. No more requests can be sent and no more responses will be received.

## State Machine Diagrams

```mermaid
stateDiagram-v2
    [*] --> UNINITIALIZED : New Connection Created
    UNINITIALIZED --> PENDING : Request pipeline established<br/>gRPC stream opened
    PENDING --> ACTIVE : Manager promotes to active<br/>based on priority
    PENDING --> CLOSING : Higher priority connection selected<br/>or connection error
    ACTIVE --> CLOSING : Too many EndOfStream responses<br/>(rate limit exceeded)
    ACTIVE --> CLOSING : EndOfStream ERROR
    ACTIVE --> CLOSING : EndOfStream PERSISTENCE_FAILED
    ACTIVE --> CLOSING : EndOfStream SUCCESS
    ACTIVE --> CLOSING : EndOfStream UNKNOWN
    ACTIVE --> CLOSING : Block not found in buffer
    ACTIVE --> CLOSING : ResendBlock unavailable
    ACTIVE --> CLOSING : gRPC onError
    ACTIVE --> CLOSING : Stream failure
    ACTIVE --> CLOSING : Manual close
    ACTIVE --> ACTIVE : BlockAcknowledgement
    ACTIVE --> ACTIVE : SkipBlock
    ACTIVE --> ACTIVE : ResendBlock available
    ACTIVE --> ACTIVE : Normal streaming
    ACTIVE --> CLOSING : EndOfStream BEHIND<br/>restart at next block
    ACTIVE --> CLOSING : EndOfStream TIMEOUT<br/>restart at next block
    ACTIVE --> CLOSING : EndOfStream DUPLICATE_BLOCK<br/>restart at next block
    ACTIVE --> CLOSING : EndOfStream BAD_BLOCK_PROOF<br/>restart at next block
    ACTIVE --> CLOSING : EndOfStream INVALID_REQUEST<br/>restart at next block
    ACTIVE --> CLOSING : Periodic stream reset
    CLOSING --> CLOSED : Pipeline closed<br/>resources released
    CLOSED --> [*] : Instance destroyed
    note right of ACTIVE
        Only one connection can be
        ACTIVE at any time
    end note
    note left of PENDING
        Multiple connections can
        be PENDING simultaneously
    end note
    note right of CLOSING
        Terminal state for cleanup
        No new requests permitted
    end note
    note right of CLOSED
        When a connection reaches CLOSED,
        the manager may create a new
        BlockNodeConnection instance that
        starts at UNINITIALIZED
    end note
```

### Connection Initialization

```mermaid
sequenceDiagram
    participant Connection as BlockNodeConnection
    participant Manager as BlockNodeConnectionManager

    Connection->>Connection: initialize transport
    Connection-->>Manager: notify connected
```

## Error Handling

- Detects and reports connection errors.
- Cleans up resources on disconnection.

```mermaid
sequenceDiagram
    participant Connection as BlockNodeConnection
    participant Manager as BlockNodeConnectionManager

    Connection-->>Manager: reportError(error)
```

### Consensus Node Behavior on EndOfStream Response Codes

| Code                          | Connect to Other Node | Retry Behavior      | Initial Retry Delay | Exponential Backoff | Restart at Block | Special Behaviour                                                                                   |
|:------------------------------|:----------------------|:--------------------|:--------------------|:--------------------|:-----------------|:----------------------------------------------------------------------------------------------------|
| `SUCCESS`                     | Yes (immediate)       | Fixed delay         | 30 seconds          | No                  | Latest           |                                                                                                     |
| `BEHIND` with block in buffer | No (retry same)       | Exponential backoff | 1 second            | Yes (2x, jittered)  | blockNumber + 1  |                                                                                                     |
| `BEHIND` w/o block in buffer  | Yes (immediate)       | Fixed delay         | 30 seconds          | No                  | Latest           | CN sends `EndStream.TOO_FAR_BEHIND` to indicate the BN to look for the block from other Block Nodes |
| `ERROR`                       | Yes (immediate)       | Fixed delay         | 30 seconds          | No                  | Latest           |                                                                                                     |
| `PERSISTENCE_FAILED`          | Yes (immediate)       | Fixed delay         | 30 seconds          | No                  | Latest           |                                                                                                     |
| `TIMEOUT`                     | No (retry same)       | Exponential backoff | 1 second            | Yes (2x, jittered)  | blockNumber + 1  |                                                                                                     |
| `DUPLICATE_BLOCK`             | No (retry same)       | Exponential backoff | 1 second            | Yes (2x, jittered)  | blockNumber + 1  |                                                                                                     |
| `BAD_BLOCK_PROOF`             | No (retry same)       | Exponential backoff | 1 second            | Yes (2x, jittered)  | blockNumber + 1  |                                                                                                     |
| `INVALID_REQUEST`             | No (retry same)       | Exponential backoff | 1 second            | Yes (2x, jittered)  | blockNumber + 1  |                                                                                                     |
| `UNKNOWN`                     | Yes (immediate)       | Fixed delay         | 30 seconds          | No                  | Latest           |                                                                                                     |

**Notes:**
- **Exponential Backoff**: When enabled, delay starts at 1 second and doubles (2x multiplier) on each retry attempt with jitter applied (delay/2 + random(0, delay/2)) to spread out retry attempts. Max backoff is configurable via `maxBackoffDelay`.
- **Connect to Other Node**: When "Yes (immediate)", the manager will immediately attempt to connect to the next available priority node while the failed node is rescheduled for retry.
- **Restart at Block**: "Latest" means reconnection starts at the latest produced block; "blockNumber + 1" means reconnection continues from the block following the acknowledged block.

### EndOfStream Rate Limiting

The connection implements a configurable rate limiting mechanism for EndOfStream responses to prevent rapid reconnection cycles and manage system resources effectively.

### Configuration Parameters

<dl>
<dt>maxEndOfStreamsAllowed</dt>
<dd>The maximum number of EndOfStream responses permitted within the configured time window.</dd>

<dt>endOfStreamTimeFrame</dt>
<dd>The duration of the sliding window in which EndOfStream responses are counted.</dd>

<dt>endOfStreamScheduleDelay</dt>
<dd>The delay duration before attempting reconnection when the rate limit is exceeded.</dd>
</dl>
