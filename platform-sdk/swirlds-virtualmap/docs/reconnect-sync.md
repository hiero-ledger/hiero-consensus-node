# Virtual Map Synchronization During Reconnects

## Background

Reconnects are the way to synchronize the ledger state between nodes when one node falls
behind the network. Such a node is often referred to as a "learner". It selects another
node to fetch all state updates from; that node is called a "teacher".

This document describes how node state, represented as a `VirtualMap`, is synchronized
between the nodes. The process of detecting that a node is behind the network, selecting a
teacher node, and all other steps that precede state synchronization are described in a
separate document.

## State Synchronization

When a reconnect starts, there are two ledger states / virtual maps:

* **teacher state** — a snapshot of a recent network state
* **learner state** — a (potentially much older) snapshot of the network state

An extreme case is when the learner is a node starting from genesis. In this case the entire
state must be transferred from the teacher. This scenario is currently not supported because
network states may be very large and transferring them over the network may be prohibitively
slow. In practice, the learner is usually only slightly behind the network — perhaps a few
minutes or an hour — so the number of changes between teacher and learner states is expected
to be relatively small.

## Key Requirements

The key metric for reconnects is how long synchronization takes. Other metrics such as total
bytes sent or number of disk reads/writes are secondary.

When a learner receives leaf data from the teacher, that data is passed to the virtual hasher
and eventually flushed to disk. The current virtual hasher implementation requires all dirty
leaves to be provided in **ascending path order**. This is why leaves must be processed in
that order during reconnects. Internal nodes may be processed in any order since they are not
directly used in hashing.

Another critical constraint is state size. States may be enormous and the number of changed
nodes may be arbitrary. Reconnects must work even if the entire state is dirty and must be
fully transferred. In particular, no assumption may be made that all dirty or all clean nodes
can be held in memory at once.

## Transfer Modes

There are two different modes for transferring data between teacher and learner:

* **PUSH** — the teacher sends node hashes and leaf data to the learner; the learner responds
  and the teacher adjusts future messages accordingly.
* **PULL** — the learner sends node hashes to the teacher; the teacher responds indicating
  whether each node is clean (hash matches the learner's) or dirty; if the node is a dirty
  leaf, the teacher also includes the leaf data in the response; the learner adjusts future
  requests based on responses.

### PUSH Mode

In PUSH mode both teacher and learner are single-threaded.

|       Role       |          Class           | Threads |                   Responsibility                   |
|------------------|--------------------------|---------|----------------------------------------------------|
| Teacher sender   | `TeacherPushSendTask`    | 1       | Reads node caches level by level, sends to learner |
| Teacher receiver | `TeacherPushReceiveTask` | 1       | Processes learner acknowledgments                  |
| Learner          | `LearnerPushTask`        | 1       | Receives all teacher messages and processes them   |

The teacher sends nodes level by level, waiting for learner acknowledgments before advancing
to the next level. This back-pressure mechanism prevents overwhelming the learner's receive
queue and keeps the protocol simple to reason about.

```
Teacher                                Learner
───────────────────────────────────────────────────────
TeacherPushSendTask ──── lessons ────► LearnerPushTask
TeacherPushReceiveTask ◄─── ACKs ────┘
```

The higher-level tree-view classes `TeacherPushVirtualTreeView` and
`LearnerPushVirtualTreeView` encapsulate the tree traversal logic used by the tasks above.

### PULL Mode

In PULL mode multiple threads work in parallel on both sides for higher throughput.

|       Role       |                Class                | Threads |                  Responsibility                   |
|------------------|-------------------------------------|---------|---------------------------------------------------|
| Learner sender   | `LearnerPullVirtualTreeSendTask`    | 4       | Load learner hashes, send requests to teacher     |
| Learner receiver | `LearnerPullVirtualTreeReceiveTask` | 32      | Process teacher responses, apply leaf data        |
| Teacher receiver | `TeacherPullVirtualTreeReceiveTask` | 16      | Process learner requests, compare hashes, respond |

The asymmetry between senders (4) and receivers (32) on the learner side reflects the fact
that receiving and deserializing leaf data is significantly more expensive than sending hash
queries.

```
Learner                                             Teacher
─────────────────────────────────────────────────────────────────
4x LearnerPullVirtualTreeSendTask ─── requests ──► 16x TeacherPullVirtualTreeReceiveTask
32x LearnerPullVirtualTreeReceiveTask ◄── responses ──┘
```

The higher-level tree-view classes `TeacherPullVirtualTreeView` and
`LearnerPullVirtualTreeView` coordinate the send/receive tasks and manage state.

#### Protocol Messages

Each learner request (`PullVirtualTreeRequest`) carries:
* **path** — the node's position in the virtual tree (use `INVALID_PATH` as an
end-of-stream sentinel)
* **hash** — the learner's current hash for that node

