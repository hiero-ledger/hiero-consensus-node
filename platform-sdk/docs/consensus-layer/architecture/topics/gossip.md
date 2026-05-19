---
title: Gossip
kind: architecture-topic
last_reviewed: TBD
---

# Gossip

## Responsibilities

Gossip is the subsystem that exchanges events with other peers. It owns the per-connection protocol stack, the sync logic that reconciles two peers' shadowgraphs, fair selection of sync partners, and the simple broadcast that pushes self-events to all neighbours. It does not own event validation, orphan buffering, hashgraph-side ingest, reconnect state transfer, or backpressure mechanics — those are owned by the topics linked at the bottom of this file.

Gossip owns:

- **Event propagation between peers** — RPC Sync protocols carry events across connections.
- **The peer protocol stack** — Heartbeat, RPC, and Reconnect protocols sharing the same connection.
- **Fair sync selection** — limiting concurrent syncs and rotating through peers so no peer is starved (currently disabled).
- **Simple broadcast** — pushing self-events to all connected peers, layered on the RPC sync connection.

Gossip does **not** own:

- Orphan buffering or hashgraph ingest — see [event-intake.md](event-intake.md).
- Reconnect state transfer (learner/teacher protocol) — see [reconnect.md](reconnect.md). The gossip-connection-level Reconnect *protocol* lives here; the reconnect *mechanism* itself does not.
- Permit issuance and queue health — see [health-monitor-and-backpressure.md](health-monitor-and-backpressure.md).

## Protocol stack

The legacy network layer negotiates one of three protocols on each connection. Once a protocol is selected, it holds the socket until it completes or yields to reconnect. RPC Sync is the workhorse; Heartbeat keeps idle connections alive; Reconnect transfers control to the reconnect mechanism when a peer falls behind.

### Heartbeat

`HeartbeatProtocol` (`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/HeartbeatProtocol.java`) keeps connections alive when another protocol holds the socket on a different connection. Within RPC, the protocol carries its own `PING` / `PING_REPLY` messages (see [rpc-gossip.md](../../../core/gossip/rpc/rpc-gossip.md)) so it does not need to hand the socket back to Heartbeat.

### RPC

