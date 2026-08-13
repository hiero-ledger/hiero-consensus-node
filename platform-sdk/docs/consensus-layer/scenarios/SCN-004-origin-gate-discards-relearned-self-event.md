---
type: scenario
id: SCN-004
title: Origin-gated self-event recency discards a self event relearned through gossip after an unclean shutdown — branching
symptoms: [SYM-003]
topics: [event-creator, restart-and-pces]
kind: near-miss
verification: test-reproduced
severity: high
related:
  invariants: []
  decisions: [ADR-004, ADR-008]
  scenarios: [SCN-003]
  tests:
    - consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/RestartTest.java
    - consensus-event-creator-impl/src/test/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreatorTests.java
status: verified
provenance: hiero-consensus-node#26530; reproduced by RestartTest.testHardNetworkRestart
curated_by: Kelly Greco (@poulok)
last_reviewed: TBD
---

# SCN-004 — Origin-gated self-event recency discards a self event relearned through gossip after an unclean shutdown — branching

## Summary

An unclean shutdown can leave the tail of the preconsensus event stream unwritten
while peers already hold those events. The restarted node then replays a self event
that is not its graph-latest one, and a peer hands the missing self event back
through gossip. `TipsetEventCreator.registerEvent` compared the two only when their
`EventOrigin` matched, so the relearned event was discarded and the node built its
next event on the older self-parent — a branch. This is the mirror of SCN-003: there
the comparison adopted a self event that was too old, here it refused one that was
genuinely newer.

## Setup

**Preconditions:**

- The node shuts down without the JVM exiting cleanly, so the PCES shutdown hook
  registered in `consensus-pces-impl/.../writer/DefaultInlinePcesWriter.java` does not
  run and the buffered tail of the stream is lost. In production this is `SIGKILL` or
  loss of host power — the residual window
  [`restart-and-pces.md`](../architecture/topics/restart-and-pces.md) accepts under
  *Durability model*.
- At least one peer holds a self event that the node created and gossiped but did not
  get onto disk.
- The node's latest self event and the self event it did persist are both non-ancient
  after the restart.
- The event creator ranking self events only within a matching `EventOrigin` pair —
  `STORAGE`/`STORAGE` or `GOSSIP`/`GOSSIP` — with no case for a `STORAGE`-origin held
  event and a `GOSSIP`-origin incoming one.

**Trigger:** a peer returning the node's own unpersisted self event through gossip
after PCES replay has already established an older self event as the latest one.

## Sequence

Hashes and sequence numbers below are from the reproducing run on node 4.

1. The node creates `b0933dd16dff`, then `3868b4cb23bb` on top of it. Both are written
   to PCES before reaching gossip, and both reach peers. (observed)
2. The node is killed without a clean JVM exit. `3868b4cb23bb` is absent from PCES;
   `b0933dd16dff` is present. (observed)
3. The node restarts and replays PCES. The last self event replayed is
   `b0933dd16dff`, origin `STORAGE`, sequence number 1002; it becomes `lastSelfEvent`.
   (observed)
4. During `OBSERVING` a peer returns `3868b4cb23bb`. The deduplicator
   (`consensus-event-intake-impl/.../deduplication/StandardEventDeduplicator.java`) does
   not hold it, because it was never replayed, so it passes intake, is stamped sequence
   number 1009 by the orphan buffer, and reaches `registerEvent` with origin `GOSSIP`.
   (observed)
5. `registerEvent` has no case for a held `STORAGE` event and an incoming `GOSSIP`
   one, so `lastSelfEvent` still points at `b0933dd16dff`. (observed)
6. `OBSERVING` ends and the node creates `6dc8da98c20a` on `b0933dd16dff` — a sibling
   of `3868b4cb23bb`, not a descendant. (observed)
7. `DefaultBranchDetector.checkForBranches` sees a second event by this creator whose
   self-parent is not the previously observed one and reports it; `DefaultBranchReporter`
   logs at `ERROR`. (observed)

## Observable signature

`Node 4 is branching` at `ERROR` from
`consensus-event-intake-impl/.../branching/DefaultBranchReporter.java`, within a few
seconds of the node reaching `CHECKING` after a restart (SYM-003). In the otter suite
`RestartTest.testHardNetworkRestart` fails its no-error-logs assertion.

