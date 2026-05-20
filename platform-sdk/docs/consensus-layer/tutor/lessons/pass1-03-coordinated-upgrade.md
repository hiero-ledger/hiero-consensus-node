---
id: pass1-03-coordinated-upgrade
cluster: pass1
title: Coordinated network upgrade
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
  - concepts/birth-round.md
kb_glossary_terms:
  - freeze
  - freeze-state
  - signed-state
  - platform-status
  - preconsensus-event-stream
  - birth-round
  - event-window
  - ancient
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name each component on the coordinated-upgrade path and state its role in one sentence
  - Sketch the order of stages from freeze trigger through state save, restart, replay, and resumption — separating the steps that happen before the JVM exits from the steps that happen after the new binary starts
  - Identify the two load-bearing transitions — the freeze-state save (the durable handoff between binaries) and the post-restart replay-then-gossip gate (gossip is held off until PCES replay completes) — and say in one sentence why each is irreversible
threshold_concepts: []
estimated_session_minutes: 25
status: drafted
last_verified_against: 9db53cdbe89215743387f536b7dfb4f84878f7af
---

# Coordinated network upgrade

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

These are the components the trace walks through. One-sentence semantics per component; the deep mechanism is the subject of the Pass 2 lessons listed under Forward pointers.

- **Execution-side freeze handler** ([freeze-and-upgrade.md, Trigger](../../architecture/topics/freeze-and-upgrade.md#trigger)) — owns the `freezeTime` write that signals the consensus layer to enter a freeze. From the consensus-layer side this is the upstream trigger; the operator tooling that produces the freeze transaction is out of scope.
- **Freeze / upgrade orchestration** ([freeze-and-upgrade.md](../../architecture/topics/freeze-and-upgrade.md)) — not a single component but a procedure distributed across several modules: a round-level cutoff in the hashgraph module, per-rule guards in event creator and gossip, the freeze-state save in the platform-core, and the platform-status transitions through `FREEZING` and into `FREEZE_COMPLETE`.
- **Event creator** ([event-creator.md](../../architecture/topics/event-creator.md)) — winds down once the freeze round is decided. In `FREEZING` status the creator emits only signing events for the freeze state; once the signature-transaction buffer is drained, no new self-events are created until the node has restarted into the next ACTIVE-or-CHECKING period.
- **Gossip** ([gossip.md](../../architecture/topics/gossip.md)) — keeps running through the freeze and into `FREEZE_COMPLETE`, so signing events carrying state signatures can reach every node. Gossip is the only subsystem on this trace whose role grows after the freeze round is decided rather than shrinks.
- **Signed-state management** ([signed-state-management.md](../../architecture/topics/signed-state-management.md)) — produces the `SignedState` for the freeze round, marks it as a freeze state, and writes it to disk synchronously. The freeze-state save is the durable handoff between the running process and the post-restart world.
- **Preconsensus event stream (PCES)** ([restart-and-pces.md](../../architecture/topics/restart-and-pces.md)) — persists self-events along the steady-state path; on restart it replays the on-disk PCES files into the intake pipeline before gossip is allowed to start. Both roles are exercised on this trace.
- **Event intake** ([event-intake.md](../../architecture/topics/event-intake.md)) — the validation-and-topological-ordering pipeline; on restart it is the receiving end of PCES replay, processing replayed events the same way it processes peer-gossip events.

The wiring framework is the substrate that holds the components together — it owns the soldering between intake, PCES, gossip, hashgraph, and the event creator; it owns the status state machine that transitions through `FREEZING` and `FREEZE_COMPLETE`; and (on restart) it owns the platform coordinator that brings the components up in the right order, gating gossip on replay completion. The wiring framework itself is Cluster 0 in the curriculum and is not part of this trace.

## Scenario setup

A small, healthy network: four nodes in steady state, gossip producing events and reaching consensus normally. The operator has prepared an upgrade — a new binary, a new on-disk layout, a configuration change, or a combination — and submits a freeze-upgrade transaction to one node, which gossips it to the rest. The trace follows what every node does from the moment that transaction is processed through to the moment the network is back in steady state on the new binary.

This is the orientation altitude. The trace deliberately does not unpack the exact `freezeTime` / `lastFrozenTime` arithmetic, the precise wiring of the platform coordinator's startup sequence, or the operator-side orchestration of the binary swap. Each Pass 2 lesson listed under Forward pointers exists to walk one of those at depth.

## Trace

### Stop 1 — A freeze transaction is processed {#stop-1-freeze-trigger}

A freeze-upgrade transaction reaches the Execution side on every node. The transaction handler dispatches to a freeze handler that writes a `freezeTime` timestamp into platform state. From the consensus-layer side this is just a field on platform state that subsequent rounds will read back out — but the write itself is what arms the freeze. Until `freezeTime` is set, the network behaves as if no freeze is pending. After it is set, the next consensus round whose timestamp falls in the freeze period is the freeze round.

`moment_id: trace-open`

### Stop 2 — The first freeze round is cut {#stop-2-cut}

Rounds continue to advance normally until one of them lands in the freeze period (consensus timestamp at or beyond `freezeTime`). That round is recognized as the freeze round by the round handler — a side-effect of consuming the round, not a separate event. From this point on:

- The round-level cutoff in the hashgraph module ensures that no further rounds will be produced — once the freeze round is decided, additional events handed to the consensus engine are ignored.
- The platform-status state machine transitions out of ACTIVE/CHECKING into `FREEZING`.
- The freeze round's `EventWindow` has its birth-round value reset to the latest consensus round, so that any migration logic at restart can distinguish pre- and post-upgrade events by birth round (see [`concepts/birth-round.md`](../../concepts/birth-round.md) for the role birth round plays in the DAG).

The freeze round is therefore the last round any node will reach consensus on with this binary. Subsequent events handed to the consensus engine on this binary are no longer integrated.

### Stop 3 — Event creation winds down; gossip keeps running {#stop-3-creator-vs-gossip}

In `FREEZING` status the gates on the two outbound-event subsystems diverge.

- The **event creator** stops creating new application events. The platform-status guard inside the creator permits new self-events in `FREEZING` only while there are signature transactions to carry — that is, signing events for the freeze state. Once those have flushed out, the creator produces nothing more on this binary.
- **Gossip** continues. The sync protocol's permitted-statuses set explicitly admits both `FREEZING` and `FREEZE_COMPLETE`, and this is intentional — the signing events that nodes do still produce need to propagate, and the network needs gossip running so the signatures they carry can reach every other node.

The divergence here is the load-bearing point of the freeze window: event creation is fenced because no new application work should land in this binary's view of the round, while gossip is fenced *open* because state-signature traffic still has work to do.

### Stop 4 — The freeze state is saved to disk {#stop-4-save}

The signed state for the freeze round is marked as a freeze state and saved synchronously to disk. "Synchronously" matters: the save bypasses the periodic-snapshot schedule, runs as an immediate write, and the platform-status transition out of `FREEZING` is gated on its completion. The persisted state's metadata records the freeze flag so that, on the next boot, the loader can recognize the loaded state as the result of a freeze rather than as an ordinary periodic snapshot.

**Load-bearing transition.** This is the irreversible step of the pre-shutdown half of the trace. Everything before this stop is reversible by a crash — the round could be re-decided on reboot, the freeze could be deferred. Everything after this stop is durable: the freeze state exists on disk, and the upgrade has a recoverable landing point even if the JVM dies before any further work happens. The save is also what unblocks the status transition out of `FREEZING`.

### Stop 5 — FREEZE_COMPLETE; signatures keep flowing {#stop-5-freeze-complete}

Once the freeze state has been written to disk, the status state machine transitions `FREEZING` → `FREEZE_COMPLETE`. In `FREEZE_COMPLETE` the platform is in its terminal pre-shutdown shape:

- Event creation remains blocked — neither ACTIVE nor CHECKING is reachable from here without a restart.
- Gossip is still permitted, so any node that has not yet received every signing event for the freeze state can still receive them, and any node that has not yet been able to deliver its own signing event to a particular peer can still do so.
- The signed state is on disk; signatures continue to accumulate on the in-memory `SignedState` until quorum is met across the network, or until the operator brings the node down.

The trace does not attempt to anchor when the operator-driven JVM exit happens — that is driven by orchestration outside the consensus-layer code. The load-bearing fact is that, by the time the JVM does exit, the freeze state on disk is the canonical handoff: the new binary's first job is to pick it up.

### Stop 6 — Operator swaps the binary and restarts the node {#stop-6-swap}

Out of consensus-layer scope but on the trace: the node is shut down, the binary is swapped (or the configuration changed, or both), and the node is restarted. This step is operator-driven — the consensus-layer code does not orchestrate it. What the consensus layer cares about is the post-shutdown invariant: when the new JVM starts, the latest signed state on disk *is* the freeze state, and the new binary will pick it up as its initial state.

### Stop 7 — The new binary loads the freeze state {#stop-7-load}

On startup, the new binary's platform construction phase locates the latest saved state on disk and loads it. The state's metadata identifies it as a freeze state. The starting round is taken from the loaded state's last consensus round, and the PCES-replay lower bound is taken from the loaded state's initial ancient threshold. The exact post-restart branching on the freeze flag — what specifically the runtime does differently because the loaded state is a freeze state — is currently underspecified in the consensus-layer KB (see [Open questions](#open-questions)).

### Stop 8 — PCES replay runs before gossip starts {#stop-8-replay}

After core platform components come up (recycle bin, metrics, platform coordinator), PCES replay runs synchronously: persisted self-events sitting in the PCES files alongside the loaded state are fed back into the intake pipeline so the local view of the round catches up to what was already durable before shutdown. Replay uses the same intake pipeline that ordinary gossip-delivered events go through; the only difference is that the events come from on-disk PCES files instead of the wire.

**Load-bearing transition.** Gossip is held off until replay completes. This is the upgrade analog of the steady-state persisted-before-gossip rule from [`pass1-01-tx-to-consensus`](./pass1-01-tx-to-consensus.md): the network is not allowed to see this node as a gossip participant until the local state has finished reconstituting from disk. If gossip started before replay finished, the node could send or receive events whose parents this node has not yet linked into its DAG, and the durability ordering the upgrade depends on would no longer hold.

### Stop 9 — Gossip starts; the event creator resumes {#stop-9-resume}

Once replay completes, the platform coordinator starts gossip. The node renegotiates its protocol stack with each peer and re-enters the steady-state event-exchange loop. As peers continue making progress on the new binary, the platform-status state machine transitions back into ACTIVE/CHECKING, and the event creator's `PlatformStatusRule` once again admits event creation. The network is now back in the steady state described by [`pass1-01-tx-to-consensus`](./pass1-01-tx-to-consensus.md), on the new binary.

## Engagement moves

### Moment `trace-open` — before Stop 1

This moment sits before the trace begins. The coordinated upgrade is the scenario most likely to be obscured by the operator-tooling layer — engineers often think of it as "the script the SRE runs" rather than as a procedure with a definite consensus-layer shape. Getting the learner's mental sketch on the table first surfaces whatever model they have of what the consensus layer itself owns versus what is delegated to orchestration outside the platform.

**Move A — prediction-and-reveal.** Diagnosis tag: the learner shows general Hedera-team familiarity and is willing to commit to a sketch from prior exposure (incident reports, design docs, or operator runbooks).

- Prompt (verbatim):
  > Before we walk it: the operator has just submitted a freeze-upgrade transaction to the network. From the consensus layer's point of view, sketch what happens between now and the moment the network is back in steady state on the new binary. Which components participate, what is happening on each one, and where are the load-bearing transitions? A rough sequence is fine — don't worry about the deep mechanism inside each step.
- `answer_shape`: sequential list of stages with one explicit before/after-JVM boundary. The asymmetry between event creation (winds down) and gossip (keeps running) through the freeze window is the most-likely-missed detail to credit when it appears.
- `canonical_answer`: a freeze transaction sets a `freezeTime` on every node; the first round in the freeze period is cut as the freeze round; status enters `FREEZING`, event creation winds down to signing-events-for-the-freeze-state only, gossip keeps running so signatures on the freeze state propagate; the freeze state is saved synchronously to disk and status moves to `FREEZE_COMPLETE`; the operator restarts each node onto the new binary; the new binary loads the freeze state from disk; PCES replay runs and gossip is held off until it completes; gossip then starts and the event creator resumes once status is back in ACTIVE/CHECKING.
- `alternative_correct_answers`:
  - An answer that collapses the freeze trigger and the first-freeze-round-cut into a single "the freeze round happens" step is correct; the trigger-vs-detection split is a refinement at this altitude.
  - An answer that says event creation and gossip both stop at the freeze round is *partially correct* but misses the key asymmetry (gossip stays open through `FREEZE_COMPLETE`). Credit the rest, name the gap during consolidation rather than scoring it wrong.
  - An answer that omits the replay-then-gossip gate at restart — "the new binary boots, loads the state, and resumes gossip" — is correct at the orientation altitude; the replay-as-gate is the load-bearing detail on the post-restart half, and the trace will fill it in.
  - An answer that explicitly names two load-bearing transitions — the freeze-state save before shutdown and the replay-then-gossip gate after restart — is correct and more accurate than the canonical's single-boundary framing. Credit it as such.
  - An answer that names the wiring framework, the platform coordinator, or the platform-status state machine as the orchestrators of the cross-component sequencing is correct as additional detail; treat it as a refinement, not a different model.
- `followup` (verbatim, delivered when the learner produced the sequence and named gossip as continuing through the freeze, but did not articulate *why* it has to):
  > You said gossip keeps running while event creation is winding down. Say one sentence about what gossip is doing during the `FREEZING` and `FREEZE_COMPLETE` window — what specifically would stall if gossip stopped at the freeze round the way event creation does?
- `followup_canonical_answer`: any of the following counts — "gossip carries the signing events that distribute state signatures; if it stopped, peers that hadn't yet received every signing event couldn't finish signing the freeze state"; "the signing events the creator still emits during `FREEZING` need to propagate, and gossip is what propagates them"; "stopping gossip would freeze the signature-distribution loop, not just the event-creation loop, and the freeze state would never reach quorum signatures network-wide"; or any answer that contrasts "event creation is fenced because no new application work should land" with "gossip is fenced open because state-signature traffic still has work to do."

**Move B — free recall.** Diagnosis tag: the learner is not confident enough to commit to an ordered sketch, asks for the canonical mapping first, or wants the names of the components without the choreography. This move lowers the stakes — it asks for the set, not the order — and lets the trace itself supply the order.

- Prompt (verbatim):
  > Before we walk it: just by name, which components or subsystems do you think are involved when the network is taken through a coordinated upgrade — from the freeze transaction landing to the network being back in steady state on the new binary? Order isn't important here — I'm checking which pieces you're holding in your head.
- `canonical_answer`: the set {Execution-side freeze handler (writes `freezeTime`), the freeze/upgrade orchestration distributed across hashgraph (round-level cutoff), event creator (winds down to signing events), gossip (keeps running through `FREEZING` and `FREEZE_COMPLETE`), signed-state management (produces and persists the freeze state), PCES (replayed on restart before gossip starts), event intake (the receiving end of replay), and the wiring framework / platform coordinator (orchestrates the restart sequence)}.
- `alternative_correct_answers`:
  - Any non-empty subset that includes (a) the freeze trigger, (b) the freeze-state save, and (c) the post-restart PCES replay — these are the three load-bearing pieces. The downstream subsystems are refinements at the set-naming altitude.
  - Sets that name the platform-status state machine, the round handler, `FreezeRoundController`, or `SavedStateController` by their class-level names rather than the topic-level names are correct.
  - Sets that include the operator/orchestration tooling are correct as additional detail; the trace will distinguish what the consensus layer owns from what is delegated.
  - Sets that omit event intake are correct at this altitude — intake's appearance on this trace is as the receiving end of PCES replay, which is a refinement on the PCES role.

## Consolidation

After the trace, the learner should be able to name each of the seven components in scope and state in one sentence what each one does on the coordinated-upgrade path. They should hold the trace as having *two* load-bearing transitions, not one: the freeze-state save at Stop 4 is the durable handoff between the running process and the post-restart world, and the replay-then-gossip gate at Stop 8 is the post-restart durability ordering that mirrors the steady-state persisted-before-gossip rule. They should be able to say *why* gossip stays open during `FREEZING` and `FREEZE_COMPLETE` (signature distribution still has work to do), *why* the freeze-state save has to be synchronous (the upgrade hinges on the on-disk handoff being complete before status transitions out of `FREEZING`), and *why* gossip is held off until PCES replay completes on restart (the local DAG has to be reconstituted before the node can be visible to peers).

If the learner ran Move A at the trace-open moment, name in plain words the gap between their prediction and the canonical trace: which stops they hit, which they merged or skipped, whether they correctly held event creation and gossip as having different fates through the freeze window, whether they spotted both load-bearing transitions or only the freeze-state save. The point of the contrast is to make the corrections explicit so they consolidate against the trace rather than fade after the reveal.

## Close-out

The learner now holds the complete-but-low-fidelity sketch of a coordinated upgrade. Every Pass 2 lesson under Cluster D — freeze trigger, freeze procedure, freeze-state save, upgrade startup, freeze-to-upgrade synthesis — will assume that the learner can place its component on this trace; the trace itself does not need to be re-explained inside those lessons. The Pass 3 deep version of this scenario, [`pass3-03-coordinated-upgrade-deep`](./pass3-03-coordinated-upgrade-deep.md), revisits the same path once the relevant Pass 2 clusters are taught. Three Pass 3 edge cases re-enter parts of this trace from a different starting condition — reconnect during freeze ([`pass3-edge-01-reconnect-during-freeze`](./pass3-edge-01-reconnect-during-freeze.md)), PCES replay after upgrade ([`pass3-edge-04-pces-replay-after-upgrade`](./pass3-edge-04-pces-replay-after-upgrade.md)), and squelching during freeze ([`pass3-edge-05-squelching-during-freeze`](./pass3-edge-05-squelching-during-freeze.md)).

**Free-recall summary** — delivered verbatim at session close:
> In your own words, sketch the path a coordinated upgrade takes through the consensus layer: name each component as you go, say one sentence about what it does at that step, and call out the two transitions in the trace that are irreversible — one before the JVM exits and one after the new binary starts.

`canonical_answer`: a coherent retelling of the nine stops, naming the seven components in scope, with the freeze-state save (Stop 4) and the replay-then-gossip gate (Stop 8) called out as the two load-bearing transitions, plus a one-sentence reason for each — "the save is what makes the upgrade survive a crash between binaries" and "the gate is what keeps the node from being visible to peers until its local DAG has been rebuilt from disk."

`alternative_correct_answers`: any retelling that hits at least trigger → freeze-round-cut → save → restart → replay → resume, names the freeze-state save as the pre-shutdown durable boundary and (in any phrasing) names a post-restart ordering between replay and gossip, and correctly captures that gossip keeps running through the freeze window while event creation winds down. Compressing trigger and freeze-round-cut into a single step is fine. Compressing `FREEZE_COMPLETE` and operator shutdown into a single step is fine. Naming the wiring framework / platform coordinator as the orchestrator of the post-restart sequence is correct as additional detail, not a different model.

**Successive-relearning tags:** none — this lesson establishes no threshold concept. The mental sketch it plants is consolidated by the Pass 2 lessons under Cluster D, each of which exercises one of its components at depth, and by the Pass 3 deep version of the scenario.

## Forward pointers

Each component in scope is covered at depth by a Pass 2 cluster or sub-cluster:

- **Wiring framework** (the substrate that holds the inter-component soldering, runs the platform-status state machine through `FREEZING` and `FREEZE_COMPLETE`, and runs the platform coordinator on restart) — Cluster 0, starting at [`c0-01-task-schedulers-and-queues`](./c0-01-task-schedulers-and-queues.md).
- **Event creator** (winds down at Stop 3, resumes at Stop 9) — Cluster A.4, starting at [`a4-01-tipset-and-advancement-score`](./a4-01-tipset-and-advancement-score.md). The platform-status guard that admits the signing event in `FREEZING` is unpacked in [`a4-05-creation-gates`](./a4-05-creation-gates.md).
- **Gossip** (keeps running through Stops 3–5, restarts at Stop 9) — Cluster A.3, starting at [`a3-01-protocol-stack`](./a3-01-protocol-stack.md).
- **Event intake** (the receiving end of PCES replay at Stop 8) — Cluster A.2, starting at [`a2-01-hashing-and-internal-validation`](./a2-01-hashing-and-internal-validation.md). The durability handoff between intake and PCES that this trace exercises at steady state is [`a2-05-durability-handoff`](./a2-05-durability-handoff.md).
- **Signed-state management** (produces and persists the freeze state at Stop 4) — Cluster C, starting at [`c-01-signed-state-lifecycle-and-reservations`](./c-01-signed-state-lifecycle-and-reservations.md). The on-disk layout that survives the JVM swap is [`c-02-on-disk-layout`](./c-02-on-disk-layout.md).
- **PCES** (replayed at Stop 8) — Cluster C, [`c-03-inline-pces-write-path`](./c-03-inline-pces-write-path.md) (the steady-state write path that produces the files replay reads) and [`c-04-restart-and-pces-replay`](./c-04-restart-and-pces-replay.md) (the replay mechanism and its replay-before-gossip ordering).
- **Freeze trigger** (Stops 1–2) — Cluster D, [`d-01-freeze-trigger-and-platform-state`](./d-01-freeze-trigger-and-platform-state.md).
- **Freeze procedure** (Stops 2–3, the round-level cutoff and the per-status guards) — [`d-02-freeze-procedure`](./d-02-freeze-procedure.md).
- **Freeze-state save** (Stop 4) — [`d-03-freeze-state-save`](./d-03-freeze-state-save.md).
- **Upgrade startup** (Stops 6–9) — [`d-04-upgrade-startup`](./d-04-upgrade-startup.md).

The full freeze-to-upgrade-restart cycle at mechanism depth is the Cluster D synthesis lesson, [`d-syn-freeze-upgrade-synthesis`](./d-syn-freeze-upgrade-synthesis.md). The Pass 3 deep version of this scenario at full cross-cluster depth is [`pass3-03-coordinated-upgrade-deep`](./pass3-03-coordinated-upgrade-deep.md).

## Open questions

- [TBD: freeze-state-aware boot branching is currently underspecified] The freeze topic notes that, on the next boot, `SavedStateMetadata#freezeState` tells the runtime that the loaded state is the result of a freeze, but the boot-path branch that consumes that flag — to gate PCES replay, event creation, or reconnect — is not anchored cleanly in current consensus-layer code. At orientation altitude this is handled by describing the restart sequence generically at Stop 7 (load → derive bounds → replay → start gossip), but a future Pass 2 / Pass 3 author needs the structural answer to anchor Stop 7's branching precisely. See the "Upgrade startup" TBD list in `freeze-and-upgrade.md`.
- [TBD: ordering between `lastFrozenTime` write, freeze-state save, and `FREEZE_COMPLETE` transition] Stop 4 and Stop 5 are described here as the save followed by the status transition; the exact ordering guarantee between the `lastFrozenTime` write, the on-disk save, and the status transition — and the failure mode if the platform crashes between any two of them — is open on the freeze topic and is needed for the Pass 3 deep version of this scenario.
- [TBD: post-restart `lastFrozenTime` re-arm window] Once the runtime has loaded a freeze state and crossed into normal operation, the `lastFrozenTime` field on the platform state is what prevents the freeze predicate from re-firing on the same `freezeTime`. Whether there is a window where a re-freeze could fire spuriously is flagged on the freeze topic; orientation does not need it, but the Pass 3 deep version does.
- [TBD: how many signing events FREEZING permits] The Stop 3 description and the event-creator topic both treat the `FREEZING`-status guard as admitting "signing events for the freeze state" without committing to whether the count is exactly one or whatever the signature-transaction buffer holds. The freeze topic's `PlatformStatusRule` TBD asks the same question. Orientation does not depend on the answer; the Pass 2 lesson on creation gates ([`a4-05-creation-gates`](./a4-05-creation-gates.md)) does.
- [TBD: glossary path mismatch — inherited from `pass1-01` and `pass1-02`] The lesson-authoring meta-prompt references `consensus-layer/glossary.md`, which does not exist; the canonical glossary is at `platform-sdk/docs/hashgraphGlossary.md`. The `kb_glossary_terms` listed in frontmatter resolve against that file. Flagging here so the reviewer fix at the prompt level closes all three Pass 1 lessons.
- [TBD: invariants and delta-map remain stubs — inherited] `consensus-layer/invariants.md` and the entries under `consensus-layer/delta-map/` do not exist yet (only README placeholders). Orientation depth does not require them; this open question carries forward so subsequent Pass 2 and Pass 3 work in this area can ground its structural and delta claims.