`RpcProtocol` (`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcProtocol.java`) is the factory for the per-peer RPC pipeline. Once selected, it holds the connection and multiplexes two layered subsystems — sync (see [RPC sync](#rpc-sync)) and broadcast (see [Simple broadcast](#simple-broadcast)) — plus ping/keepalive messages. It only releases the connection when the reconnect protocol needs to take over.

### Reconnect (gossip-side)

`ReconnectProtocolFactory` (`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/reconnect/ReconnectProtocolFactory.java`) is the gossip-connection-level protocol that yields the socket to the reconnect mechanism when one peer has fallen behind. It is distinct from the reconnect mechanism itself (state transfer, learner/teacher handshake, peer selection); see [reconnect.md](reconnect.md) for that.

## RPC sync

The RPC Sync layer is responsible for the actual per-peer conversation. Three threads are created per connection:

- **Reader** — blocking reads from the socket; deserializes messages onto an input queue.
- **Dispatch** — single-threaded execution of business logic; consumes the input queue and produces messages onto the output queue. All shared-state mutations on the per-peer side run on this thread.
- **Writer** — blocking writes from the output queue; piggybacks periodic ping emission.

Per-peer classes (one instance per connection):

- `RpcPeerProtocol` (`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcPeerProtocol.java`) — the per-peer state machine. Tracks the `SyncPhase` of the conversation: `IDLE`, `EXCHANGING_WINDOWS`, `EXCHANGING_TIPS`, `EXCHANGING_EVENTS`, `SENDING_EVENTS`, `RECEIVING_EVENTS`, plus terminal states (`OTHER_FALLEN_BEHIND`, `SELF_FALLEN_BEHIND`, `GOSSIP_HALTED`, `PLATFORM_STATUS_PREVENTING_SYNC`, `NO_PERMIT`, `OUTSIDE_OF_RPC`) — see `SyncPhase` (`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/SyncPhase.java`).
- `RpcPeerHandler` (`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java`) — sync-specific conversation logic for one peer. Receives deserialized messages and drives the three-phase exchange.
- `GossipRpcSender` (`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/rpc/GossipRpcSender.java`) — outbound interface. Methods: `sendSyncData`, `sendTips`, `sendEvents`, `sendBroadcastEvent`, `sendEndOfEvents`, `breakConversation`.

Cross-connection (singleton):

- `ShadowgraphSynchronizer` (`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/ShadowgraphSynchronizer.java`) — thread-safe, shared across all peer connections. Builds the send list during phase 3 (`createSendList()`).

> **Delta vs. rpc-gossip.md:** the source doc names two outbound classes as `RpcSender` and `RpcShadowgraphSynchronizer`. In current code these are `GossipRpcSender` (an interface) and `ShadowgraphSynchronizer` (no `Rpc` prefix; the singleton is shared between sync and RPC sync).

For the full message-type catalog (`SYNC_DATA`, `KNOWN_TIPS`, `EVENT`, `EVENTS_FINISHED`, `PING`, `PING_REPLY`, `BROADCAST_EVENT`) and the wire framing (`int16 batchSize`, message-type byte, PBJ-serialized payload), see [rpc-gossip.md](../../../core/gossip/rpc/rpc-gossip.md).

## Three-phase sync protocol

When two peers sync, they exchange enough information to compute the set of events each side is missing, then send those events. The protocol runs over the RPC pipeline; the message types are listed in [rpc-gossip.md](../../../core/gossip/rpc/rpc-gossip.md). Each peer takes an immutable snapshot of its shadowgraph for the duration of the sync — events may be added during the sync (this affects only the booleans exchanged in phase 2), but events may not be removed.

### Phase 1 — window and tip exchange

Each peer sends its current `EventWindow` and the hashes of all its tips ("tip" = an event with no self-child). The container record is `SyncData` (`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/rpc/SyncData.java`); the wire form is `GossipSyncData` (`hapi/hedera-protobuf-java-api/src/main/proto/platform/message/gossip_sync_data.proto`). On receipt, each side consults `FallenBehindStatus` (`platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/monitoring/FallenBehindStatus.java`) to decide whether to abort the sync or record a fallen-behind vote.

### Phase 2 — known-tips boolean exchange

Each peer responds with a boolean per remote tip indicating whether it already has that event. The wire form is `GossipKnownTips` (`hapi/hedera-protobuf-java-api/src/main/proto/platform/message/gossip_known_tips.proto`). The booleans seed `knownSet` — the set of events the remote peer is known to already have.

### Phase 3 — ancestor traversal and send list

Each peer walks ancestors of its tips, filtering by the remote peer's `ancientThreshold`, and builds a `sendList` of events not already in `knownSet`. Sorted topologically and shipped over `GossipRpcSender.sendEvents`. The traversal lives in `ShadowgraphSynchronizer.createSendList()`.

> **Delta vs. sync-protocol.md:** the source doc (dated 2021-10-01) describes phase 1 as exchanging `maxRoundGen`, `minGenNonAncient`, `minGenNonExpired` (generation-based ancient) and phase 3 as filtering with `x.generation >= otherMinGenNonAncient`. Current code uses **birth-round filtering**: the `EventWindow` record (`platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/hashgraph/EventWindow.java`) carries `latestConsensusRound`, `newEventBirthRound`, `ancientThreshold`, and `expiredThreshold`, all in birth-round units, and phase 3 filters with `EventWindow.isAncient(event.getBirthRound())`. The protocol shape is unchanged; the field semantics differ. See [hashgraph.md](hashgraph.md) for birth-round filtering. (Mapping: `maxRoundGen` ↔ `latestConsensusRound`; `minGenNonAncient` ↔ `ancientThreshold`; `minGenNonExpired` ↔ `expiredThreshold`.)

## Simple broadcast

Layered on the RPC pipeline, simple broadcast pushes each self-event to every connected peer as soon as it is created, using the `BROADCAST_EVENT` message type. The send site is `GossipRpcSender.sendBroadcastEvent(GossipEvent)`. Sync runs at a reduced cadence in parallel as a fallback for missed broadcasts and for nodes that are temporarily disconnected.

`RpcOverloadMonitor` (`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcOverloadMonitor.java`) enforces the simple-backpressure rule per peer:

- Output queue size exceeds `BroadcastConfig.throttleOutputQueueThreshold` (default `200` items), or
- Round-trip ping time exceeds `BroadcastConfig.disablePingThreshold` (default `900ms`).

When either trips, broadcast is paused for `BroadcastConfig.pauseOnLag` (default `30s`) and sync handles the connection during the cooldown. See `BroadcastConfig` (`platform-sdk/consensus-gossip/src/main/java/org/hiero/consensus/gossip/config/BroadcastConfig.java`).

Broadcast remains disabled until the initial sync with a peer completes, so newly connected nodes catch up via sync first.

## Fair sync selector

Permits alone do not guarantee fairness: with many peers and few permits, the same peers can be picked repeatedly while others starve. The fair selector sits inside the RPC sync layer to enforce two limits when choosing the next peer to sync with.

Implementation: `LruSyncGuard` (`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/permits/LruSyncGuard.java`), behind the `SyncGuard` interface (`SyncGuardFactory` chooses the implementation; `NoopSyncGuard` is the disabled variant).

Tunables (`platform-sdk/consensus-gossip/src/main/java/org/hiero/consensus/gossip/config/SyncConfig.java`):

- `sync.fairMaxConcurrentSyncs` (default `-1` — disabled). Soft limit on the number of syncs this node will *initiate* concurrently. Incoming syncs from peers are still accepted even when the limit is breached. If `0 < value <= 1`, treated as a ratio of total network size; otherwise the ceiling is the absolute cap.
- `sync.fairMinimalRoundRobinSize` (default `0.3`). Hard lower bound on the number of distinct peers that must be synced with before any peer becomes eligible for a repeat sync. Same ratio/absolute interpretation as above.

> **Delta vs. fair-sync-selector.md:** the source doc names these tunables `maxConcurrentSyncs` and `minimalRoundRobinSize` without the `fair` prefix. Current code uses the `fair`-prefixed names above.

See [../../tunables.md](../../tunables.md) for the cross-topic catalog of gossip tunables.

## Backpressure interaction

Gossip permits, queue-overflow signalling, and the global health-monitor feedback loop are documented in [health-monitor-and-backpressure.md](health-monitor-and-backpressure.md). Gossip consumes those signals (e.g., `RpcOverloadMonitor` pauses broadcast under overload, `SyncConfig.permitsRevokedPerSecond` reduces sync permits when the system is unhealthy) but does not own the policies. No duplicated detail here — see the linked topic.

## Cross-references

- **Topics:** [event-intake.md](event-intake.md), [reconnect.md](reconnect.md), [event-creator.md](event-creator.md), [health-monitor-and-backpressure.md](health-monitor-and-backpressure.md), [reasons-not-to-gossip.md](reasons-not-to-gossip.md). [TBD: these topic files are forthcoming.]
- **Source docs:** [gossip.md](../../../core/gossip/gossip.md), [rpc-gossip.md](../../../core/gossip/rpc/rpc-gossip.md), [simple-broadcast.md](../../../core/gossip/rpc/simple-broadcast.md), [fair-sync-selector.md](../../../core/gossip/syncing/fair-sync-selector.md), [sync-protocol.md](../../../core/gossip/syncing/sync-protocol.md).
- **Tunables:** [../../tunables.md](../../tunables.md).
- **Invariants:** [TBD: INV-NNN once invariants.md catalog populates].
- **Decisions:** [TBD: ADR-NNN once decisions/ catalog populates].

## Future state (sidebar)

The [Consensus-Layer proposal](../../../proposals/consensus-layer/Consensus-Layer.md) introduces a Sheriff module for peer discipline, layered above gossip rather than replacing any current mechanism. Sheriff is not present in current code; this topic intentionally does not assert peer-discipline semantics tied to it.
