---
id: pass1-03-coordinated-network-upgrade
cluster: pass1
title: A coordinated network upgrade through freeze
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/freeze-and-upgrade.md
  - architecture/topics/signed-state-management.md
  - architecture/topics/restart-and-pces.md
  - architecture/topics/event-creator.md
  - architecture/topics/gossip.md
kb_concepts:
  - concepts/birth-round.md
  - concepts/event-lifecycle.md
kb_glossary_terms:
  - Signed state
  - Consensus round
  - Birth round
  - Roster
  - Platform status
  - Event window
  - Ancient threshold
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the consensus-layer components a coordinated upgrade involves, in the order they react, and identify which side originates the freeze signal.
  - Describe at role level how `freezeTime` becomes a network-wide cutoff at a specific consensus round rather than a per-node clock-driven stop.
  - Distinguish "event creation halts" from "gossip halts" during the freeze, and explain at role level why one continues and the other does not.
  - Identify the freeze state as the handoff artifact between the running process and the post-upgrade restart, and name the metadata flag that makes that handoff legible on the next boot.
  - Name the `FREEZING` → `FREEZE_COMPLETE` transition and the action that drives it.
threshold_concepts: []
estimated_session_minutes: 35
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# A coordinated network upgrade through freeze

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in. If you have done `pass1-01-transaction-to-consensus`, the component vocabulary it planted (event creator, gossip, hashgraph, PCES, signed-state management) carries straight into this scenario; if not, the components are re-introduced briefly below.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

Six consensus-layer surfaces participate in this scenario. Each gets a one-line role here; depth lives in the Pass 2 lessons linked under Forward pointers.

- **Execution boundary** — the freeze transaction originates on the Execution side; it travels through normal consensus and writes a `freezeTime` field into the platform state. The consensus layer never authors the freeze; it reacts to a value written by Execution. Detail in [`architecture/topics/freeze-and-upgrade.md`](../../architecture/topics/freeze-and-upgrade.md).
- **Hashgraph** ([`architecture/topics/hashgraph.md`](../../architecture/topics/hashgraph.md)) — detects the first consensus round whose timestamp falls in the freeze period (the *freeze round*), rewrites that round's `EventWindow` to make pre- and post-upgrade events distinguishable, discards any later rounds in the same batch, and then ignores all further events.
- **Event creator** ([`architecture/topics/event-creator.md`](../../architecture/topics/event-creator.md)) — its `PlatformStatusRule` blocks self-event creation once platform status enters `FREEZING`, with a narrow carve-out for events that carry signature transactions on the freeze state. No new application events are produced after the freeze round.
- **Gossip** ([`architecture/topics/gossip.md`](../../architecture/topics/gossip.md)) — keeps running across `FREEZING` and `FREEZE_COMPLETE`. The permit set that gates the RPC sync protocol explicitly admits both statuses, and there is no separate freeze branch in the gossip code. The continued chatter is what gets signatures on the freeze state out to peers that are slightly behind.
- **Signed-state management** ([`architecture/topics/signed-state-management.md`](../../architecture/topics/signed-state-management.md)) — when the freeze round produces a signed state, the saved-state controller short-circuits its periodic-snapshot logic and marks the state as a `FREEZE_STATE` unconditionally. The snapshot manager writes it synchronously, and the on-disk metadata records `freezeState=true` so the next boot can recognize the handoff.
- **Status state machine** — drives `FREEZING` → `FREEZE_COMPLETE` once the freeze state has been written to disk. The transition is what tells the rest of the platform "the handoff artifact exists". Detail in [`architecture/topics/freeze-and-upgrade.md`](../../architecture/topics/freeze-and-upgrade.md).

PCES (`architecture/topics/restart-and-pces.md`) also appears in the trace on the *next-boot* side, when the restarted process replays PCES on top of the loaded freeze state before gossip starts. Its role is the same as in the steady-state restart sequence; only the input (a freeze-marked state on disk) differs.

## Scenario setup

A small steady-state network of N nodes. The operator has staged a software upgrade and now wants to retire the current binary and bring up a new one at a synchronized point — every node must stop applying new rounds at the *same* consensus point, write a marked signed state, and continue gossiping only long enough to distribute signatures on that state. The operator submits a freeze transaction (typically `FREEZE_UPGRADE`) via the Execution side; consensus is healthy, gossip is running, all permission gates on event creation are open.

From the consensus layer's vantage, the freeze transaction enters as just another transaction. It rides through gossip, intake, the hashgraph, and consensus order; on its way through the transaction handler it causes `freezeTime` to be written into the platform state. The interesting behaviour starts only when a *subsequent* consensus round's timestamp lands at or after that `freezeTime`.

