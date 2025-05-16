# Block Node Communication Overview

This document outlines the architecture, behavior, classes, methods, and configurations for the Block Node Streaming functionality. The primary goal is to maintain reliable and prioritized streaming connections for blockchain data from Consensus Nodes to external Block Nodes using gRPC.

The implemented logic ensures the following:

- Only one active connection at a time, favoring higher-priority nodes
- Automatic retry and failover when a node becomes unreachable
- Support for configurable priority-based connection selection and resilient recovery using backoff
- Support for buffering of blocks for streaming

---

## Core Components

### 1. BlockNodeConnectionManager

The `BlockNodeConnectionManager` is a central class that manages all `BlockNodeConnection` instances.

**Responsibilities**
- Track all connections (`Map<BlockNodeConfig, BlockNodeConnection>`)
- Schedule and retry connection attempts
- Promote higher-priority connections when available
- Close and clean up failed or replaced connections
- Track last verified block number per node

---

### Key Methods

#### BlockNodeConnectionManager() - Constructor

**Purpose:**  
Constructs a new instance of `BlockNodeConnectionManager`, initializing the core state and dependencies required to manage block node connections.

**Parameters:**
- `blockNodeConfigExtractor`: Provides configuration data for available block nodes, determining which nodes to connect to.
- `blockStreamStateManager`: Manages state information related to block streaming and progress.
- `blockStreamMetrics`: Collects and reports metrics related to connection, block progress, and performance.

**Detailed Behaviour:**
- Stores `blockNodeConfigExtractor` and `blockStreamStateManager` into final fields after validating they are not null.
- Initializes two thread-safe `ConcurrentHashMap` instances for connections and last verified block numbers per connection.
- Stores `blockStreamMetrics` directly without null checks.

**Edge Cases:**
- No default values or fallback behavior are implemented. Passing `null` for `blockNodeConfigExtractor` or `blockStreamStateManager` throws an exception immediately.

**Thread Safety:**
- Uses `ConcurrentHashMap` for internal shared data structures to ensure safe concurrent operations.

---

#### createNewGrpcClient(...)

**Purpose:**  
Constructs and returns a fully configured `GrpcServiceClient` for the specified block node, enabling bidirectional streaming communication with the BlockStreamService over gRPC.

**Parameters:**
- `node`: A `BlockNodeConfig` object representing a single block node's connection configuration (e.g., address, port, priority).

**Detailed Behaviour:**
- Constructs and returns a ready-to-use `GrpcClient`.
- TLS is disabled, the base URI is composed dynamically from the node's address and port.
- Applies `GrpcClientProtocolConfig` to prevent polling from aborting on timeout and sets the max wait time while polling.
- Supports bidirectional streaming with request/response types `PublishStreamRequest` and `PublishStreamResponse`.
- Uses a custom marshaller via `RequestResponseMarshaller.Supplier()`.

**Edge Cases and Error Handling:**
- No error checking for base URI, assumes `node.address()` and `node.port()` return valid, reachable values.
- TLS is disabled, which may need reconsideration in future versions.
- Wait time and keep-alive options are hardcoded.
- Relies on the constant `GRPC_END_POINT` and existence of `BlockStreamServiceGrpc.SERVICE_NAME`.

---

#### handleConnectionError()

**Purpose:**  
Handles errors reported by an active `BlockNodeConnection`. Schedules a retry after a specified delay and attempts to connect to the next available block node.

**Parameters:**
- `connection`: The connection that encountered an error.
- `initialDelay`: The amount of time to wait before retrying the failed connection.

**Detailed Behaviour:**
- Uses a synchronized block on the `connection` object to ensure consistent state and avoid race conditions during retry scheduling and failover.
- Logs a warning message indicating error handling.
- Calls `scheduleRetry()` with the failed connection and the specified delay.
- Calls `selectBlockNodeForStreaming()` to attempt connection to the next available node.

**Edge Cases and Error Handling:**
- If `scheduleRetry()` fails internally, the connection may not be retried.
- If `selectBlockNodeForStreaming()` cannot find an available node, streaming could be interrupted.

**Thread Safety:**
- The connection should already exist in the connections map, otherwise, retry attempts might be ineffective.

---

#### scheduleRetry()

**Purpose:**  
Schedules a connection attempt or retry for a given Block Node with a specified delay. Handles adding/removing the connection from retry maps, supporting exponential backoff and jitter.

**Parameters:**
- `connection`: The `BlockNodeConnection` instance to schedule a retry for.
- `initialDelay`: The time to wait before the first retry attempt. `Duration.ZERO` means retry immediately.

