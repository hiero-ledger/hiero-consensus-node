---
type: architecture-topic
title: Reasons not to gossip
last_reviewed: TBD
---

# Reasons not to gossip

This topic catalogues the rules that cause a node to reduce or stop
gossiping in current code. Some rules suppress gossip entirely; some
suppress a class of messages (e.g., broadcasts); some skip a specific
peer; and some throttle gossip gradually under load (backpressure).

## Responsibilities

This topic is the reference for *why a node may not be gossiping*. It is
deliberately a catalog rather than a narrative: each rule names the
guard, the trigger, what is suppressed, and the rationale (where
visible). Adding or removing a rule should mean adding or removing one
subsection that follows the same template.

In scope:

- Guards in [`consensus-gossip-impl`](../../../../consensus-gossip-impl)
  that suppress sync initiation, sync acceptance, broadcast, or specific
  events.
- Queue- and health-driven backpressure that throttles gossip gradually.
  This catalog names backpressure as a reason to reduce gossip; the
  mechanical details (permit acquisition, intake counters, overload
  monitors, thresholds) live in
  [`topics/health-monitor-and-backpressure.md`](health-monitor-and-backpressure.md).
- The durability ordering between Pre-Consensus Event Stream (PCES) and
  gossip for self-events.

Out of scope:

- Future-state peer-discipline rules from the proposal's Sheriff module
  (see [Future state](#future-state) below).
- Rules that suppress event *creation* rather than event *gossip* (see
  [`topics/event-creator.md`](event-creator.md)). When event creation
  stops, the node has no new self-events to send, but it can still
  receive and forward peer events. The suppression point is upstream
  and is catalogued there.

## Catalog of rules

Each rule below is a guard found in current code. Most rules are binary
(the condition flips and gossip stops or resumes); the backpressure
entry is graded. Code anchors cite module/file/class/method; line
ranges are accurate at last review and may shift with refactors.

### Self-event must be durably persisted before gossip

- Trigger: a self-event has been created but has not yet been written
  through the PCES writer.
- Suppresses: gossiping the self-event (and, transitively, any later
  self-event built on it).
- Code anchor: [`consensus-pces`](../../../../consensus-pces) PCES writer;
  the wiring routes self-events through the writer before they reach the
  gossip path. Configuration: `event.preconsensus.inlinePcesSyncOption`
  (TUN-129; default `DONT_SYNC` — the durability guarantee holds without a
  per-event fsync, see [`restart-and-pces.md`](restart-and-pces.md)).
- Rationale: a self-event gossiped before persistence can cause a branch
  on restart, since the node may rebuild a different self-event on the
  same self-parent. Documented in
  [`platform-sdk/docs/core/inlinePces/inlinePces.md`](../../../core/inlinePces/inlinePces.md).
  Cross-link: [`topics/restart-and-pces.md`](restart-and-pces.md).

### Gossip globally halted

- Trigger: `gossipHalted` is set to `true` (typically by `RpcProtocol.stop()`
  or `RpcProtocol.pause()`).
- Suppresses: all sync initiation, sync acceptance, and message
  dispatch on every peer connection.
- Code anchor:
  [`consensus-gossip-impl/.../RpcProtocol.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcProtocol.java)
  `#stop()` (lines 211–222), `#pause()` (lines 236–242); flag read in
  [`RpcPeerProtocol.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcPeerProtocol.java)
  (sync initiation around lines 261–276, dispatch loop around lines 340–342,
  message-processing check around lines 416–418).
- Rationale: the node has fallen far enough behind that some events it
  needs have been expired by enough peers, so it must reconnect rather
  than catch up via gossip. Once a reconnect is required, receiving
  more events over gossip does nothing to help. `gossipHalted` is the
  pre-reconnect signal: existing syncs drain, no new syncs start, the
  protocol exits to free permits and the connection. Cross-link:
  [`topics/reconnect.md`](reconnect.md).

### Platform status does not permit sync

- Trigger: `PlatformStatus` is anything outside the allow-list `{ACTIVE,
  FREEZING, FREEZE_COMPLETE, OBSERVING, CHECKING, RECONNECT_COMPLETE}`.
- Suppresses: sync initiation and sync acceptance with all peers.
- Code anchor:
  [`consensus-gossip-impl/.../sync/protocol/SyncStatusChecker.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/sync/protocol/SyncStatusChecker.java)
  (lines 17–23); read by
  `RpcPeerProtocol.shouldSwitchToRpc()`.
- Rationale: only specific platform lifecycle statuses are safe for
  exchanging events; the allow-list is explicit in
  `STATUSES_THAT_PERMIT_SYNC`. The statuses *not* in the allow-list
  and why each blocks sync:
  - `STARTUP` — the system is initializing and not yet ready to sync.
  - `REPLAYING_EVENTS` — the node is replaying events from local PCES
    to catch up to where it left off, and is not yet ready to accept
    new events.
  - `BEHIND` — the node needs to reconnect, and gossiping will not
    help.
  - `CATASTROPHIC_FAILURE` — a major error has occurred and the node
    is no longer operational, so gossiping does not help.

  Cross-link: [`topics/freeze-and-upgrade.md`](freeze-and-upgrade.md)
  for which statuses arise during freeze.

### Node overloaded — backpressure throttles gossip

- Trigger: queue depths, intake backlog, or health and latency signals
  indicate the node is falling behind on processing events.
- Suppresses: gossip gradually — fewer new syncs are initiated or
  accepted and fewer incoming events are processed as load rises;
  throttling relaxes as queues drain. Unlike the other rules in this
  catalog, the response is graded rather than binary.
- Code anchor: multiple mechanisms in
  [`consensus-gossip-impl`](../../../../consensus-gossip-impl) —
  `PermitProvider.acquire()` (called from
  `RpcPeerProtocol.shouldSwitchToRpc()`),
  `PermitProvider.isHealthy()`, the `RpcOverloadMonitor` output-queue
  and ping-latency checks, and the `ignoreIncomingEvents` flag.
- Rationale: gossiping faster than the node can process events grows
  queues without making progress; throttling lets the local pipeline
  drain. Full treatment of each mechanism, the signals that feed it,
  and how thresholds are tuned is in
  [`topics/health-monitor-and-backpressure.md`](health-monitor-and-backpressure.md).

### Peer fallen behind — do not sync with that peer

- Trigger: prior sync detected `FallenBehindStatus.OTHER_FALLEN_BEHIND`
  and set `state.peerIsBehind = true`.
- Suppresses: new syncs with this peer; broadcasts to this peer (via the
  composite `isBroadcastRunning()` guard below).
- Code anchor:
  [`consensus-gossip-impl/.../shadowgraph/RpcPeerHandler.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java)
  `#checkForPeriodicActions` (lines 194–197); flag set around line 415 in
  `#maybeBothSentSyncData`; also gates `#isBroadcastRunning()` (line 541).
- Rationale: comment near line 265 — "don't spam remote side if it is
  going to reconnect". The peer is presumed to be entering its reconnect
  flow and cannot usefully receive further events over gossip until it
  rejoins. Cross-link: [`topics/reconnect.md`](reconnect.md).

### Broadcast disabled by configuration

- Trigger: `broadcastConfig.enableBroadcast()` is `false`.
- Suppresses: the simplistic-broadcast path for self-events to all peers
  (sync still runs normally and remains the channel for self-events).
- Code anchor:
  [`consensus-gossip-impl/.../RpcProtocol.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcProtocol.java)
  `#addEvent` (lines 186–193); also a term in
  [`RpcPeerHandler.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java)
  `#isBroadcastRunning()` (line 540).
- Rationale: feature flag. With broadcast disabled, all events flow
  through sync only.

### Broadcast not running for this peer

- Trigger: composite — false if any of these is true: broadcast disabled
  in config; `state.peerIsBehind`; `state.lastSyncFinishedTime ==
  Instant.MIN` (no successful sync yet); `communicationOverload`.
- Suppresses: out-of-sync broadcast of self-events to this specific peer
  (sync may still run when permitted).
- Code anchor:
  [`RpcPeerHandler.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java)
  `#isBroadcastRunning()` (lines 538–544); guards `#broadcastEvent`
  (lines 264–273).
- Rationale: comment lines 265–266 — "don't spam remote side if it is
  going to reconnect or if we haven't completed even a first sync, as it
  might be a recovery phase". The `communicationOverload` term is a
  backpressure signal (see "Node overloaded — backpressure throttles
  gossip" above); the other three terms are binary.

### Self-event holdback while broadcast is running

- Trigger: broadcast currently running for this peer **and** a candidate
  self-event is younger than `selfFilterThreshold` (or its recursive
  parent younger than `ancestorFilterThreshold`).
- Suppresses: inclusion of those events in the sync send-list. They are
  held back from sync because the broadcast channel is preferred for
  fresh self-events.
- Code anchor:
  [`consensus-gossip-impl/.../shadowgraph/SyncUtils.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/SyncUtils.java)
  `#filterLikelyDuplicates` (lines 70–115); thresholds plumbed by
  [`ShadowgraphSynchronizer.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/ShadowgraphSynchronizer.java)
  (lines 191–192), which passes `Duration.ZERO` when broadcast is not
  running.
- Rationale: comment at `SyncUtils` lines 90–91 — when broadcast is
  disabled the threshold is zero so self-events flow through sync
  immediately; when broadcast is active, sync defers them to avoid
  duplicate transmission across the two channels.

### Sync cooldown after last sync

- Trigger: less than `rpcSleepAfterSync()` has elapsed since
  `lastSyncFinishedTime` for this peer.
- Suppresses: starting a new sync with this peer (acceptance still
  works).
- Code anchor:
  [`RpcPeerHandler.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java)
  `#checkForPeriodicActions` (lines 189–192) via
  `#isSyncCooldownComplete()`.
- Rationale: when broadcast is enabled, broadcast is the primary
  channel for event propagation and sync runs periodically as a backup
  for events that broadcast missed. The cooldown enforces this
  cadence — without it, sync would run back-to-back and overlap
  broadcast, since in the window between sending our tipset and
  receiving the peer's response the peer may broadcast events to us
  that the sync has already marked as missing, causing duplicate
  transmission.

### Peer still sending events

- Trigger: `state.peerStillSendingEvents == true` from an earlier sync
  whose receive phase has not yet ended.
- Suppresses: starting a new sync with this peer.
- Code anchor:
  [`RpcPeerHandler.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java)
  `#checkForPeriodicActions` (lines 199–202); flag cleared in
  `#receiveEventsFinished` (line 363).
- Rationale: prevents starting a new sync while the peer is still
  sending events from the prior one, to avoid overlapping sync logic
  and possible duplicate events.

### Peer's prior events not yet processed in intake

- Trigger: `intakeEventCounter.hasUnprocessedEvents(peerId)` returns
  `true` — at least one event received from this peer in the prior
  sync is still in the intake pipeline and has not yet reached the
  shadowgraph.
- Suppresses: starting a new sync with this peer.
- Code anchor:
  [`RpcPeerHandler.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java)
  `#checkForPeriodicActions` (line 204); per-peer counters in
  [`DefaultIntakeEventCounter.java`](../../../../consensus-utility/src/main/java/org/hiero/consensus/event/DefaultIntakeEventCounter.java)
  `#hasUnprocessedEvents`.
- Rationale: the peer just sent us a batch of events. Until those
  events finish intake and land in the shadowgraph, a new sync would
  cause the peer to re-send the same events, since the shadowgraph
  does not yet reflect what we have already received. Waiting for
  intake to drain avoids that redundant transmission.

### Sync already in progress with this peer

- Trigger: `state.mySyncData != null` — a sync this side has initiated
  is still active.
- Suppresses: starting a second sync with the same peer.
- Code anchor:
  [`RpcPeerHandler.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java)
  `#checkForPeriodicActions` (lines 209, 225–228).
- Rationale: prevents duplicate events and protects the internal
  per-sync data structures (`mySyncData`) that the active sync owns.

### Fair selector did not authorize a new sync to this peer

- Trigger: `syncGuard.isSyncAllowed(peerId)` returned `false`.
- Suppresses: this side initiating a sync to this peer (an
  already-arriving remote sync request still proceeds — see the
  `onForcedSync` branch in `#checkForPeriodicActions`).
- Code anchor:
  [`RpcPeerHandler.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java)
  `#checkForPeriodicActions` (lines 212–215).
- Rationale: a currently *disabled* round-robin selector for outgoing
  syncs. The motivation was preventing this node from only ever
  syncing with a subset of peers — without round-robin, a misbehaving
  subset could be repeatedly selected and starve this node of useful
  events from the rest of the network. The guard remains in the call
  path and will fire if the selector is re-enabled.

## Cross-references

**Topics**

- [`topics/gossip.md`](gossip.md)
- [`topics/event-creator.md`](event-creator.md)
- [`topics/health-monitor-and-backpressure.md`](health-monitor-and-backpressure.md)
- [`topics/freeze-and-upgrade.md`](freeze-and-upgrade.md)
- [`topics/reconnect.md`](reconnect.md)
- [`topics/restart-and-pces.md`](restart-and-pces.md)

**Invariants**

- [TBD: INV-NNN — link once `invariants.md` catalog populates. Likely
  candidates: durability-before-gossip; gossip suspended during
  reconnect; one-active-sync-per-peer.]

**Decisions**

- [TBD: ADR-NNN — link once `decisions/` catalog populates. Likely
  candidates: the platform-status allow-list; the simplistic-broadcast
  channel and its disable conditions; the sync-cooldown duration.]

**Scenarios**

- [TBD: SCN-NNN — silent-node and partial-gossip scenarios are likely
  seeds; route through this catalog to identify which rule is firing.]

## Future state

> **Future state.** The proposal's **Sheriff** module (described for the
> whole layer in the [overview's Future state](../overview.md#future-state))
> would absorb some peer-discipline rules. Of this catalog, "peer fallen
>
>> behind" and parts of the broadcast-not-running composite may move under
>> Sheriff once it lands; others are protocol invariants that will not. No
>> `Sheriff` exists in current code; this file describes current code only.