The distinguishing tell against SCN-003 is in the node's own log: PCES replay reports
a self event as its last, and a self event by this node arrives afterwards through
gossip. The branching event's self-parent is the replayed event rather than the
gossiped one.

## Contributing factors

- **A durability guarantee narrower than its consumers assumed.** Every gossiped
  event is on disk only after a *graceful* JVM exit, but the event creator's origin
  gate was written as though replay always restores the graph-latest self event.
- **A shutdown path that did not reach the writer.** `Platform.destroy()` stopped the
  wiring model without syncing and closing the current PCES file, so the only path to
  a clean close was the JVM-exit hook.
- **An origin pairing with no case.** The gate enumerated the pairings that a clean
  restart and a disk wipe produce, and an unclean shutdown produces neither.
- **A simulation that hid which arm was at fault.** The turtle network preserved the
  creating node's `EventOrigin` on delivery, so the returned self event arrived as
  `RUNTIME` rather than `GOSSIP` — a pairing the gate also had no case for, reached by
  a route that cannot occur in production.

## Mitigation

`TipsetEventCreator.registerEvent` now adopts a `GOSSIP`-origin self event over a
`STORAGE`-origin held event. ADR-008 carries the argument for why that is safe without
comparing sequence numbers across the two. The adoption is gated on the event creator
not having prepared for a reconnect, since `ReconnectCoordinator.clear()`
(`consensus-reconnect-impl/.../ReconnectCoordinator.java`) clears the deduplicator and
so removes the property the argument rests on.

`PcesModule.destroy()` is now called from `SwirldsPlatform.destroy()`, so a node
shutdown that reaches that path syncs and closes the current PCES file without relying
on the JVM-exit hook. That removes the trigger for any shutdown the platform performs
itself; `SIGKILL` and power loss remain outside it.

`OBSERVING` is what gives the relearn time to happen at all — see ADR-004 for why the
status is retained.

What it does not cover:

- PCES losing events from the middle of the stream rather than its tail. Replay then
  delivers something other than a prefix of the node's self chain, and a relearned
  self-*ancestor* becomes indistinguishable from a relearned descendant. Disk-loss
  recovery is best-effort by decision (ADR-004).
- A reconnect inside the window between replay and the node's first created event.
  The gate closes, so the node keeps the stale self-parent rather than adopting a
  possibly-older one — the hazard is traded, not removed.

## Verification

`test-reproduced`. `RestartTest.testHardNetworkRestart` drives a full-network restart
and fails on the unmodified origin gate, with the `ERROR` branch log above.
`TipsetEventCreatorTests.gossipedSelfEventDisplacesReplayedSelfEventWhenPcesWasIncomplete`
pins the adoption at unit level, and
`gossipedSelfEventDoesNotDisplaceReplayedSelfEventAfterReconnect` pins the reconnect
gate; each fails when the code path it covers is disabled.

The otter reproduction reached the lost-tail precondition by a route specific to the
turtle environment: a node kill there does not exit the JVM, so the hook could not run.
Two turtle fidelity gaps found alongside it are fixed — the simulated network now
stamps `GOSSIP` on delivery, matching what
`consensus-gossip-impl/.../shadowgraph/RpcPeerHandler.java` does with a real peer, and
the shutdown now reaches PCES. With both fixed the test no longer reproduces the
scenario, so `RestartTest` guards the production trigger only through the
`Platform.destroy()` path; the unit tests are what pin the gate itself.

## Open questions

- Whether the reconnect-inside-the-`STORAGE`-window case is worth closing properly.
  An epoch counter stamped by the orphan buffer and compared alongside the sequence
  number would remove the need for `EventOrigin` as an epoch proxy in every arm of the
  gate, and would answer this and the corresponding ADR-008 residual together.

## Notes

- 2026-08-12 — created from the #26530 investigation. Every step is `(observed)`, taken
  from instrumented `TipsetEventCreator` and `DefaultBranchDetector` output on node 4 of
  a failing `RestartTest.testHardNetworkRestart` run — Kelly Greco (@poulok).