**Detailed Behaviour:**
- Validates input parameters are not null. Converts `initialDelay` to milliseconds, defaulting to 0 if negative.
- Logs a message indicating when the connection retry is scheduled.
- Submits a new `BlockNodeConnectionTask` to the connection executor after the delay.
- Logs debug message on successful scheduling.
- On scheduling failure, logs the error and closes the connection.

**Edge Cases and Error Handling:**
- Negative `initialDelay` is normalized to 0 ms.
- If `connectionExecutor.schedule()` fails, error is logged and connection is closed.
- Null `connection` or `initialDelay` throws `NullPointerException`.

**Thread Safety:**
- Does not update maps or lists.
- Depends on thread safety of `connectionExecutor.schedule()` and `connection` (assumed safe).
- `BlockNodeConnectionTask` handles its own concurrency.

---

#### shutdown()

**Purpose:**  
Gracefully shuts down the `BlockNodeConnectionManager` by closing the executor and all active and retry-scheduled connections.

**Parameters:** None

**Detailed Behaviour:**
- Calls `shutdown()` on the connection executor.
- Handles timeout and interruptions, logging errors if shutdown is unsuccessful or interrupted.
- Closes all tracked connections except the placeholder `NoOpConnection.INSTANCE`.
- Clears the connections map.

**Edge Cases and Error Handling:**
- Logs error if executor does not shut down within the timeout.
- Throws `RuntimeException` if interrupted while waiting.

**Thread Safety:**
- Synchronizes access to the connections map to prevent concurrent modification during shutdown.
- Executor shutdown is thread-safe using Java’s `ScheduledExecutorService`.

---

#### waitForConnection(...)

**Purpose:**  
Waits for at least one Block Node connection to become ACTIVE within a defined timeout, typically used during startup or reconnection logic.

**Parameters:**
- `timeout`: Time to wait for a connection to become active.

**Detailed Behaviour:**
- Initiates connection attempts by calling `selectBlockNodeForStreaming()`.
- Enters a loop checking if any connection is in `ACTIVE` state.
- Decrements remaining time and logs wait status at each iteration.
- Returns `true` when a connection becomes active.
- Returns `false` and logs a warning if timeout elapses without an active connection.
- Restores interrupt flag, logs, and returns `false` if interrupted.

**Edge Cases and Error Handling:**
- Handles `InterruptedException`.
- Uses `Thread.sleep(1000)` to prevent busy waiting.
- Logs warning if no active connection by timeout.

**Thread Safety:**
- Reads from thread-safe `ConcurrentHashMap` without modifying shared state.

---

#### openBlock(...)

**Purpose:**  
Signals the currently active `BlockNodeConnection` that a new block should be streamed, if an active connection exists.

**Parameters:**
- `blockNumber`: The number of the block to be opened and streamed.

**Detailed Behaviour:**
- Fetches current active connection with `getActiveConnection()`.
- Logs a warning and returns if no active connection is found.
- Sets current block number (default -1 if not set) and notifies connection of the new block.

**Edge Cases and Error Handling:**
- Logs warning and exits if no active connection.
- Default block number is `-1` if not set, it does not overwrite it if already set.

**Thread Safety:**
- Uses read-only logic without modifying shared state.

---

#### notifyConnectionsOfNewRequest()

**Purpose:**  
Notifies the active connection that a new data request has been made.

**Parameters:** None

**Detailed Behaviour:**
- Retrieves current active connection.
- If exists, calls `notifyNewRequestAvailable()` on the connection.

**Edge Cases and Error Handling:**
- Returns immediately if no active connection.
- Does not change internal state.

**Thread Safety:**
- Read-only access only.

---

#### selectBlockNodeForStreaming()

**Purpose:**  
Selects the next best block node based on priority and initiates a connection attempt.

**Parameters:** None

**Detailed Behaviour:**
- Synchronizes on the connections map to prevent concurrent changes.
- Calls `getNextPriorityBlockNode()` to find the highest-priority unused node.
- If a node is found, logs selection and calls `connectToNode()` to initiate connection.

**Edge Cases and Error Handling:**
- Logs and exits if no node is selected.

**Thread Safety:**
- Synchronizes on connections to ensure single-threaded node selection.

---

#### getNextPriorityBlockNode()

**Purpose:**  
Selects the most suitable `BlockNodeConfig` to initiate a new connection based on priority, availability, and retry status.

**Parameters:** None

**Detailed Behaviour:**
- Logs attempt to select next block node by priority.
- Fetches all nodes from `blockNodeConfigurations.getAllNodes()`.
- Groups nodes by priority (lower number = higher priority).
- Retrieves current minimum priority via `getCurrentMinPriority()`.
- Iterates through nodes, skipping those with priority ≤ current minimum or in PENDING state.
- Randomly selects a node from the first eligible priority group.
- Returns `null` if no eligible nodes found.

