---
id: pass1-03-coordinated-upgrade
cluster: pass1
title: "Coordinated network upgrade — orientation walkthrough"
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/freeze-and-upgrade.md
  - architecture/topics/signed-state-management.md
  - architecture/topics/restart-and-pces.md
  - architecture/topics/event-creator.md
  - architecture/topics/gossip.md
  - architecture/topics/event-intake.md
kb_concepts:
  - concepts/event-lifecycle.md
kb_glossary_terms: []
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the components a coordinated network upgrade passes through, in order — from the freeze trigger through the freeze round, the freeze-state save, the JVM restart, PCES replay, and gossip resumption on the new version.
  - State that coordination is consensus-mediated — every node learns of the upcoming freeze by reaching consensus on a freeze transaction that writes `freezeTime` into platform state, not by an out-of-band synchronous signal.
  - Describe at role level the strict ordering at restart — load the signed state, replay PCES through the normal intake pipeline, then start gossip — and the reason gossip cannot start earlier.
threshold_concepts: []
estimated_session_minutes: 30
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# Coordinated network upgrade — orientation walkthrough

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

Seven roles carry the network from steady-state operation, through a clean stop at an agreed point in consensus history, across a JVM restart on the new software version, and back to steady state. Each is named with one-sentence semantics so the trace can integrate them without further explanation.

