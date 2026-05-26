---
title: Reconnect
kind: architecture-topic
last_reviewed: TBD
---

# Reconnect

## Responsibilities

Reconnect is the recovery path for a node that has fallen so far behind
that gossip alone cannot catch it up. It owns three things: detecting
that the node is behind, transferring a signed state from a healthy
peer (the learner / teacher exchange), and re-anchoring local
components so that event intake, hashgraph, and event creation can
resume from the new round.

It does not own:

- the gossip protocol stack itself — see [`gossip.md`](gossip.md), where
  the reconnect protocol is one of three protocols on the shared
  connection;
- ISS recovery or PCES replay across restart — see
  [`restart-and-pces.md`](restart-and-pces.md);
- signed-state internals (round signing, state hashing, signature
  collection) — see
  [`signed-state-management.md`](signed-state-management.md).

## Lifecycle

The orchestration entry point is the `ReconnectModule` interface in
[`swirlds-platform-core`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/reconnect/ReconnectModule.java),
implemented by
[`DefaultReconnectModule`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/DefaultReconnectModule.java)
in `consensus-reconnect-impl`. `DefaultReconnectModule.initialize`
constructs a `ReconnectController` and starts it on a dedicated
thread.

[`ReconnectController`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectController.java)
runs a continuous loop: it blocks until the node detects it has fallen
behind, then attempts a reconnect, retrying until success or until
configured thresholds are exceeded. Each attempt walks five phases.