**Edge Cases and Error Handling:**
- If no active connection, all nodes including lower priorities are eligible.
- Returns `null` if all nodes are in PENDING state.
- Random selection distributes load among eligible nodes.

**Thread Safety:**
- Only reads from the thread-safe connections map, no shared state changes.

---

#### getCurrentMinPriority()

**Purpose:**  
Determines the priority of the currently active connection with the highest priority.

**Parameters:** None

**Detailed Behaviour:**
- Streams connection values, filters ACTIVE connections.
- Maps connections to their priority and returns the minimum.
- Returns `Integer.MAX_VALUE` if no active connections exist.

**Edge Cases and Error Handling:**
- Defaults to `Integer.MAX_VALUE` when no active connection is present.

**Thread Safety:**
- Read-only access to thread-safe `ConcurrentHashMap`.

---

#### BlockNodeConnection getActiveConnection()

**Purpose:**  
Returns the currently active `BlockNodeConnection`, if any.

**Parameters:** None

**Detailed Behaviour:**
- Streams over connections, filters for ACTIVE, returns the first found.

**Edge Cases and Error Handling:**
- Returns `null` if no active connection exists.

**Thread Safety:**
- Read-only operation on thread-safe `ConcurrentHashMap`.

---

#### connectToNode(...)

**Purpose:**  
Creates the initial connection attempt to a Block Node, schedules the connection immediately with zero delay using the retry mechanism.

**Parameters:**
- `node`: A `BlockNodeConfig` object representing a block node configuration.

**Detailed Behaviour:**
- Logs scheduling of connection attempt.
- Creates a new gRPC client using `createNewGrpcClient(node)`.
- Constructs a new `BlockNodeConnection` with required dependencies.
- Adds connection to internal map keyed by node config.
- Schedules immediate connection attempt with zero delay via `scheduleRetry(connection, Duration.ZERO)`.
- Logs and handles errors during client creation or setup gracefully; no retries scheduled on failure.

**Edge Cases and Error Handling:**
- Catches all exceptions during creation or scheduling.
- Zero delay means immediate retry but via scheduled task.
- On failure, node will not retry until selected again internally.

**Thread Safety:**
- Relies on thread-safe connection map and scheduling mechanisms.

---

#### isRetrying(...)

`BlockNodeConnection blockNodeConnection`

**Purpose:**  
Checks if the specified connection is currently scheduled for retry or actively retrying.

**Parameters:**
- `blockNodeConnection`: The connection instance to check.

**Detailed Behaviour:**
- Checks if the connection is in the retry map.
- Checks if the connection’s internal retry flag is set.
- Returns `true` if either condition is met.

**Edge Cases and Error Handling:**
- Returns `false` if the connection is null or not tracked.

**Thread Safety:**
- Read-only check on thread-safe structures.

---

### getHighestPriorityPendingConnection(…)

**Purpose:**  
This method finds the best higher-priority connection that is currently in PENDING state in comparison to another one, given to the method as a parameter.

**Parameters:**  
- `currentConnection` - the BlockNodeConnection given for priority comparison

**Detailed Behaviour:**  
- The method iterates over all connections in the connections map and filters those connections that are in PENDING state.  
- From that list it selects the connection with higher priority than the current connection and returns the one that is with highest priority among all that are eligible or returns `null` if none is found.

**Edge Cases and Error Handling:**  
- If no PENDING connection is found, the method returns `null`.

**Thread Safety:**  
- Method is considered thread-safe as it executes only read operations on `ConcurrentHashMap`.

---

### higherPriorityStarted(…)

**Purpose:**  
The method determines whether there is a pending connection with higher priority than the current one, and if so, the newly found connection is promoted to ACTIVE and the current connection is terminated.

**Parameters:**  
- `blockNodeConnection`: The current active connection that is used as a reference for comparing priorities.

**Detailed Behaviour:**  
- The method first locks on connection to ensure thread-safe evaluation.  
- `getHighestPriorityPendingConnection(blockNodeConnection)` is then called to find better connection.  
- If such connection is found, the pending connection is promoted to ACTIVE state and request worker thread is started for it, enabling streaming of requests.  
- The method logs message indicating the transition to a higher priority pending connection.  
- The current connection is closed and removed from the connections map.  
- The method returns `true` when the transition was successful and `false` if no higher-priority connection exists.

**Edge Cases and Error Handling:**  
- In the case when no higher priority pending connection is found, the transition is not performed and safe fallback is implemented.  
- The method assures safe handling even if the map is empty or the current connection is obsolete.

**Thread Safety:**  
- The method is considered thread-safe due to the synchronisation.

---

### updateLastVerifiedBlock(…)

**Purpose:**  
Updates the record of the most recently verified block number for a specific block node, if the new block number is higher than the previous one.