The scenario follows the network from the moment the freeze transaction first writes `freezeTime` through to a clean restart on the new binary with the freeze state loaded.

## Productive impasse

Before walking through the trace, predict, thinking-aloud style:

- A freeze is a network-wide coordinated stop. Which clock or condition makes "stop now" land at the *same point* on every node? If your gut says "everyone watches a wall-clock timestamp and stops when it elapses", what would go wrong with that?
- During the freeze period, which of the components above keep running and which stop? In particular: does gossip pause, or does it keep going? Why might the answer be the opposite of what you'd guess for "the network is shutting down"?
- The freeze state is written to disk as a marked snapshot. What does the metadata flag on that state buy that the consensus layer could not get from just "save the latest periodic snapshot"?

There is no quiz here; the goal is to surface the model you are bringing in. If your prior comes from "graceful shutdown" or "rolling restart" patterns elsewhere, say so — the consolidation at the end will flag where this differs.

## Trace

The trace runs in seven role-level stages, four before the JVM exits and three after the operator brings the new binary up. Code anchors live in the Pass 2 lessons; this lesson stays at role level.

### Stop 1 — Execution writes `freezeTime` into the platform state

The operator submits a freeze transaction via the Execution side. It travels as a normal transaction through gossip, intake, the hashgraph, consensus, and the transaction handler. On the way through, the Execution-side freeze handler writes a `freezeTime` timestamp into the platform state, and the platform-state freeze fields are populated alongside it. From this moment on, every component that consults the platform state can ask the canonical predicate "are we in a freeze period?" — defined in terms of `freezeTime`, the current consensus time, and a separate `lastFrozenTime` field that records when the last freeze actually fired.

Two things to notice at role level:

- **The freeze trigger is data, not control.** No component is told "freeze now"; a field is written to state, and every component that gates on the freeze predicate reads it independently. This is what makes the cutoff land at the same consensus point on every node — they all evaluate the same predicate against the same state.
- **The transaction itself is unremarkable.** It rides the steady-state pipeline that `pass1-01` walked, and it reaches consensus the same way any other transaction does.

### Stop 2 — Consensus reaches the freeze round; the hashgraph rewrites it and stops

The first consensus round whose timestamp falls inside the freeze period is the *freeze round*. Two parallel actions handle it.

The transaction handler detects, on its way through the round, that the round's timestamp has entered the freeze period. It submits a `FreezePeriodEnteredAction(round)` into the status state machine — this is what flips platform status to `FREEZING` — and sets an internal `freezeRoundReceived` flag so later rounds in the same batch are ignored.

In parallel, a freeze round controller in the hashgraph layer takes the first freeze round and modifies its `EventWindow`: it rewrites the round's birth round to equal the latest consensus round, so that pre- and post-upgrade events can be distinguished on the next boot (see [`concepts/birth-round.md`](../../concepts/birth-round.md) for what birth round is and what it gates). The controller then discards any later rounds in the same batch and flips an internal `isFrozen` flag.

Once `isFrozen` is set, the consensus engine ignores all further events on its input. No additional rounds will be produced from this process; the freeze round is the last one this binary will ever decide.

### Stop 3 — Event creation halts; gossip keeps going

The status transition to `FREEZING` propagates to the components that consult platform status.

- **The event creator's permission rule** sees `FREEZING` and stops emitting self-events. There is a narrow carve-out: while there are buffered state-signature transactions waiting to be carried, the creator may still produce events for the purpose of getting those signatures into consensus. After the signing work is done, creation is fully stopped because neither `ACTIVE` nor `CHECKING` is reached again.
- **Gossip does *not* stop.** The permit set that gates the RPC sync protocol explicitly admits `FREEZING` (and, in a moment, `FREEZE_COMPLETE`). There is no separate freeze branch in the gossip code. The reason is downstream: peers that are slightly behind have not yet received the freeze round, or have not yet received quorum signatures on the freeze state. Continued gossip is how those peers catch up and how their signatures reach the rest of the network.

The asymmetry is deliberate. "Stop creating new application events" and "stop talking to peers" are two different decisions, and the platform makes them independently.

### Stop 4 — The freeze state is saved synchronously

The freeze round produces a signed state in the usual way: the transaction handler applies the round and emits a fresh `SignedState`. From there the path diverges from a periodic snapshot.

The saved-state controller's first decision branch tests "is this a freeze state?" — when yes, it short-circuits the periodic-snapshot logic and marks the state for saving unconditionally, with a `FREEZE_STATE` reason. The snapshot manager writes the round directory synchronously: under a temporary path first, then atomically renamed into place (so readers never observe a half-built directory), with the PCES files needed to replay from this round hard-linked into the directory rather than byte-copied (see [`architecture/topics/signed-state-management.md`](../../architecture/topics/signed-state-management.md) for the on-disk layout). The persisted metadata carries a `freezeState=true` flag.

