# Internal Design Document for BlockNodeConnectionManager

## Table of Contents

1. [Abstract](#abstract)
2. [Definitions](#definitions)
3. [Component Responsibilities](#component-responsibilities)
4. [Component Interaction](#component-interaction)
5. [Sequence Diagrams](#sequence-diagrams)
6. [Error Handling](#error-handling)

## Abstract

This document describes the internal design and responsibilities of the `BlockNodeConnectionManager` class.
This component manages active connections, handling connection lifecycle, and coordinating
with individual connection instances. There should be only one active connection at a time.
The class also interacts with the `BlockBufferService` to retrieve blocks/requests and notify the buffer of acknowledged
blocks.

## Definitions

<dl>
<dt>BlockNodeConnectionManager</dt>
<dd>The class responsible for managing and tracking all active block node connections, including creation, teardown, and error recovery.</dd>

<dt>BlockNodeConnection</dt>
<dd>A representation of a single connection to block node, managed by the connection manager.</dd>

<dt>BlockBufferService</dt>
<dd>The component responsible for maintaining a buffer of blocks produced by the consensus node.</dd>

<dt>Connection Lifecycle</dt>
<dd>The phases a connection undergoes.</dd>
</dl>

## Component Responsibilities

- Maintain a registry of active connection instances.
- Track the latest verified block for each connection.
- Select the most appropriate connection for streaming blocks.
- Retry failed connections with exponential backoff.
- Remove or replace failed connections.
- Support lifecycle control.

## Component Interaction

- Maintains a bidirectional association with each connection.
- Calls `BlockBufferService` to get the blocks/requests to send and to also notify the buffer when blocks are acknowledged.
- Updates connection state and retry schedule based on feedback from connections.

## Sequence Diagrams

### Connection Establishment

```mermaid
sequenceDiagram
    participant Manager as BlockNodeConnectionManager
    participant Conn as BlockNodeConnection

    Manager->>Manager: Select node to connect to
    Manager->>Manager: Schedule connection task

    alt connection task
      Manager->>Conn: Try connect

      alt success
        Conn->>Manager: Connection was successful
        Manager->Conn: Set to active connection
      else failure
        Manager->>Manager: Reschedule connection task
      end
    end
```

### Connection Error and Retry

```mermaid
sequenceDiagram
    participant Conn as BlockNodeConnection
    participant Manager as BlockNodeConnectionManager

    Conn->>Conn: Close connection
    Conn->>Manager: Notify to reschedule connection<br/>and connect to new node

    Note over Manager: Retry with backoff delay
```

### Shutdown Lifecycle

```mermaid
sequenceDiagram
    participant Manager as BlockNodeConnectionManager
    participant Conn as BlockNodeConnection

    alt shutdown
      Manager -> Manager: Stop worker thread
      Manager -> Manager: Is active flag set to false
      loop over all connections
        Manager ->> Conn: Close
      end
    end

```

## Error Handling

- Implements backoff-based retry scheduling when connections fail.
- Detects and cleans up errored or stalled connections.
- If `getLastVerifiedBlock()` or other state is unavailable, logs warnings and may skip the connection.