**Parameters:**  
- `blockNodeConfig`: The block node whose verification state is being updated.  
- `blockNumber`: The newly verified block number (nullable).

**Detailed Behaviour:**  
- The method updates the internal `lastVerifiedBlockPerConnection` map only if `blockNumber` is greater than the currently recorded one.  
- If `blockNumber` is not null, the method also updates the `blockStreamStateManager` with it.

**Edge Cases and Error Handling:**  
- Method handles cases when the `blockNumber` can be null.  
- If the `blockNodeConfig` is null, the `requireNonNull` will throw a `NullPointerException`.  
- The method does not update state if `blockNumber ≤ latestBlock`.

**Thread Safety:**  
- The method uses `ConcurrentHashMap` and there is no external synchronisation needed.

---

### getLastVerifiedBlock(…)

**Purpose:**  
Retrieves the last verified block number for the given block node.

**Parameters:**  
- `blockNodeConfig` - The block node whose state is queried.

**Detailed Behaviour:**  
- The method returns the last verified block number if available, otherwise it returns `-1L` as default.  
- Additionally, `computeIfAbsent` is used to lazily initialize missing entries with `-1L`.

**Edge Cases and Error Handling:**  
- If the node has no verified block yet, the method returns `-1L` and the `blockNodeConfig` is required and null-checked.

**Thread Safety:**  
- The method ensures thread-safety by using `computeIfAbsent` on a `ConcurrentHashMap`.

---

### NoOpConnection

The `NoOpConnection` class is a static singleton used to represent a no-operation connection in place of null. It implements the `BlockNodeConnection` interface but performs no meaningful work.

- This avoids null checks across the codebase and safely handles failure or uninitialized states in the connection lifecycle (e.g., retry map placeholders, failed connection setups).  
- It uses the Null Object Pattern to provide a predictable, inert implementation.

**Overridden No-Op Methods:**  
The following methods are overridden and implemented as no-ops:  
- `onNext(PublishStreamResponse response)`  
- `onError(Throwable throwable)`  
- `onCompleted()`  
- `sendRequest(PublishStreamRequest request)`  
- `isActive()`  
- `close()`  
- `getNodeConfig()`  
- `updateConnectionState(ConnectionState newState)`  
- `setCurrentBlockNumber(long blockNumber)`  
- `getCurrentBlockNumber()`  
- `notifyNewBlockAvailable()`  
- `notifyNewRequestAvailable()`  
- `jumpToBlock(long blockNumber)`

---

### BlockNodeConnectionTask

This inner class is a `Runnable` responsible for managing connection attempts to a `BlockNodeConnection`, by implementing the following:

- Sets up initial connection attempts or retries if failures occur.  
- Implements exponential backoff with jitter to avoid thundering herd problems.  
- Coordinates connection state transitions (`UNINITIALIZED → PENDING → ACTIVE`).  
- Ensures only one active connection at a time, honouring connection priority.  
- Handles exceptions gracefully and reschedules itself using `ScheduledExecutorService`.

**Key Behaviour:**  
- Initial Delay Handling: Sets connection to PENDING if delay is non-zero.  
- Thread Safety: Synchronizes on the shared connections map.  
- Backoff Strategy:  
  - Uses exponential delay up to a max (`MAX_RETRY_DELAY`).  
  - Applies random jitter to prevent synchronized retries.  
- Failsafe: If scheduling fails entirely, the connection is removed and closed.

**Important No-op Cases Handled:**  
- Already active connections.  
- Lower-priority connection attempts when a higher-priority one is already active.

---

## Summary of Connection Lifecycle

1. On startup, `waitForConnection()` triggers initial `selectBlockNodeForStreaming()` to choose the best node.  
2. `connectToNode()` creates the gRPC client and schedules an immediate connection attempt.  
3. Upon successful connection, the connection state becomes `ACTIVE`.  
4. Blocks are streamed via the active connection, `openBlock()` notifies the connection of new blocks.  
5. Multiple connections may exist in different states (`PENDING`, `ACTIVE`) prioritized by priority levels.  
6. `getHighestPriorityPendingConnection()` finds the best pending connection with higher priority than the current active one.  
7. `higherPriorityStarted()` promotes higher-priority pending connections to active, closing lower-priority active connections safely.  
8. On failure, `handleConnectionError()` schedules retry with delay and selects next node if necessary.  
9. Retry is scheduled via `scheduleRetry()` using exponential backoff with jitter.  
10. `NoOpConnection` is used as a safe inert placeholder connection to avoid null checks and simplify error handling.  
11. `BlockNodeConnectionTask` manages connection attempts, backoff, and state transitions while ensuring thread safety.  
12. When shutting down, all connections and executor services are closed.

---