That metadata flag is the load-bearing handoff. On the next boot, the start-up code reads it from disk and knows the loaded state is the result of a freeze, not an opportunistic periodic snapshot. The two are otherwise the same shape; the flag is what makes them legibly different to the upgrade path.

### Stop 5 — `FREEZING` → `FREEZE_COMPLETE`; signatures continue to circulate

When the freeze-state write completes, a `StateWrittenToDiskAction(isFreezeState=true)` is submitted to the status state machine. The freezing-status logic consumes it and transitions to `FREEZE_COMPLETE`.

Two things hold in `FREEZE_COMPLETE`:

- Gossip remains permitted. The permit set covers `FREEZE_COMPLETE` for the same reason it covered `FREEZING`: signatures on the freeze state must reach laggards, and laggards' signatures must reach the rest of the network. The freeze state is "official" only once enough signing weight has accumulated, and the route by which that happens is normal gossip carrying state-signature transactions.
- Event creation is finished. `FREEZE_COMPLETE` is not in the set of statuses the permission rule admits for new self-events, and the platform will not return to `ACTIVE` from here without a restart.

The node is now in the steady-state-after-freeze posture: no more events from this binary, but the gossip plane is still active, distributing the freeze-state signature set across the network.

### Stop 6 — Operator restarts on the new binary

At some point — operator-driven, not platform-driven — the JVM exits and a new binary starts in its place. The exact trigger for the JVM exit is not anchored cleanly in the consensus-layer code reading; it is plausibly operator orchestration, an Execution-module hook, or a watchdog higher up the stack (see Open questions). What matters at orientation altitude is that the running process eventually goes away and a fresh JVM comes up against the same on-disk state directory the freeze run wrote into.

From the consensus layer's vantage, this stop is a black box: the binary that decided the freeze round is replaced; the next stop picks up against whatever the new binary brings.

### Stop 7 — Boot loads the freeze state; PCES replay; gossip resumes

The new binary's start-up code locates the latest saved state on disk and deserializes it. The metadata flag from Stop 4 is preserved through serialization, so the platform knows the loaded state was the result of a freeze.

