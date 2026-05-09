---

title: Reasons not to gossip
kind: architecture-topic
last_reviewed: TBD
------------------

# Reasons not to gossip

This topic catalogues the categorical rules that cause a node to refrain
from gossiping in current code: rules whose intervention shape is
all-or-nothing. A condition is true and a particular kind of gossip
stops; the condition flips and gossip resumes. The catalog covers rules
that suppress gossip globally, rules that suppress a class of messages
(e.g., broadcasts), and rules that skip a specific peer.

## Responsibilities

This topic is the reference for *why a node may not be gossiping*. It is
deliberately a catalog rather than a narrative: each rule names the
guard, the trigger, what is suppressed, and the rationale (where
visible). Adding or removing a rule should mean adding or removing one
subsection of the same shape.

In scope:

- Categorical guards in [`consensus-gossip-impl`](../../../../consensus-gossip-impl)
  that suppress sync initiation, sync acceptance, broadcast, or specific
  events.
- The durability ordering between Pre-Consensus Event Stream (PCES) and
  gossip for self-events.

Out of scope:

- Queue-driven backpressure (see
  [`topics/health-monitor-and-backpressure.md`](health-monitor-and-backpressure.md)).
- Future-state peer-discipline rules from the proposal's Sheriff module
  (see [Future state](#future-state) below).
- Rules that suppress event *creation* rather than event *gossip* (see
  [`topics/event-creator.md`](event-creator.md)). When event creation
  stops, there is eventually nothing new to gossip, but the suppression
  point is upstream and is catalogued there.

## Distinction from backpressure

Backpressure is queue-driven and graded: as a queue grows, throttling
becomes stronger; as it drains, throttling relaxes. Reasons-not-to-gossip
are categorical: a condition is true and a particular gossip behaviour
stops, then resumes when the condition flips. The shape of the
intervention is the test.

Several guards in
[`consensus-gossip-impl`](../../../../consensus-gossip-impl) are
queue- or health-driven and therefore belong with backpressure rather
than in this catalog: permit acquisition
(`PermitProvider.acquire()` in `RpcPeerProtocol.shouldSwitchToRpc()`),
`IntakeEventCounter.hasUnprocessedEvents()`, `PermitProvider.isHealthy()`,
the `RpcOverloadMonitor` output-queue and ping-latency checks, and the
`ignoreIncomingEvents` flag. They are listed here only by name; full
treatment is in
[`topics/health-monitor-and-backpressure.md`](health-monitor-and-backpressure.md).

## Catalog of rules

Each rule below is a categorical guard found in current code. Code
anchors cite module/file/class/method; line ranges are accurate at last
review and may shift with refactors.

### Self-event must be durably persisted before gossip

- Trigger: a self-event has been created but has not yet been written
  through the PCES writer.
- Suppresses: gossiping the self-event (and, transitively, any later
  self-event built on it).
- Code anchor: [`consensus-pces`](../../../../consensus-pces) PCES writer;
  the wiring routes self-events through the writer before they reach the
  gossip path. Configuration: `event.preconsensus.inlinePcesSyncOption`
  (default `EVERY_SELF_EVENT`).
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
- Rationale: log marker at `RpcProtocol.stop()` and the comment in
  `RpcPeerProtocol` (~line 411) state that `gossipHalted` is the
  pre-reconnect signal — existing syncs drain, no new syncs start, the
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
  `STATUSES_THAT_PERMIT_SYNC`. Cross-link:
  [`topics/freeze-and-upgrade.md`](freeze-and-upgrade.md) for which
  statuses arise during freeze.

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
  might be a recovery phase". The `communicationOverload` term overlaps
  with the backpressure boundary and is treated there; the other terms
  are categorical.

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
- Rationale: [TBD: question for engineer — In `RpcPeerHandler.checkForPeriodicActions`,
  a per-peer cooldown gates fresh sync initiation. What scenario
  motivated this — letting the local intake pipeline drain, spreading
  load across peers, avoiding duplicate work after a recent successful
  sync, or something else? What value of `rpcSleepAfterSync` is in use
  and what symptom appeared with shorter values?]

### Peer still sending events

- Trigger: `state.peerStillSendingEvents == true` from an earlier sync
  whose receive phase has not yet ended.
- Suppresses: starting a new sync with this peer.
- Code anchor:
  [`RpcPeerHandler.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java)
  `#checkForPeriodicActions` (lines 199–202); flag cleared in
  `#receiveEventsFinished` (line 363).
- Rationale: [TBD: question for engineer — This guard prevents a fresh
  sync from starting while the peer is still streaming events from the
  prior one. Is the intent purely to keep the per-peer protocol
  serial, or is there a downstream symptom (duplicate ingest, message
  reordering, intake-counter drift) that this rule is preventing?]

### Sync already in progress with this peer

- Trigger: `state.mySyncData != null` — a sync this side has initiated
  is still active.
- Suppresses: starting a second sync with the same peer.
- Code anchor:
  [`RpcPeerHandler.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java)
  `#checkForPeriodicActions` (lines 209, 225–228).
- Rationale: [TBD: question for engineer — Is this strictly the
  one-sync-per-peer-at-a-time invariant, or is there a deeper protocol
  reason (e.g., shared state in `mySyncData` that cannot tolerate
  reentry)? What goes wrong if a second sync starts before the first
  cleans up?]

### Fair selector did not authorize a new sync to this peer

- Trigger: `syncGuard.isSyncAllowed(peerId)` returned `false`.
- Suppresses: this side initiating a sync to this peer (an
  already-arriving remote sync request still proceeds — see the
  `onForcedSync` branch in `#checkForPeriodicActions`).
- Code anchor:
  [`RpcPeerHandler.java`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java)
  `#checkForPeriodicActions` (lines 212–215).
- Rationale: [TBD: question for engineer — The fair selector spreads
  outgoing-sync attempts across peers. What problem motivated it —
  starvation of slower neighbours, thundering-herd against a single
  peer, or balancing the inbound/outbound sync ratio? Is the policy
  round-robin, or is it weighted?]

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

> **Future state.** The proposal introduces a separate **Sheriff** module
> that aggregates misbehavior reports from Gossip and Event Intake and
> decides when to "shun" or "welcome" a neighbor. No `Sheriff` module or
> class exists in current code; the rules in this catalog are
> distributed across the gossip protocol classes. Some rules — for
> example "peer fallen behind" and parts of the broadcast-not-running
> composite — may move under Sheriff once it lands; others are protocol
> invariants that will not. This file describes current code only; the
> proposal is in
> [`Consensus-Layer.md`](../../../proposals/consensus-layer/Consensus-Layer.md).