1. **Detection.** `ReconnectController` blocks on
   `FallenBehindMonitor.awaitFallenBehind()`. The monitor flips its
   `isBehind` flag once enough peers have reported the local node as
   behind. See [Detection](#detection-fallenbehindmonitor).
2. **Peer selection and handshake.** Teacher selection is random:
   the learner offers the reconnect protocol to peers, and the first
   peer whose `ReconnectStatePeerProtocol.shouldAccept()` returns true
   becomes the teacher. There is no explicit ranking step inside
   `ReconnectController`; state retrieval is asynchronous, with the
   controller waiting on a
   `BlockingResourceProvider<ReservedSignedStateResult>` for the next
   available state.
3. **State transfer.** The receiving side runs
   `ReconnectStateLearner.execute()`; the sending side runs
   `ReconnectStateTeacher.execute()`. See
   [Learner / teacher protocol](#learner--teacher-protocol).
4. **Validation and load.** The default `SignedStateValidator` checks
   only that the received state carries a signature quorum; no roster-
   compatibility or software-version check is performed, because the
   roster does not change between upgrades and nodes on different
   versions do not establish connections in the first place.
   `ReconnectCoordinator.loadReconnectState` then re-initialises every
   component that depends on an event window (hashgraph, event
   intake, shadowgraph, …) against the new state. See
   [Post-reconnect resumption](#post-reconnect-resumption).
5. **Resumption.** `ReconnectController` submits a
   `ReconnectCompleteAction`; the status machine moves the node to
   `RECONNECT_COMPLETE`, then to `CHECKING` once the state is
   persisted to disk, and the loop returns to phase 1.

If an attempt fails, the controller backs off and re-enters detection;
once the count crosses
`ReconnectConfig.maximumReconnectFailuresBeforeShutdown` the node
exits via `SystemExitUtils` with `SystemExitCode.RECONNECT_FAILURE`.

## Detection (`FallenBehindMonitor`)

[`FallenBehindMonitor`](../../../../consensus-utility/src/main/java/org/hiero/consensus/monitoring/FallenBehindMonitor.java)
collects fallen-behind reports from peers — each peer that observes
the local node lagging on event-window boundaries calls
`FallenBehindMonitor.report(NodeId)`. The monitor recomputes whether
the node is behind on every report and signals waiting threads when
the state flips to behind.

The trigger condition is in `FallenBehindMonitor.checkAndNotify()`:

```java
isBehind = peersSize * fallenBehindThreshold < reportFallenBehind.size()
        || (peersSize > 0 && reportFallenBehind.size() == peersSize);
```

`fallenBehindThreshold` is a proportion (0.0–1.0) read from
`FallenBehindConfig.fallenBehindThreshold`; the second clause covers
the edge case where every peer has reported. See
[`../../tunables.md`](../../tunables.md) for the configured value. The
monitor also surfaces two metrics under the `internal` category:
`hasFallenBehind` and `numReportFallenBehind`.

Detection is only the trigger; before the learner can fetch a new
state the node has to quiesce. `notifySyncProtocolPaused()` /
`awaitGossipPaused()` on `FallenBehindMonitor` are the gossip-pause
handshake used for that preparation: once the monitor flips to
behind, gossip is stopped, in-flight tasks are flushed, and data
structures that depend on the current event window are cleared.
Only after that preparation completes does the learner request a new
state from a teacher.

## Learner / teacher protocol

State transfer is a paired exchange. The node that has fallen behind
acts as **learner**; a healthy peer acts as **teacher**. Both sides
reuse the shared connection that gossip already maintains; the
reconnect protocol is one of three protocols multiplexed on that
connection (see [`gossip.md`](gossip.md) for the protocol-stack view).

- [`ReconnectStateLearner`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateLearner.java)
  drives the learner side. `execute()` performs the actual state sync
  and returns a reserved signed state.

- [`ReconnectStateTeacher`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateTeacher.java)
  drives the teacher side. `execute()` streams the local signed state
  to the requesting peer.

- [`ReconnectStateTeacherThrottle`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateTeacherThrottle.java)
  bounds how often this node accepts a teacher session.
  `initiateReconnect()` is the gate; `reconnectAttemptFinished()`
  releases the slot; `getNumberOfRecentReconnects()` exposes the
  observed rate.

- [`ReconnectStateSyncProtocol`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateSyncProtocol.java)
  is the gossip-side protocol object — it produces a per-peer
  `ReconnectStatePeerProtocol` instance via `createPeerInstance()` and
  tracks the platform status the per-peer decisions depend on
  (`updatePlatformStatus()`). The "sync" in the name refers to state
  synchronisation; the gossip event-exchange algorithm is a separate
  protocol described in [`gossip.md`](gossip.md).

- [`ReconnectStatePeerProtocol`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStatePeerProtocol.java)
  is the per-connection implementation: `shouldInitiate()` decides
  whether to start a reconnect attempt as learner, `shouldAccept()`
  whether to accept one as teacher, and `runProtocol()` runs the
  chosen role on the active connection.

A node will only ever teach one learner at a time;
`ReconnectStateTeacherThrottle` enforces that single-slot bound.

The learner-side `shouldInitiate()` has no gates beyond
`FallenBehindMonitor` reporting the local node behind by this peer.
The teacher-side `shouldAccept()` additionally requires platform
status `ACTIVE`, since only a healthy node should teach.
Software-version mismatches are rejected when connections are first
established, so a non-matching peer never reaches either check; the
roster does not change between upgrades, so no roster-compatibility
check is needed.

The learner does not open a new socket — it reuses the same
gossip-multiplexed connection that the reconnect protocol negotiated
on.

## Post-reconnect resumption

Once a state is validated, the entire set of components that depend
on an event window has to be re-initialised against it — the
hashgraph algorithm, event intake, the shadowgraph, and any other
component carrying round / event-window state. These structures
were already cleared during preparation (see
[Detection](#detection-fallenbehindmonitor)); resumption is the
mirror step that pushes the new state into them.

The orchestration is
[`ReconnectCoordinator.loadReconnectState`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectCoordinator.java):
it overrides the ISS-detector state, installs the signed state as
the latest immutable state, hands it to the signature collector
(which queues it for disk write), overrides the consensus snapshot,
injects the roster history into event intake, updates the event
window across the platform, overrides the running event hash, and
records a PCES discontinuity at the state's round.

Status transitions follow loading in two stages.
`ReconnectController` submits a `ReconnectCompleteAction` once a
valid state has been learned, and the platform status machine moves
the node to `RECONNECT_COMPLETE`. The node only advances from
`RECONNECT_COMPLETE` to `CHECKING` — the status at which it is
permitted to create events — after the state has been written to
disk.

The event window — which carries the ancient round and the other
round thresholds — is what tells event-intake which events to keep
and which to discard; updating it during `loadReconnectState` is how
the discard rule re-anchors. See
[`event-intake.md`](event-intake.md) for the window's full role.

The event creator does not run separate logic for event creation after a reconnect; the
status machine is the gate. After `RECONNECT_COMPLETE` the node
moves to `CHECKING`, where it may create events. It re-enters
`ACTIVE` as soon as one of those events reaches consensus in a
timely manner (a transition through `OBSERVING` would also be
valid). Note that if the Execution layer keeps its transaction
pool across the reconnect, the node may briefly create events
carrying user transactions that have already gone stale.

There is no separate "caught-up" signal: reaching `ACTIVE` *is* the
recovered state — the node is back to creating events that reach
consensus.

## Boundary handoffs

See
[`../interfaces/consensus-execution-boundary.md`](../interfaces/consensus-execution-boundary.md)
for how reconnect crosses the Consensus / Execution boundary.

## Cross-references

**Topics**

- [`gossip.md`](gossip.md)
- [`signed-state-management.md`](signed-state-management.md)
- [`restart-and-pces.md`](restart-and-pces.md)
- [`event-intake.md`](event-intake.md)

**Interfaces**

- [`../interfaces/consensus-execution-boundary.md`](../interfaces/consensus-execution-boundary.md)

**Source doc**

- [`reconnect-refactor-proposal.md`](../../../proposals/reconnect-refactor/reconnect-refactor-proposal.md)

**Other catalogs**

- Tunables — [`../../tunables.md`](../../tunables.md) (pending).
- Invariants — [TBD: INV-NNN once `invariants.md` catalog populates;
  the fallen-behind-threshold proportion and the
  state-ownership-flip-at-validation step are likely candidates].
- Decisions — [TBD: ADR-NNN once `decisions/` catalog populates].
- Scenarios — [TBD: SCN-NNN — reconnect failure modes (failure to
  reconnect within retry cap, reconnect succeeded but next freeze
  broke, teacher-throttle starvation, spurious detection on a flaky
  peer) are likely scenario seeds].

## Future state

> **Future state.** Reconnect is expected to move entirely to the
> Execution layer. Rather than re-initialising in-place components
> against the new state, Execution will likely tear down the whole
> platform and reconstruct it from scratch around the received
> state. The main prose above describes reconnect as it stands
> today.