From here the boot follows the normal restart sequence described in [`architecture/topics/restart-and-pces.md`](../../architecture/topics/restart-and-pces.md): replay bounds (`pcesReplayLowerBound` from the loaded state's ancient threshold, `startingRound` from its last consensus round) are derived during construction; core platform components come up; PCES replay runs synchronously, draining any PCES events covered by the replay window into the normal intake pipeline; gossip starts only after replay completes. The freeze flag on the loaded state is what the upgrade path branches on — exactly which decisions it gates (whether PCES replay is skipped on a freeze-state boot, whether event creation is held under a transient status, whether reconnect is available pre-replay) is not anchored cleanly in the current reading (see Open questions).

Once gossip starts, the new binary's window catches up with the network, and the platform re-enters its normal steady-state posture. The `lastFrozenTime` field on the platform state eventually catches up with `freezeTime`, which is what prevents the same `freezeTime` value from re-arming the freeze predicate on a subsequent round.

## Consolidation

The trace has three pivots worth naming explicitly, in case your prediction missed them:

- **The cutoff is consensus-clock-driven, not wall-clock-driven.** Every node freezes at the *same consensus round* — the first one whose consensus timestamp lands in the freeze period — because every node evaluates the same `freezeTime` value against the same consensus time on the same round. If your prior was "every node watches its own wall clock and stops when the freeze time elapses", the gap is that wall clocks disagree and consensus-clock timestamps don't.
- **Gossip is not part of the stop.** Event *creation* halts (with a narrow signing carve-out), and the hashgraph stops admitting events, but the gossip plane keeps running across both `FREEZING` and `FREEZE_COMPLETE`. The reason is downstream: a freeze state isn't "official" until peers' signatures reach quorum, and the route by which signatures travel is gossip. If your prior was "the network is shutting down, so the network plane stops", the codebase's choice goes the other way for a load-bearing reason.
- **The freeze state is a handoff artifact, not a graceful shutdown side-effect.** The marked `FREEZE_STATE` snapshot, with its `freezeState=true` metadata flag, is the only durable bridge between the binary that just decided the freeze round and the binary that will boot next. A periodic snapshot taken at the same moment would have most of the same content but would not be legibly identifiable as the freeze handoff on the next boot. The marker, the synchronous write, and the metadata flag together are what make the upgrade resumable.

The transaction-level mental model from `pass1-01` still applies for the freeze transaction's path through consensus; what's new here is the second-order effect: that one specific field-write on state can convert a subsequent consensus round into a network-wide cutoff.

## Close-out

You should now be able to:

- Sketch the trace from the freeze transaction through to the restarted process on the new binary, naming each component as a box and the transitions between them.
- Distinguish the freeze trigger (data: `freezeTime` written to state by Execution) from the freeze reaction (every component gates on the same predicate).
- Point to the freeze round as the cutoff, and say at role level why every node lands on the same round.
- Explain at role level why event creation halts at `FREEZING` but gossip continues through both `FREEZING` and `FREEZE_COMPLETE`.
- Name the freeze state's metadata flag and what it buys the next-boot path.

The Pass 2 lessons listed under Forward pointers below take each component apart at mechanism depth. The Pass 3 deep version of this scenario (`pass3-03-coordinated-network-upgrade-deep`) returns to the trace with code anchors, invariants, deltas, and the cross-cluster stitch points the Pass 2 lessons could not teach in isolation. Two Pass 3 edge scenarios — `pass3-edge-01-reconnect-during-freeze` and `pass3-edge-05-squelching-during-freeze` — perturb this same trace by overlapping it with a reconnect or with a squelch.

## Forward pointers

Each component touched in this trace has a dedicated Pass 2 cluster. After you have a steady sketch from this lesson, walk through them in roughly this order:

- **Freeze and upgrade** — cluster D: `d-01-freeze-trigger-and-platform-state`, `d-02-freeze-round-and-event-cutoff`, `d-03-freeze-state-save-and-status-transitions`, `d-04-upgrade-startup-path`, `d-syn-freeze-upgrade-synthesis`. The four mechanism lessons cover the trigger and platform-state fields, the round-level cutoff and event-creation gating, the synchronous save and `FREEZING` → `FREEZE_COMPLETE` transition, and the upgrade startup path; the synthesis stitches them.
- **Signed-state management** — cluster C: `c-01-signed-state-runtime-types`, `c-02-signed-state-lifecycle-and-on-disk-layout`. The reservation discipline and the six-phase lifecycle underlie the freeze-state save; the on-disk layout is what `d-03` reads from.
- **Restart and PCES** — cluster C: `c-03-inline-pces-write-path`, `c-04-restart-and-pces-replay`. The replay path the new binary runs in Stop 7 is the same path a non-freeze restart runs.
- **Event creator status gating** — cluster A.4: `a4-04-creation-rules-and-health-gates`. The permission rule that admits or refuses creation per platform status — including the `FREEZING` carve-out — is covered there.
- **Gossip status gating** — cluster A.3: `a3-01-protocol-stack-and-rpc`. The permit set that admits `FREEZING` and `FREEZE_COMPLETE` lives in the protocol-stack mechanism.
- **Recovery synthesis** — `c-syn-recovery-synthesis` integrates signed-state, restart/PCES, and reconnect once each is established.
- **Deep return** — `pass3-03-coordinated-network-upgrade-deep` revisits this scenario at mechanism level.
- **Edges** — `pass3-edge-01-reconnect-during-freeze`, `pass3-edge-04-pces-replay-after-upgrade`, `pass3-edge-05-squelching-during-freeze`.

Cluster 0 (Wiring Framework Foundation) underlies the soldering that carries `freezeTime` reads, status transitions, and the freeze-state save signal between modules. If "wire", "scheduler", or "action" felt under-defined here, take cluster 0 before cluster D.

## Open questions

The freeze topic flags several mechanism-level questions whose answers shape the trace above; at orientation altitude the trace is correct as written, but the boundaries at Stop 6 and Stop 7 are partially derived from current code and partially [TBD] in the source KB. The Pass 2 cluster D lessons and the Pass 3 deep return should resolve them; the reviewer is welcome to fold the answers back here.

- [TBD: what triggers JVM exit between `FREEZE_COMPLETE` and the next-boot binary?] — Stop 6 is described as operator-driven because the consensus-layer / platform-core anchoring does not surface an in-process shutdown path; the freeze-and-upgrade KB topic carries the same gap.
- [TBD: how does the upgrade boot branch on `SavedStateMetadata#freezeState`?] — Stop 7 describes PCES replay following the normal restart sequence; whether replay is skipped on a freeze-state boot, whether event creation is held under a transient status, and whether reconnect is available pre-replay are not anchored cleanly in current reading.
- [TBD: precise ordering and contract around `lastFrozenTime`.] — The trace says `lastFrozenTime` "eventually catches up" with `freezeTime`; the freeze-and-upgrade topic flags both the write timing relative to the freeze-state save and the freeze-rearming contract as open.