- **Freeze trigger (Execution side)** — a freeze transaction dispatched on the Execution side writes a `freezeTime` timestamp into the platform state once it reaches consensus; the consensus side reads it back through `FreezePeriodChecker` ([`freeze-and-upgrade.md` — Trigger](../../architecture/topics/freeze-and-upgrade.md#trigger)).
- **Freeze-round controller** — the consensus-side component that recognises the first consensus round whose timestamp falls in the freeze period, marks it as the freeze round, rewrites its `EventWindow` so the upgrade boundary is clean, and then stops the hashgraph engine from producing further rounds ([`freeze-and-upgrade.md` — Freeze procedure](../../architecture/topics/freeze-and-upgrade.md#freeze-procedure)).
- **Event creator under freeze** — the event creator's `PlatformStatusRule` blocks self-event creation in `FREEZING` except for events that carry state-signature transactions, so the node still emits its signature on the freeze state but produces no further application events ([`freeze-and-upgrade.md` — Event creation](../../architecture/topics/freeze-and-upgrade.md#event-creation); [`event-creator.md` — Backpressure interaction](../../architecture/topics/event-creator.md#backpressure-interaction)).
- **Gossip during freeze** — the per-peer permit set admits both `FREEZING` and `FREEZE_COMPLETE`, so sync continues across the freeze and the freeze-state signatures propagate to peers that are still catching up ([`freeze-and-upgrade.md` — Gossip](../../architecture/topics/freeze-and-upgrade.md#gossip); [`gossip.md` — Protocol stack](../../architecture/topics/gossip.md#protocol-stack)).
- **Signed state manager** — produces the freeze-round signed state, recognises `isFreezeState()` and short-circuits the periodic-save logic, writes the state synchronously to disk with `SavedStateMetadata.freezeState = true`, and bundles the PCES sub-tree the round needs into the on-disk snapshot ([`signed-state-management.md` — Lifecycle](../../architecture/topics/signed-state-management.md#lifecycle); [`freeze-and-upgrade.md` — State save](../../architecture/topics/freeze-and-upgrade.md#state-save)).
- **Preconsensus event stream (PCES)** — the on-disk record of self-events written before they are gossiped; at restart it supplies stored events back into intake so the node can rebuild preconsensus state before re-entering the network ([`restart-and-pces.md` — Inline PCES (write path)](../../architecture/topics/restart-and-pces.md#inline-pces-write-path)).
- **Restart loader and PCES replayer** — on the new version's first boot, `SwirldsPlatform` loads the latest signed state, derives the replay window from it, brings up core components, runs PCES replay synchronously through the normal intake pipeline, and only then starts gossip ([`restart-and-pces.md` — Restart sequence](../../architecture/topics/restart-and-pces.md#restart-sequence); [`restart-and-pces.md` — Replay](../../architecture/topics/restart-and-pces.md#replay)).

The trace also touches the **freeze-round signed state** as the payload of the handoff between old and new versions — the on-disk directory that bundles the round's signed state, its signatures, and the PCES sub-tree the new binary needs to replay back to the same point ([`signed-state-management.md` — On-disk layout](../../architecture/topics/signed-state-management.md#on-disk-layout)).

## Scenario setup

The network has been running steadily on software version N. Operators have agreed on a `freezeTime` — a near-future wall-clock instant at which every node should stop producing new rounds, write a marked signed state to disk, and prepare for restart on version N+1. The coordination is in-band: a freeze transaction has been authored on the Execution side and submitted to the network; it travels through gossip and consensus like any other transaction, and every node will act on it by reading its effect out of the platform state it just helped produce. The roster, the current consensus round, and the signed-state cadence are otherwise unchanged. We are about to follow what happens next, from the moment the freeze transaction reaches consensus on every node to the moment the network is back in steady state running version N+1.

## Trace

The trace is six stops. Each stop names the component the upgrade sits in, what happens there, and links to the topic file that owns the mechanism for readers who want to go deeper after the session.

### Stop 1 — The freeze transaction reaches consensus; `freezeTime` is written

**moment_id**: `moment-pre-trace` (before this stop)

The freeze is triggered on the Execution side: a freeze transaction (one of `FREEZE_ONLY`, `FREEZE_UPGRADE`, `TELEMETRY_UPGRADE`, `PREPARE_UPGRADE`, or `FREEZE_ABORT`) is handled by `FreezeHandler` and delegated to `FreezeUpgradeActions`, which writes a `freezeTime` timestamp into platform state via `WritablePlatformStateStore` ([`freeze-and-upgrade.md` — Trigger](../../architecture/topics/freeze-and-upgrade.md#trigger)).

This is the orientation-altitude load-bearing fact: there is no out-of-band coordination channel. Every node learns of the upcoming freeze by the same mechanism it learns of any other state change — reaching consensus on the freeze transaction. The consensus side reads the written field back out through `FreezePeriodChecker` and consults `PlatformStateUtils#isInFreezePeriod`, which returns true when `freezeTime` is set, consensus time has reached or passed it, and the last completed freeze has not yet caught up to it. The trigger is therefore deterministic across the roster: every honest node, having ordered the same freeze transaction, sees the same `freezeTime` and the same predicate flip in the same place in consensus history.

### Stop 2 — The first round in the freeze period becomes the freeze round

The consensus engine keeps running normally as new events arrive. It produces rounds at the usual cadence. When the timestamp of a new consensus round falls inside the freeze period, the freeze-round controller in the hashgraph layer recognises this as the freeze round: it keeps that round, modifies the round's `EventWindow` so the upgrade boundary is clean (setting the birth round equal to the latest consensus round, so pre- and post-upgrade events can be distinguished after restart), discards any later rounds the same batch produced, and flips an internal `isFrozen` flag ([`freeze-and-upgrade.md` — Freeze procedure](../../architecture/topics/freeze-and-upgrade.md#freeze-procedure)). Once `isFrozen`, the consensus engine ignores every subsequent event: no further rounds will be produced on this version.

The freeze round is the same round on every node — the consensus order of the freeze transaction and the timestamp predicate together pin the upgrade boundary to a single point in shared consensus history.

### Stop 3 — Event creation halts; gossip continues

Platform status enters `FREEZING`. Two behavioural changes follow, with opposite shapes ([`freeze-and-upgrade.md` — Freeze-time behaviour](../../architecture/topics/freeze-and-upgrade.md#freeze-time-behaviour)).

Event creation **stops**, almost. The event creator's `PlatformStatusRule` permits creation in `FREEZING` only when the signature-transaction buffer is non-empty — so the node may still emit an event that carries its signature on the freeze state, but no further application events. The documented intent on `PlatformStatus.FREEZING` is that a node will not produce events after the one carrying its self-signature on the freeze state ([`event-creator.md` — Backpressure interaction](../../architecture/topics/event-creator.md#backpressure-interaction)).

Gossip **continues**, deliberately. The per-peer protocol's status permit set explicitly includes both `FREEZING` and `FREEZE_COMPLETE`, with no separate freeze branch. Continuing to gossip after consensus has stopped is the point: it is how state signatures from this node reach peers that have not yet seen them, and how this node receives signatures it still needs. The freeze procedure is, in effect, a coordinated halt with a signature-distribution tail ([`gossip.md` — Protocol stack](../../architecture/topics/gossip.md#protocol-stack)).

### Stop 4 — The freeze state is produced, signed, and persisted

**moment_id**: `moment-handoff`

The signed state for the freeze round is produced as for any other round — the consensus-side transaction handler emits a fresh `SignedState` after consensus, and state-signature transactions from peers accumulate into the state's `SigSet` until quorum ([`signed-state-management.md` — Lifecycle](../../architecture/topics/signed-state-management.md#lifecycle)). What is different is the persist decision: the saved-state controller sees `isFreezeState()` on this round's state and short-circuits its periodic-snapshot logic, returning the `FREEZE_STATE` save reason unconditionally; the snapshot manager writes the state synchronously, and the on-disk metadata records `freezeState = true` so the next boot can recognise this directory as the product of a freeze ([`freeze-and-upgrade.md` — State save](../../architecture/topics/freeze-and-upgrade.md#state-save); [`signed-state-management.md` — Lifecycle](../../architecture/topics/signed-state-management.md#lifecycle)).

The on-disk directory that the writer produces bundles more than the state itself: alongside the state files it includes the round's signature set, the active roster, the consensus snapshot, the configuration that was in effect, and a PCES sub-tree of event files needed to replay the state from this round ([`signed-state-management.md` — On-disk layout](../../architecture/topics/signed-state-management.md#on-disk-layout)). The PCES sub-tree is the part the restart path will actually consume; it is what makes the freeze directory self-sufficient as the input to the new binary's boot.

Once the state has reached disk, the status state machine transitions `FREEZING → FREEZE_COMPLETE`. Gossip continues across this transition for the same reason it continued before it: laggards may still need to receive the freeze-state signatures, and the node has them to send.

### Stop 5 — The JVM exits; operators bring up the new version

What triggers the actual JVM shutdown and the binary swap is outside the consensus layer's purview — it is operator orchestration around the platform, not platform code. The freeze directory on disk is the handoff: every operator's new binary will start by reading exactly this directory. From the consensus layer's point of view, this stop is a pause; from the operator's point of view, it is the upgrade window.

### Stop 6 — Restart: load state, replay PCES, then start gossip

**moment_id**: `moment-replay-before-gossip`

The new version comes up. `SwirldsPlatform` construction reads the latest signed state from disk into memory, and the platform derives the bounds it will need for replay from that state: the `startingRound` is the loaded state's last consensus round, and the `pcesReplayLowerBound` is its initial ancient threshold ([`restart-and-pces.md` — Restart sequence](../../architecture/topics/restart-and-pces.md#restart-sequence)). The bounds together define which on-disk PCES events the new boot will treat as in-scope.

The platform then enters its run sequence. It brings up the recycle bin, metrics, and the platform coordinator. It runs PCES replay synchronously: the replayer opens an iterator over the PCES files in the replay window, drives each event onto the same input wire that gossip-delivered events use, and the events flow through the normal intake pipeline — hashing, validation, deduplication, orphan buffering, topological emission — and on into the hashgraph, where they reach consensus and update state exactly as they did the first time around ([`restart-and-pces.md` — Replay](../../architecture/topics/restart-and-pces.md#replay); [`event-intake.md` — Inputs and outputs](../../architecture/topics/event-intake.md#inputs-and-outputs)).

Only after replay has run to completion does the platform coordinator start gossip ([`restart-and-pces.md` — Restart sequence](../../architecture/topics/restart-and-pces.md#restart-sequence)). This ordering is the orientation-altitude load-bearing fact for the restart side: gossip is not how the node catches back up after an upgrade. PCES replay is. By the time gossip starts, the node's preconsensus and consensus state are already reconstituted up to the freeze round; from gossip's first sync it is operating on the post-upgrade hashgraph, on the new software version, anchored to the consensus history that the network agreed on before the freeze.

The orientation arc closes here. The network is now running version N+1 in steady state, and every node carries the same view of consensus history across the upgrade boundary because every node bootstrapped from the same freeze-round signed state and replayed the same PCES events on top of it.

## Engagement moves

Two moments along the trace are load-bearing enough to warrant a choice of teaching technique. The tutor picks contingent on what the learner shows and varies move type across the two moments so the session does not become monotonous.

### Moment `moment-pre-trace` — eliciting the role-level prediction

Sits before Stop 1. Load-bearing because the orientation's first job is to surface the learner's existing mental model of how a coordinated upgrade is executed before walking the canonical version on top of it. A learner who has spent time in distributed-systems schemas may import a leader-driven or out-of-band coordination model from Raft, Paxos, or rolling-deploy infrastructure intuition; the orientation distinguishes those from the consensus-mediated coordination this codebase actually uses.

**Move A — prediction-and-reveal (role level).**

- **Diagnosis tag**: opening of an orientation session; the learner has general distributed-systems and consensus-layer familiarity and can produce a sketch.
- **Framing**: "Operators want every node on the network to stop at the same point in consensus history, write a checkpoint to disk, and come back up on a new software version — without any node falling out of sync with the rest. Before we walk the canonical upgrade, what's your gut prediction — which components participate, and roughly in what order, from the moment the operator initiates a freeze through to the network running normally on the new version?"
- **Confidence elicitation (optional)**: "On a one-to-five scale, how confident are you in how the network agrees on the stop point — what signals the network that a freeze has begun, and how do nodes know they're all stopping at the same place?" Useful if the learner has implementation history on the Execution side or on similar coordination flows and may import a synchronous-signal intuition from there.
- **`answer_shape`**: sequential pipeline with one structural side-fact. The canonical shape is `freeze transaction → consensus on the freeze transaction → freezeTime written into platform state → first round in the freeze period becomes the freeze round → consensus stops; event creation halts (with a signature-event exception); gossip continues → freeze-round signed state produced and saved with PCES bundle → JVM exits; operators restart on the new binary → load state, replay PCES, then start gossip → steady state on the new version`. The single structural side-fact a prediction may or may not surface is that coordination is consensus-mediated — every node learns of the freeze through ordinary consensus on a freeze transaction, not through an out-of-band synchronous signal.
- **`alternative_correct_answers`**:
  - Compressed linear sketch that names the spine without the PCES-replay step at restart: "freeze → save state → restart → resume." Credit as correct on the spine; consolidation surfaces the explicit PCES-replay-before-gossip step at restart as the missing transition between "the new binary starts" and "the node is back in the network."
  - Sketch that places the coordination signal out-of-band — e.g. "operators send a network-wide freeze command directly to each node" or "a leader broadcasts the freeze instant." Treat as half-correct — the learner has the upgrade shape right but a wrong locus for coordination. Consolidation moves the trigger inside the consensus order: a freeze transaction reaches consensus like any other transaction, and the predicate that converts `freezeTime` into "stop now" runs the same way on every node by deterministic agreement.
  - Sketch that has the network catching back up at restart by ordinary gossip rather than by PCES replay. Treat as half — gossip is part of normal operation; what restores the node to the freeze-round consensus position on this codebase is PCES replay, before gossip is allowed to start. Consolidate against the strict restart ordering at [`restart-and-pces.md` — Restart sequence](../../architecture/topics/restart-and-pces.md#restart-sequence).
  - Sketch that has event creation and gossip both stopping for the duration of the freeze. Half — event creation stops (with a signature-event exception); gossip explicitly continues so freeze-state signatures can reach laggards. Consolidate against the permit set at [`gossip.md` — Protocol stack](../../architecture/topics/gossip.md#protocol-stack).
- **Canonical answer**: the spine walked through quickly so the learner can see the gap before the trace replaces gut with mechanism. Anchored to [`freeze-and-upgrade.md` — Freeze procedure](../../architecture/topics/freeze-and-upgrade.md#freeze-procedure) and [`restart-and-pces.md` — Restart sequence](../../architecture/topics/restart-and-pces.md#restart-sequence).
- **Consolidation**: name three structural choices the canonical upgrade makes that a typical first prediction misses or compresses — (a) coordination is consensus-mediated via a freeze transaction; (b) gossip does not stop at the freeze, only event creation does (and only for non-signature events); (c) the restart path is "load state, replay PCES, then start gossip" with strict ordering, not "load state, resume gossip and let the network catch us up."

**Move B — direct walk.**

- **Diagnosis tag**: the learner shows fluency on the consensus-layer's coordination vocabulary (freeze transactions, signed-state persistence, PCES); eliciting a prediction would be redundant.
- **Move**: skip the prediction and walk Stop 1 through Stop 6 directly, with the cued check at Moment `moment-handoff` providing the only check on the trace.

### Moment `moment-handoff` — the freeze state as the upgrade payload

Sits at Stop 4. Load-bearing because this is the orientation insight that distinguishes a coordinated upgrade from a coordinated halt: the freeze-round signed state is not just "another saved state that happens to be the last one" — it is the deliberate payload of the upgrade, bundled with the PCES sub-tree the new binary will need to replay back to this point. A learner who internalises that the freeze state and its PCES bundle are *the handoff* between versions has the spine of Stop 6 already in hand.

**Move A — worked example with self-explanation.**

- **Diagnosis tag**: the learner has produced a coherent sketch in `moment-pre-trace` but described the freeze state as "the last saved state before shutdown" without naming what makes it different. A worked example surfaces what the directory actually contains.
- **Move**: walk through the contents of a freeze directory at orientation altitude — the state files, the signature set, the consensus snapshot, the active roster, and (load-bearing) the PCES sub-tree of event files needed to replay state from this round. Mark the PCES sub-tree explicitly as the load-bearing line and prompt:
  - "Which invariant on the upgrade depends on the PCES sub-tree being in the snapshot, and what would break if it were missing?"
  - "What does it mean for the freeze-state save to be marked unconditionally, rather than going through the periodic-snapshot logic that other saved states go through?"
- **Canonical answers for self-explanation**:
  - The bundled PCES sub-tree is what lets the next-boot replay back to the freeze round without depending on the live PCES directory; without it, the new binary would have no source for the preconsensus events it needs to reconstitute state. Anchored to [`signed-state-management.md` — On-disk layout](../../architecture/topics/signed-state-management.md#on-disk-layout).
  - The unconditional save guarantees that the freeze state — the upgrade boundary — is always on disk, regardless of where the periodic-snapshot schedule would have placed the next save. The freeze round is too important to depend on cadence. Anchored to [`freeze-and-upgrade.md` — State save](../../architecture/topics/freeze-and-upgrade.md#state-save).

**Move B — direct walk with cued check.**

- **Diagnosis tag**: the learner has not encountered the freeze-state-as-bundle model before and is unlikely to predict it; a worked walk surfaces the contents directly.
- **Move**: walk Stop 4 directly, naming the state-files / signature set / roster / consensus snapshot / PCES sub-tree contents and the `freezeState = true` metadata flag. Then cue: "Given that the freeze directory contains its own PCES sub-tree, what does the new binary need to read from the live filesystem at restart, and what does it read from the freeze directory? What is the consequence of bundling PCES with the snapshot?" Canonical answer for the cued check: the new binary loads the latest signed state from the freeze directory and uses the PCES sub-tree to drive replay; nothing about the steady-state PCES location matters for the freeze handoff itself. Bundling PCES with the snapshot is what makes the freeze directory a self-sufficient handoff.

### Moment `moment-replay-before-gossip` — PCES replay precedes gossip at restart

Sits at Stop 6. Load-bearing because this is the orientation insight that distinguishes a real platform restart from "the JVM comes up and the node picks back up via gossip." A learner who internalises that PCES replay runs synchronously and gossip cannot start until it completes has the spine of every Pass 2 lesson on restart, recovery, or any cross-cluster scenario where preconsensus state must be reconstituted from disk before the network can be re-engaged.

**Move A — prediction-and-reveal.**

- **Diagnosis tag**: the learner has produced a coherent sketch in `moment-pre-trace` and is past the freeze handoff at Moment `moment-handoff`; they can be expected to predict the restart shape.
- **Framing**: "We are at the point where the new binary has come up and the freeze-round signed state is loaded into memory. Before I tell you what happens next, what's your gut prediction — does this node start gossiping immediately, or does something else have to run first? In one or two sentences, name the mechanism."
- **`answer_shape`**: single mechanism name plus a one-sentence reason. The canonical answer is "PCES replay runs synchronously through the normal intake pipeline before gossip starts — gossip is not started until replay has run to completion, so the network never observes a partially-replayed node."
- **`alternative_correct_answers`**:
  - "PCES replay first, then gossip — the replay drives stored events through intake to rebuild preconsensus state, and only when replay is done does the platform coordinator start gossip." Correct in substance and ordering. Consolidation names the entry point (`PcesModule.replayPcesEvents`) and the strict-ordering guarantee in `SwirldsPlatform`.
  - "PCES replay runs before gossip — it reuses the same intake pipeline that gossip-delivered events use." Correct in substance. Consolidation surfaces the structural choice: replay re-enters the platform through the same wire as gossip, so the events flow through hashing, validation, deduplication, orphan buffering, and topological emission in the same order they did the first time.
  - "Gossip starts immediately; it catches the node back up from peers." Incorrect, and instructively so. This is the model the orientation exists to displace — it would work if peers retained every preconsensus event indefinitely, but they do not, and even if they did, a node bootstrapping from a freeze state already has the events it needs in its own PCES directory. Consolidate against the strict-ordering guarantee at [`restart-and-pces.md` — Restart sequence](../../architecture/topics/restart-and-pces.md#restart-sequence).
  - "The node loads state and is immediately back in operation; no replay is needed." Incorrect. The signed state captures *consensus* state at the freeze round; PCES captures *preconsensus* state — events that had been persisted but had not yet been incorporated into the loaded state's consensus history. Without replay, those events are not in the rebuilt hashgraph at all. Consolidate by distinguishing the two state surfaces and the role of PCES replay in bridging them.
- **Canonical answer**: PCES replay runs synchronously inside `SwirldsPlatform.start()` between core-component startup and gossip startup; the replayer drives stored events onto the normal intake input wire, they flow through hashing / validation / deduplication / orphan buffer / topological emission, reach the hashgraph and consensus, and update state — exactly as they did the first time the events arrived from gossip. Only after replay returns does the platform coordinator start gossip. Anchored to [`restart-and-pces.md` — Restart sequence](../../architecture/topics/restart-and-pces.md#restart-sequence) and [`restart-and-pces.md` — Replay](../../architecture/topics/restart-and-pces.md#replay).
- **Consolidation**: name the structural choice — preconsensus events that this node had persisted but had not yet had reflected in a signed state are exactly the events PCES replay restores; the freeze state plus the PCES replay together reconstitute the node to its pre-freeze point. Then connect the second half of the moment: gossip is held off until replay completes so the network never observes a node with a partially-rebuilt hashgraph, which is the property that lets the new version rejoin the network without any peer needing to know that this node has just restarted.

**Move B — free recall.**

- **Diagnosis tag**: the learner has been engaged through the trace and is approaching the close-out; a free recall before the close-out gives the tutor a check on whether the strict-ordering insight has landed.
- **Move**: pause before walking Stop 6 to completion and prompt: "We've loaded the freeze-round signed state into memory. Before I walk what happens next, can you say in your own words what has to happen between 'state is loaded' and 'this node is participating in the network again'? Two or three steps, in order." Canonical answer for the recall: bring up core platform components (recycle bin, metrics, platform coordinator), run PCES replay through the intake pipeline to reconstitute preconsensus state, then start gossip. The strict order matters: gossip must not start before replay finishes.

## Consolidation

The orientation succeeds when the learner can hold three things at once: the upgrade's component sequence end to end across the freeze-and-restart arc; the consensus-mediated shape of the coordination (the freeze transaction is ordered like any other, and the `freezeTime` predicate flips deterministically on every node at the same point in consensus history); and the strict ordering at restart that places PCES replay before gossip can start. The tutor consolidates explicitly against the predictions made during the moments — naming, in particular, any prediction that placed coordination out-of-band rather than inside consensus order, any prediction that compressed the freeze-state save into "just another snapshot," and any prediction that had the restart node catching back up via gossip rather than via PCES replay.

The trace also surfaces, by what it does not say, the boundaries the orientation respects: how the freeze-period predicate is exactly defined and what guarantees its single-firing on a given `freezeTime`; the precise schedule by which the status state machine transitions through `FREEZING` and `FREEZE_COMPLETE` and what determines when those transitions happen relative to the state save; how the freeze-round controller's birth-round rewrite supports any migration logic the next version may run; what gossip does differently in `FREEZING` versus `FREEZE_COMPLETE`, if anything; how the on-disk freeze directory's individual files are produced and how the PCES sub-tree is bundled. These are the topics Pass 2 deepens.

## Close-out

A brief mental-sketch consolidation. The tutor asks: "If a colleague asked you in the hallway how a Hedera consensus network performs a coordinated software upgrade — not 'how does an upgrade work in general,' but how does *this* network ensure every node stops at the same point and comes back up on the new version in sync — what would you draw on a whiteboard?" The canonical sketch is the six-stop spine — a freeze transaction reaches consensus and writes `freezeTime`; the first round in the freeze period becomes the freeze round and the hashgraph engine stops; event creation halts (signatures excepted) while gossip continues; the freeze-round signed state is produced and saved with its PCES bundle and `FREEZE_COMPLETE` is reached; the JVM exits and operators restart on the new binary; the new binary loads the freeze state, replays PCES through intake, and then starts gossip. The tutor consolidates against whatever the learner draws and names the components the sketch should reach by way of Pass 2.

No threshold concepts; no successive-relearning tags for this lesson.

## Forward pointers

The Pass 2 lessons that deepen each component in this scenario:

- **Freeze trigger, procedure, and `freezeTime`** — `d-01-freeze-trigger-and-coordination` covers the trigger pathway from the Execution side through `WritablePlatformStateStore` to `FreezePeriodChecker`; `d-02-freeze-time-behaviour` covers the per-status behaviour changes (event creation, gossip continuation, state save) in mechanism depth.
- **Upgrade startup** — `d-03-upgrade-startup` covers what the next-boot path does with `SavedStateMetadata.freezeState`, how the consensus side resumes after restart, and the boundaries between Execution-driven and consensus-driven restart behaviour.
- **Freeze and upgrade synthesis** — `d-syn-freeze-upgrade-synthesis` revisits the full coordinated event end to end in Cluster D depth.
- **Signed state as upgrade payload** — `c-01-signed-state-creation-and-types` covers `SignedState`, `ReservedSignedState`, and signature collection; `c-02-reservation-discipline-and-on-disk` covers the on-disk layout (including the PCES sub-tree bundling), the lifecycle, and the reservation discipline that keeps the upgrade-handoff state safely in memory while it is being written.
- **PCES write and replay** — `c-03-pces-write-and-replay` covers the inline-PCES write path, the strict-ordering guarantee that gossip never sees an event before it is on disk, and the replay procedure that this lesson previews at Stop 6.
- **State and recovery synthesis** — `c-syn-state-and-recovery-synthesis` revisits durability, restart, and reconnect together; the Pass 3 entry `pass3-03-coordinated-upgrade-deep` walks this same scenario at full depth.
- **Pass 3 edge cases that interact with the freeze boundary** — `pass3-edge-01-reconnect-during-freeze`, `pass3-edge-04-pces-replay-after-upgrade`, and `pass3-edge-05-squelching-during-freeze` exercise this scenario's invariants under cross-cluster perturbation.

This is the spiral the curriculum exists to walk: orientation here, mechanism in Pass 2, full-depth stitch in Pass 3.

## Open questions

- `[TBD: glossary path]` — the authoring prompt names `platform-sdk/docs/consensus-layer/glossary.md` as a canonical input, but that file does not exist; the term-definition source for this layer is `hashgraphGlossary.md` one directory up. Open question carried over from `pass1-01-tx-to-consensus` and `pass1-02-node-falls-behind`; populate `kb_glossary_terms` on this lesson's frontmatter once decided. Freeze-specific term coverage (e.g. "freeze state," "freeze round," "freeze period," `FREEZING` and `FREEZE_COMPLETE` status definitions) is currently scattered across the topic file and `SavedStateMetadata`; whether to add explicit glossary entries is a decision the reviewer can take alongside the path question.
- `[TBD: JVM exit and binary swap]` — `freeze-and-upgrade.md` flags this as an open question of its own (Section: Freeze procedure, [TBD]): after `FREEZE_COMPLETE`, what triggers the JVM exit and the binary swap is not visible in the consensus-layer or platform-core code anchored in the topic file. The orientation walks Stop 5 at the role-level "operators restart on the new binary" altitude precisely because the mechanism is not in scope for the consensus layer. Reviewer can decide whether to surface this in the Pass 2 deep-dive (`d-03-upgrade-startup`) or leave it to operator documentation.
- `[TBD: boot-path branch on freezeState]` — `freeze-and-upgrade.md` flags this as an open question: where the boot path branches on `SavedStateMetadata#freezeState` (whether PCES replay runs on a freeze-state boot, whether event creation is gated until something completes, whether reconnect is available pre-replay on a freeze-state boot) is not anchored in current reading. This lesson follows the restart sequence documented in `restart-and-pces.md`, which describes PCES replay running unconditionally as part of `SwirldsPlatform.start()`; if the freeze-state boot in fact takes a different branch, the Stop 6 trace needs to surface the distinction at the Pass 2 deep-dive level.