Each teacher response (`PullVirtualTreeResponse`) carries:
* **path** — echoes the requested node path
* **isClean** — `true` if the teacher's hash matches the learner's hash
* **firstLeafPath / lastLeafPath** — included only in the root-node response, informing
the learner of the teacher's leaf path range
* **leaf data** — included only when the node is dirty *and* is a leaf

#### Leaf Ordering in PULL Mode

Because responses can arrive out of order (32 receiver threads working independently), the
learner maintains an `anticipatedLeafPaths` queue and a `responses` map inside
`LearnerPullVirtualTreeView`. Leaf data is buffered until all preceding leaves have been
delivered to the hasher, ensuring it always receives leaves in ascending path order.

## Pull Node Traversal Order

In PULL mode, the `NodeTraversalOrder` interface defines which nodes to request from the
teacher and in what order. Its key methods are:

* `getNextInternalPathToSend()` — called by sending threads to pick the next internal node
* `getNextLeafPathToSend()` — called by sending threads to pick the next leaf node
* `nodeReceived(path, isClean)` — called by receiving threads as a feedback loop so the
  strategy can adapt based on which nodes turned out to be clean or dirty

Three traversal strategies are currently implemented:

### Top-to-Bottom

Traverses the tree rank by rank (breadth-first) from a configurable starting rank (default:
rank 16 or the leaf-parent rank, whichever is lower) down to the leaves. When a node is
confirmed clean, its entire sub-tree is skipped.

### Two-Phase Pessimistic

**Phase 1 — Internal nodes**: the tree is divided into vertical chunks at the leaf-parent
rank. Each chunk's starting path is queried first. If a response comes back clean and the
left child is also clean, the strategy moves up to the parent; otherwise it advances to the
next path in the chunk. The strategy is "pessimistic" in that it treats a node as dirty
whenever no response has arrived for it yet, avoiding stalls.

**Phase 2 — Leaves**: leaves are traversed sequentially from the first to the last leaf
path, skipping any whose parent was confirmed clean in phase 1.

### Parallel Sync

Similar to Two-Phase Pessimistic but the chunks are processed in parallel rather than one
at a time. The rank at which the tree is split into chunks is configurable (default: 16).
Leaves are still traversed sequentially in ascending order to satisfy the hasher requirement.

## Stale Node Removal

After synchronization, nodes that exist in the learner's original state but not in the
teacher's state must be deleted. `ReconnectNodeRemover` tracks every leaf received from the
teacher and schedules learner nodes for removal when they are absent from the teacher's state.

A subtle edge case arises when a key moves from one path to another between the two states
(key relocation). The remover handles this by tracking keys rather than paths, so a key that
moved is correctly preserved and not mistakenly deleted.

## Hashing During Reconnect

Reconnect bypasses the normal cache and hash pipeline to avoid accumulating the entire tree
in memory. Instead:

* `ReconnectHashListener` observes hashing events and writes hashes directly to disk as they
  are computed, rather than buffering them in the pipeline.
* `ReconnectHashLeafFlusher` performs the actual disk writes in memory-efficient batches.

This design means even a full-state transfer — where every leaf is dirty — does not require
more memory than a partial transfer.

## Configuration

The reconnect mode is selected via `VirtualMapReconnectMode`:

|            Value             |           Description            |
|------------------------------|----------------------------------|
| `PUSH`                       | Single-threaded push mode        |
| `PULL_TOP_TO_BOTTOM`         | Breadth-first pull traversal     |
| `PULL_TWO_PHASE_PESSIMISTIC` | Two-phase chunked pull traversal |
| `PULL_PARALLEL_SYNC`         | Parallel-chunk pull traversal    |

Other tunable parameters include:

|            Parameter             |                           Description                           |
|----------------------------------|-----------------------------------------------------------------|
| `pullLearnerRootResponseTimeout` | How long the learner waits for the root response before failing |
| `teacherMaxNodesPerSecond`       | Rate limit on nodes sent by the teacher                         |
| `teacherRateLimiterSleep`        | Sleep duration used by the teacher's rate limiter               |
| `allMessagesReceivedTimeout`     | How long to wait for all in-flight responses before failing     |
