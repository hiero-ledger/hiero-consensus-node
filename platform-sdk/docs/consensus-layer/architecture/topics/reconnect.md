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

> **Delta vs. reconnect-refactor proposal:** the proposal absorbs
> `ReconnectController`, `ReconnectStateLoader`,
> `ReconnectPlatformHelper(Impl)`, and `ReconnectLearnerThrottle` into a
> single `PlatformReconnecter` and moves the entry point out of
> `swirlds-platform-core`. None of this is implemented;
> `ReconnectController` remains the live lifecycle driver. See
> [Future state](#future-state).

1. **Detection.** `ReconnectController` blocks on
   `FallenBehindMonitor.awaitFallenBehind()`. The monitor flips its
   `isBehind` flag once enough peers have reported the local node as
   behind. See [Detection](#detection-fallenbehindmonitor).
2. **Peer selection and handshake.** State retrieval is asynchronous:
   `ReconnectController` waits on a
   `BlockingResourceProvider<ReservedSignedStateResult>` for the next
   available state, while
   `ReconnectStatePeerProtocol.shouldInitiate()` and `shouldAccept()`
   decide which peers participate.
   [TBD: question for engineer — is there an explicit peer ranking
   step inside `ReconnectController`, or is selection purely
   first-come via the blocking resource provider? If first-come, what
   prevents a single slow teacher from monopolising the slot?]
3. **State transfer.** The receiving side runs
   `ReconnectStateLearner.execute()`; the sending side runs
   `ReconnectStateTeacher.execute()`. See
   [Learner / teacher protocol](#learner--teacher-protocol).
4. **Validation and load.** A `SignedStateValidator` checks the
   received state, then `PlatformCoordinator`
   (`com.swirlds.platform.wiring.PlatformCoordinator`) pauses the
   wiring, swaps in the new state via `StateLifecycleManager`, and
   clears in-flight pipelines.
   [TBD: question for engineer — beyond signature quorum, what does
   the default `SignedStateValidator` check (roster compatibility,
   software version, birth-round monotonicity)?]
5. **Resumption.** `ReconnectController` submits a
   `ReconnectCompleteAction` to the platform status machine, the
   wiring resumes, and the loop returns to phase 1. See
   [Post-reconnect resumption](#post-reconnect-resumption).

If an attempt fails, the controller backs off and re-enters detection;
after a configured maximum number of failures the node exits via
`SystemExitUtils`.
[TBD: question for engineer — what is the field name on
`ReconnectConfig` for the failure cap, and what `SystemExitCode` is
used when the cap is exceeded?]

## Detection (`FallenBehindMonitor`)

[`FallenBehindMonitor`](../../../../consensus-utility/src/main/java/org/hiero/consensus/monitoring/FallenBehindMonitor.java)
collects fallen-behind reports from peers — each peer that observes
the local node lagging on event-window boundaries calls
`FallenBehindMonitor.report(NodeId)`. The monitor recomputes whether
the node is behind on every report and signals waiting threads when
the state flips to behind.

> **Delta vs. reconnect-refactor proposal:** the proposal renames
> `FallenBehindManager` / `FallenBehindManagerImpl` to
> `FallenBehindMonitor` and folds in `SyncManagerImpl`. The rename is
> implemented; the monitor lives in `consensus-utility`, not
> `consensus-reconnect-impl`.

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

[TBD: question for engineer — `FallenBehindMonitor` exposes
`notifySyncProtocolPaused()` / `awaitGossipPaused()` as a separate
condition. Is the gossip-pause handshake part of the detection
contract (the monitor blocks reconnect until gossip is quiescent), or
is this a distinct synchronisation point owned elsewhere?]

[TBD: question for engineer — when the threshold trips spuriously (a
flaky neighbor briefly under-reports during normal operation), what
is the operator-visible symptom? Spurious reconnect attempts,
throttled retries, or silent recovery?]

## Learner / teacher protocol

State transfer is a paired exchange. The node that has fallen behind
acts as **learner**; a healthy peer acts as **teacher**. Both sides
reuse the shared connection that gossip already maintains; the
reconnect protocol is one of three protocols multiplexed on that
connection (see [`gossip.md`](gossip.md) for the protocol-stack view).

- [`ReconnectStateLearner`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateLearner.java)
  drives the learner side. `execute()` performs the actual state sync
  and returns a reserved signed state.

  > **Delta vs. reconnect-refactor proposal:** the proposal calls this
  > `ReconnectLearner` and pairs it with a `ReconnectLearnerFactory`;
  > both have been collapsed into the single class above.

- [`ReconnectStateTeacher`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateTeacher.java)
  drives the teacher side. `execute()` streams the local signed state
  to the requesting peer.

  > **Delta vs. reconnect-refactor proposal:** the proposal calls this
  > `ReconnectTeacher`.

- [`ReconnectStateTeacherThrottle`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateTeacherThrottle.java)
  bounds how often this node accepts a teacher session.
  `initiateReconnect()` is the gate; `reconnectAttemptFinished()`
  releases the slot; `getNumberOfRecentReconnects()` exposes the
  observed rate.

  > **Delta vs. reconnect-refactor proposal:** the proposal lists a
  > `ReconnectLearnerThrottle` (learner-side retry / shutdown limits)
  > separate from the teacher throttle. Current code only contains a
  > teacher-side throttle; learner-side retry and shutdown logic lives
  > inside `ReconnectController` itself.

- [`ReconnectStateSyncProtocol`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateSyncProtocol.java)
  is the gossip-side protocol object — it produces a per-peer
  `ReconnectStatePeerProtocol` instance via `createPeerInstance()` and
  tracks the platform status the per-peer decisions depend on
  (`updatePlatformStatus()`).

  > **Delta vs. reconnect-refactor proposal:** the proposal calls this
  > `SyncProtocol` in current naming and renames it to
  > `StateSyncProtocol` in the proposed design. Current code uses
  > `ReconnectStateSyncProtocol`.

- [`ReconnectStatePeerProtocol`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStatePeerProtocol.java)
  is the per-connection implementation: `shouldInitiate()` decides
  whether to start a reconnect attempt as learner, `shouldAccept()`
  whether to accept one as teacher, and `runProtocol()` runs the
  chosen role on the active connection.

  > **Delta vs. reconnect-refactor proposal:** the proposal calls this
  > `ReconnectPeerProtocol`.

[TBD: question for engineer — what is the current cap on concurrent
teacher sessions in `ReconnectStateTeacherThrottle` (and is it
configurable via `ReconnectConfig`)? Has there been a production
scenario where this cap was the bottleneck?]

[TBD: question for engineer — `ReconnectStatePeerProtocol.shouldInitiate`
relies on `FallenBehindMonitor` plus platform status. What other
gates are checked (freeze in progress, software-version mismatch,
roster compatibility) before a reconnect can begin?]

[TBD: question for engineer — does the learner reuse the gossip
socket directly via `ReconnectStatePeerProtocol.runProtocol()`, or
does it acquire a connection through the
`BlockingResourceProvider<ReservedSignedStateResult>` referenced from
`ReconnectController`? The two paths are not obviously equivalent.]

## Post-reconnect resumption

Once a state is validated and loaded, the platform's wiring must be
re-anchored to the new round before gossip and event creation can
resume. `PlatformCoordinator` is the orchestration object: it pauses
gossip, flushes wiring pipelines, swaps in the new state via
`StateLifecycleManager`, and resumes. `ReconnectController` then
submits a `ReconnectCompleteAction` to the platform status machine,
which transitions the node out of the BEHIND status.

[TBD: question for engineer — where does event-intake re-anchor after
a reconnect, and what invariant does it re-establish? Specifically,
which event-window thresholds are reset, and how does event-intake
know to discard events older than the new state's birth-round? Cross-
link to [`event-intake.md`](event-intake.md) once anchored.]

[TBD: question for engineer — does the event creator pause for a
quiescent period after `ReconnectCompleteAction` to avoid creating
events with stale parents, or does it rely entirely on event-intake
filtering downstream?]

[TBD: question for engineer — is there a post-reconnect verification
step that confirms the node has reached parity with peers (a
"caught-up" signal), or is exit from BEHIND status considered
sufficient?]

## Boundary handoffs

Reconnect crosses the Consensus / Execution boundary in two places.
The orchestration entry point — `ReconnectModule` — lives in
`swirlds-platform-core`, the wiring root where Execution and Consensus
meet. The implementation (`ReconnectController`, the learner / teacher
classes, the throttle, the protocols) lives in `consensus-reconnect-impl`
on the Consensus side. State ownership transitions at validation:
until then the local signed state belongs to Execution; the loaded
replacement is installed via `StateLifecycleManager` before Consensus
modules re-anchor. See
[`../interfaces/consensus-execution-boundary.md`](../interfaces/consensus-execution-boundary.md)
for the boundary's full method-by-method walk.

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

> **Future state.** The items below are described in the
> reconnect-refactor and consensus-layer proposals but are not yet
> present in current code. They are listed here so a reader of the
> codebase is not surprised by their absence; main prose above
> describes reconnect as it stands.
>
> - **`PlatformReconnecter`.** The reconnect-refactor proposal
>   centralises the reconnect lifecycle in a single
>   `PlatformReconnecter` class on the platform side, absorbing
>   `ReconnectController`, `ReconnectStateLoader`,
>   `ReconnectPlatformHelper(Impl)`, and `ReconnectLearnerThrottle`.
>   No such class exists; the lifecycle still runs in
>   `ReconnectController`, and the helper classes named alongside it
>   in the proposal are not present in current code under those
>   names.
> - **`StateSyncProtocol`.** The proposal renames the gossip-side
>   reconnect protocol to `StateSyncProtocol` to reflect that its
>   responsibility narrows to fetching a state on request. Current
>   code uses `ReconnectStateSyncProtocol`.
> - **`ReservedSignedStatePromise`.** The proposal introduces a
>   purpose-built blocking resource to hand a reserved signed state
>   from the protocol to the reconnect orchestrator. The capability
>   exists today via the generic
>   `BlockingResourceProvider<ReservedSignedStateResult>` used by
>   `ReconnectController`, but no class named
>   `ReservedSignedStatePromise` exists.
>
> Separately, the [consensus-layer
> proposal](../../../proposals/consensus-layer/Consensus-Layer.md)
> places reconnect entirely on the Execution side ("Reconnect
>
>> therefore is the responsibility of Execution"). Current code splits
>> responsibilities across `consensus-reconnect-impl` (the Consensus-
>> side implementation) and `swirlds-platform-core` (the orchestration
>> entry point and the Execution handoff).
