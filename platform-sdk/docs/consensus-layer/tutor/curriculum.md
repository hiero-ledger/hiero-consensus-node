---
title: Consensus Layer Tutor curriculum
kind: tutor-curriculum-manifest
generated_by: tutor/prompts/lesson-authoring.md (Phase 1 bootstrap)
last_reviewed: 2026-05-13
---

# Consensus Layer Tutor — Curriculum Manifest

This file is the machine-readable plan for the Consensus Layer Tutor curriculum. The YAML block below is the source of truth that the lesson-authoring meta-prompt reads to pick the next lesson to author. The headings beneath the YAML are a human-readable rendering of the same data, for reviewers.

The manifest is human-editable between authoring runs. Renaming, reordering, splitting, or merging entries is fine; the next authoring run picks up the new shape. Status is metadata for reviewers — file existence on disk is the source of truth for whether an entry has been authored.

Authoring inputs at bootstrap time:

- Project brief at `tutor/brief.md` — not present in repo. Manifest scoped from KB + the prompt's pass/cluster structure (lines 22–24 of `tutor/prompts/lesson-authoring.md`).
- KB layout — `platform-sdk/docs/consensus-layer/README.md` (Tutor reads from `concepts/`, `../hashgraphGlossary.md`, `architecture/`, `tutor/`).
- Architecture overview — `platform-sdk/docs/consensus-layer/architecture/overview.md` (11 topic files; module map).
- Topic files — all 11 under `architecture/topics/`.
- Concept files — all 9 under `concepts/`.
- Glossary — `platform-sdk/docs/hashgraphGlossary.md` (note: not at `consensus-layer/glossary.md`; the prompt's path reference does not match the repo layout).
- `invariants.md` — README marks pending; not yet populated.
- `delta-map/`, `decisions/`, `scenarios/` — README files only; no entries yet. Pass 2 / Pass 3 authoring runs will hit `[TBD]` markers wherever these are cited.

```yaml
entries:
  # ============================================================
  # Pass 1 — Orientation scenarios (four canonical traces)
  # ============================================================
  - id: pass1-01-transaction-to-consensus
    index: 1
    pass: 1
    cluster: pass1
    title: A transaction flows from submission to consensus
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/event-intake.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/gossip.md
      - architecture/topics/hashgraph.md
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: pass1-02-node-falls-behind
    index: 2
    pass: 1
    cluster: pass1
    title: A node falls behind and reconnects
    source_topics:
      - architecture/topics/gossip.md
      - architecture/topics/reconnect.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/event-intake.md
      - architecture/topics/hashgraph.md
    status: not_started

  - id: pass1-03-coordinated-network-upgrade
    index: 3
    pass: 1
    cluster: pass1
    title: A coordinated network upgrade
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/event-creator.md
      - architecture/topics/hashgraph.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/gossip.md
    status: not_started

  - id: pass1-04-event-creation-under-stress
    index: 4
    pass: 1
    cluster: pass1
    title: Event creation under stress
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/event-intake.md
    status: not_started

  # ============================================================
  # Pass 2 / Cluster 0 — Wiring Framework Foundation
  # ============================================================
  - id: c0-01-task-schedulers
    index: 5
    pass: 2
    cluster: c0
    title: Task schedulers and threading policies
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: c0-02-wires-and-soldering
    index: 6
    pass: 2
    cluster: c0
    title: Input wires, output wires, and soldering
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: c0-03-transformers-and-splitters
    index: 7
    pass: 2
    cluster: c0
    title: Transformers, splitters, and fan-out
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: c0-04-wiring-model-and-validation
    index: 8
    pass: 2
    cluster: c0
    title: WiringModel — graph validation and lifecycle
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: c0-05-backpressure-and-flushing
    index: 9
    pass: 2
    cluster: c0
    title: Wire-level backpressure, flushing, and squelching
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: c0-syn-wiring-composition
    index: 10
    pass: 2
    cluster: c0-syn
    title: Synthesis — composing a module with ComponentWiring
    source_topics:
      - architecture/topics/wiring-framework.md
      - architecture/topics/event-intake.md
    status: not_started

  # ============================================================
  # Pass 2 / Cluster A.1 — Hashgraph algorithm
  # ============================================================
  - id: a1-01-hashgraph-dag
    index: 11
    pass: 2
    cluster: a1
    title: The hashgraph DAG
    source_topics:
      - architecture/topics/hashgraph.md
      - concepts/hashgraph-dag.md
    status: not_started

  - id: a1-02-rounds-and-witnesses
    index: 12
    pass: 2
    cluster: a1
    title: Rounds and witnesses
    source_topics:
      - architecture/topics/hashgraph.md
      - concepts/rounds-and-witnesses.md
    status: not_started

  - id: a1-03-strongly-seeing
    index: 13
    pass: 2
    cluster: a1
    title: Strongly-seeing
    source_topics:
      - architecture/topics/hashgraph.md
      - concepts/strongly-seeing.md
    status: not_started

  - id: a1-04-fame-voting-and-coin-rounds
    index: 14
    pass: 2
    cluster: a1
    title: Fame voting and coin rounds
    source_topics:
      - architecture/topics/hashgraph.md
      - concepts/voting.md
      - concepts/coin-rounds.md
    status: not_started

  - id: a1-05-judges-and-consensus-order
    index: 15
    pass: 2
    cluster: a1
    title: Judges and the consensus order
    source_topics:
      - architecture/topics/hashgraph.md
      - concepts/judges.md
    status: not_started

  - id: a1-06-birth-round
    index: 16
    pass: 2
    cluster: a1
    title: Birth round, future buffering, and the ancient window
    source_topics:
      - architecture/topics/hashgraph.md
      - concepts/birth-round.md
    status: not_started

  - id: a1-07-event-lifecycle-and-stale
    index: 17
    pass: 2
    cluster: a1
    title: Event lifecycle — admitted, ancient, expired, stale
    source_topics:
      - architecture/topics/hashgraph.md
      - concepts/event-lifecycle.md
      - concepts/stale-events.md
    status: not_started

  - id: a1-syn-hashgraph-synthesis
    index: 18
    pass: 2
    cluster: a1-syn
    title: Synthesis — an event walks the hashgraph pipeline
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started

  # ============================================================
  # Pass 2 / Cluster A.2 — Event Intake
  # ============================================================
  - id: a2-01-intake-validation-pipeline
    index: 19
    pass: 2
    cluster: a2
    title: The five-stage validation pipeline
    source_topics:
      - architecture/topics/event-intake.md
    status: not_started

  - id: a2-02-orphan-buffer
    index: 20
    pass: 2
    cluster: a2
    title: The orphan buffer — linking and release
    source_topics:
      - architecture/topics/event-intake.md
    status: not_started

  - id: a2-03-birth-round-filtering-in-intake
    index: 21
    pass: 2
    cluster: a2
    title: Birth-round filtering across intake stages
    source_topics:
      - architecture/topics/event-intake.md
      - architecture/topics/hashgraph.md
    status: not_started

  - id: a2-04-durability-handoff
    index: 22
    pass: 2
    cluster: a2
    title: Durability handoff — intake to PCES to consensus, gossip, and event creator
    source_topics:
      - architecture/topics/event-intake.md
      - architecture/topics/restart-and-pces.md
    status: not_started

  - id: a2-syn-intake-synthesis
    index: 23
    pass: 2
    cluster: a2-syn
    title: Synthesis — a peer event and a self-event traverse intake
    source_topics:
      - architecture/topics/event-intake.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/event-creator.md
    status: not_started

  # ============================================================
  # Pass 2 / Cluster A.3 — Gossip
  # ============================================================
  - id: a3-01-protocol-stack
    index: 24
    pass: 2
    cluster: a3
    title: The per-connection protocol stack
    source_topics:
      - architecture/topics/gossip.md
    status: not_started

  - id: a3-02-rpc-sync-threading
    index: 25
    pass: 2
    cluster: a3
    title: RPC sync — threading model and per-peer state
    source_topics:
      - architecture/topics/gossip.md
    status: not_started

  - id: a3-03-three-phase-sync
    index: 26
    pass: 2
    cluster: a3
    title: The three-phase sync protocol
    source_topics:
      - architecture/topics/gossip.md
    status: not_started

  - id: a3-04-broadcast-and-fair-selector
    index: 27
    pass: 2
    cluster: a3
    title: Simple broadcast and the fair sync selector
    source_topics:
      - architecture/topics/gossip.md
    status: not_started

  - id: a3-syn-gossip-synthesis
    index: 28
    pass: 2
    cluster: a3-syn
    title: Synthesis — a full sync from connection to send list
    source_topics:
      - architecture/topics/gossip.md
    status: not_started

  # ============================================================
  # Pass 2 / Cluster A.4 — Event Creator
  # ============================================================
  - id: a4-01-tipsets-and-advancement
    index: 29
    pass: 2
    cluster: a4
    title: Tipsets and the advancement score
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started

  - id: a4-02-snapshot-and-creation-rule
    index: 30
    pass: 2
    cluster: a4
    title: Snapshots and the event-creation rule
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started

  - id: a4-03-selfishness-and-pity-picks
    index: 31
    pass: 2
    cluster: a4
    title: Selfishness score and probabilistic pity-picks
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started

  - id: a4-04-creation-permission-rules
    index: 32
    pass: 2
    cluster: a4
    title: The event-creation permission chain
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/health-monitor-and-backpressure.md
    status: not_started

  - id: a4-syn-event-creator-synthesis
    index: 33
    pass: 2
    cluster: a4-syn
    title: Synthesis — a self-event from peer arrival to signed emission
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/event-intake.md
    status: not_started

  # ============================================================
  # Pass 2 / Cluster A.5 — Steady-state synthesis
  # ============================================================
  - id: a5-syn-steady-state-synthesis
    index: 34
    pass: 2
    cluster: a5-syn
    title: Steady-state synthesis — Hashgraph, Intake, Gossip, and Event Creator collaborating
    source_topics:
      - architecture/topics/hashgraph.md
      - architecture/topics/event-intake.md
      - architecture/topics/gossip.md
      - architecture/topics/event-creator.md
      - architecture/topics/restart-and-pces.md
    status: not_started

  # ============================================================
  # Pass 2 / Cluster B — Health Monitor + Reasons not to gossip
  # ============================================================
  - id: b-01-health-monitor-detection
    index: 35
    pass: 2
    cluster: b
    title: Health monitor — detection and the unhealthy-duration signal
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: b-02-health-reactions
    index: 36
    pass: 2
    cluster: b
    title: Reactions — event creation, gossip permits, transaction acceptance, PCES replay
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
      - architecture/topics/restart-and-pces.md
    status: not_started

  - id: b-03-reasons-not-to-gossip-categorical
    index: 37
    pass: 2
    cluster: b
    title: Categorical reasons not to gossip — durability, halted, status, peer behind
    source_topics:
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/gossip.md
    status: not_started

  - id: b-04-broadcast-and-sync-guards
    index: 38
    pass: 2
    cluster: b
    title: Broadcast disabled, holdback, cooldown, fair-selector guards
    source_topics:
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/gossip.md
    status: not_started

  - id: b-syn-load-handling-synthesis
    index: 39
    pass: 2
    cluster: b-syn
    title: Synthesis — load handling under unhealthy and categorical conditions
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/freeze-and-upgrade.md
    status: not_started

  # ============================================================
  # Pass 2 / Cluster C — Signed State, Restart + PCES, Reconnect
  # ============================================================
  - id: c-01-signed-state-and-reservations
    index: 40
    pass: 2
    cluster: c
    title: Signed state runtime types and reservation discipline
    source_topics:
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: c-02-signed-state-on-disk
    index: 41
    pass: 2
    cluster: c
    title: Signed state on disk — layout and atomic rename
    source_topics:
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: c-03-signed-state-lifecycle
    index: 42
    pass: 2
    cluster: c
    title: Signed state lifecycle — create, sign, save, dump, write, reclaim
    source_topics:
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: c-04-inline-pces-and-restart
    index: 43
    pass: 2
    cluster: c
    title: Inline PCES and the restart sequence
    source_topics:
      - architecture/topics/restart-and-pces.md
      - architecture/topics/event-intake.md
    status: not_started

  - id: c-05-pces-replay-and-iss-recovery
    index: 44
    pass: 2
    cluster: c
    title: PCES replay and offline ISS recovery
    source_topics:
      - architecture/topics/restart-and-pces.md
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: c-06-reconnect-lifecycle
    index: 45
    pass: 2
    cluster: c
    title: Reconnect lifecycle — detection, learner/teacher, validate, resume
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/gossip.md
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: c-syn-recovery-synthesis
    index: 46
    pass: 2
    cluster: c-syn
    title: Synthesis — a node falls behind, reconnects, and resumes
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/gossip.md
      - architecture/topics/event-intake.md
      - architecture/topics/hashgraph.md
    status: not_started

  # ============================================================
  # Pass 2 / Cluster D — Freeze and Upgrade
  # ============================================================
  - id: d-01-freeze-trigger-and-detection
    index: 47
    pass: 2
    cluster: d
    title: Freeze trigger and the in-freeze predicate
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
    status: not_started

  - id: d-02-freeze-procedure
    index: 48
    pass: 2
    cluster: d
    title: The freeze procedure — round cutoff, state save, status transition
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/hashgraph.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
    status: not_started

  - id: d-03-upgrade-startup
    index: 49
    pass: 2
    cluster: d
    title: Upgrade startup — booting on a freeze state
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: d-syn-freeze-upgrade-synthesis
    index: 50
    pass: 2
    cluster: d-syn
    title: Synthesis — full freeze and upgrade cycle
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
      - architecture/topics/hashgraph.md
    status: not_started

  # ============================================================
  # Pass 3 — Canonical scenarios revisited at full depth
  # ============================================================
  - id: pass3-01-transaction-to-consensus-deep
    index: 51
    pass: 3
    cluster: pass3-canonical
    title: Transaction to consensus, at full depth
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/event-intake.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/gossip.md
      - architecture/topics/hashgraph.md
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: pass3-02-node-falls-behind-deep
    index: 52
    pass: 3
    cluster: pass3-canonical
    title: Node falls behind, at full depth
    source_topics:
      - architecture/topics/gossip.md
      - architecture/topics/reconnect.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/event-intake.md
      - architecture/topics/hashgraph.md
      - architecture/topics/reasons-not-to-gossip.md
    status: not_started

  - id: pass3-03-coordinated-network-upgrade-deep
    index: 53
    pass: 3
    cluster: pass3-canonical
    title: Coordinated network upgrade, at full depth
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/event-creator.md
      - architecture/topics/hashgraph.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/gossip.md
      - architecture/topics/reasons-not-to-gossip.md
    status: not_started

  - id: pass3-04-event-creation-under-stress-deep
    index: 54
    pass: 3
    cluster: pass3-canonical
    title: Event creation under stress, at full depth
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/event-intake.md
      - architecture/topics/restart-and-pces.md
    status: not_started

  # ============================================================
  # Pass 3 — Edge cases
  # ============================================================
  - id: pass3-edge-01-reconnect-during-freeze
    index: 55
    pass: 3
    cluster: pass3-edge
    title: Reconnect attempted during freeze
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/gossip.md
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: pass3-edge-02-fall-behind-during-heavy-gossip
    index: 56
    pass: 3
    cluster: pass3-edge
    title: Fall-behind triggered by Health Monitor during heavy gossip
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/gossip.md
      - architecture/topics/reconnect.md
      - architecture/topics/event-intake.md
      - architecture/topics/reasons-not-to-gossip.md
    status: not_started

  - id: pass3-edge-03-roster-change-during-reconnect
    index: 57
    pass: 3
    cluster: pass3-edge
    title: Roster change during reconnect
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/hashgraph.md
      - architecture/topics/event-intake.md
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: pass3-edge-04-pces-replay-after-upgrade
    index: 58
    pass: 3
    cluster: pass3-edge
    title: PCES replay after upgrade
    source_topics:
      - architecture/topics/restart-and-pces.md
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/hashgraph.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/event-intake.md
    status: not_started

  - id: pass3-edge-05-squelching-during-freeze
    index: 59
    pass: 3
    cluster: pass3-edge
    title: Squelching during freeze
    source_topics:
      - architecture/topics/wiring-framework.md
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/event-intake.md
      - architecture/topics/hashgraph.md
    status: not_started
```

## Human-readable rendering

### Pass 1 — Orientation scenarios

Four canonical traces at role-level altitude. No code anchors; threading concept-file and topic-file links through the trace. Pre-trains the four operating modes a senior consensus engineer needs to be able to picture before going deep on any single subsystem.

1. `pass1-01-transaction-to-consensus` — A transaction flows from submission to consensus.
2. `pass1-02-node-falls-behind` — A node falls behind and reconnects.
3. `pass1-03-coordinated-network-upgrade` — A coordinated network upgrade.
4. `pass1-04-event-creation-under-stress` — Event creation under stress.

### Pass 2 / Cluster 0 — Wiring framework foundation

Five sub-lessons plus synthesis. The wiring substrate every other Pass 2 cluster builds on. Each sub-lesson anchors on a different facet of `wiring-framework.md`.

5. `c0-01-task-schedulers` — Task schedulers and threading policies.
6. `c0-02-wires-and-soldering` — Input wires, output wires, and soldering.
7. `c0-03-transformers-and-splitters` — Transformers, splitters, and fan-out.
8. `c0-04-wiring-model-and-validation` — WiringModel — graph validation and lifecycle.
9. `c0-05-backpressure-and-flushing` — Wire-level backpressure, flushing, and squelching.
10. `c0-syn-wiring-composition` — Synthesis: composing a module with ComponentWiring (DefaultEventIntakeModule pattern).

### Pass 2 / Cluster A.1 — Hashgraph algorithm

Seven sub-lessons plus synthesis. Anchored on the concept files under `concepts/`, in the prerequisite order the prompt prescribes (DAG → rounds-and-witnesses → strongly-seeing → judges → consensus-order → birth-round; ancient and stale come last). Voting and coin-rounds are combined because coin rounds is a tweak inside the voting algorithm. Judges and consensus-order are combined because the judges' whitening byte string is consumed in the sort. Event-lifecycle and stale-events are combined because stale events are the negative branch of the lifecycle staircase.

11. `a1-01-hashgraph-dag` — The hashgraph DAG.
12. `a1-02-rounds-and-witnesses` — Rounds and witnesses.
13. `a1-03-strongly-seeing` — Strongly-seeing.
14. `a1-04-fame-voting-and-coin-rounds` — Fame voting and coin rounds.
15. `a1-05-judges-and-consensus-order` — Judges and the consensus order.
16. `a1-06-birth-round` — Birth round, future buffering, and the ancient window.
17. `a1-07-event-lifecycle-and-stale` — Event lifecycle — admitted, ancient, expired, stale.
18. `a1-syn-hashgraph-synthesis` — Synthesis: an event walks the hashgraph pipeline.

### Pass 2 / Cluster A.2 — Event intake

Four sub-lessons plus synthesis. Anchored on `event-intake.md`'s pipeline, orphan-buffer, filtering, and durability-handoff sections.

19. `a2-01-intake-validation-pipeline` — The five-stage validation pipeline.
20. `a2-02-orphan-buffer` — The orphan buffer — linking and release.
21. `a2-03-birth-round-filtering-in-intake` — Birth-round filtering across intake stages.
22. `a2-04-durability-handoff` — Durability handoff — intake → PCES → consensus, gossip, event creator.
23. `a2-syn-intake-synthesis` — Synthesis: a peer event and a self-event traverse intake.

### Pass 2 / Cluster A.3 — Gossip

Four sub-lessons plus synthesis. Anchored on `gossip.md`'s protocol stack, RPC sync, three-phase protocol, and broadcast/fair-selector.

24. `a3-01-protocol-stack` — The per-connection protocol stack.
25. `a3-02-rpc-sync-threading` — RPC sync — threading model and per-peer state.
26. `a3-03-three-phase-sync` — The three-phase sync protocol.
27. `a3-04-broadcast-and-fair-selector` — Simple broadcast and the fair sync selector.
28. `a3-syn-gossip-synthesis` — Synthesis: a full sync from connection to send list.

### Pass 2 / Cluster A.4 — Event creator

Four sub-lessons plus synthesis. Anchored on `event-creator.md`'s tipset, snapshot, selfishness, and permission-rule sections.

29. `a4-01-tipsets-and-advancement` — Tipsets and the advancement score.
30. `a4-02-snapshot-and-creation-rule` — Snapshots and the event-creation rule.
31. `a4-03-selfishness-and-pity-picks` — Selfishness score and probabilistic pity-picks.
32. `a4-04-creation-permission-rules` — The event-creation permission chain.
33. `a4-syn-event-creator-synthesis` — Synthesis: a self-event from peer arrival to signed emission.

### Pass 2 / Cluster A.5 — Steady-state synthesis

Single synthesis lesson that exercises Hashgraph, Event Intake, Gossip, and Event Creator collaborating under nominal load. The Pass 2 "events flow normally" altitude before Pass 2 / Cluster B drags the system off the happy path.

34. `a5-syn-steady-state-synthesis` — Steady-state synthesis.

### Pass 2 / Cluster B — Health monitor and reasons not to gossip

Four sub-lessons plus synthesis. The two topics share this cluster because both are about *how the system departs from steady state* — backpressure is the queue-driven side, reasons-not-to-gossip is the categorical side.

35. `b-01-health-monitor-detection` — Health monitor — detection and the unhealthy-duration signal.
36. `b-02-health-reactions` — Reactions — event creation, gossip permits, transaction acceptance, PCES replay.
37. `b-03-reasons-not-to-gossip-categorical` — Categorical guards — durability, halted, status, peer behind.
38. `b-04-broadcast-and-sync-guards` — Broadcast disabled, holdback, cooldown, fair-selector guards.
39. `b-syn-load-handling-synthesis` — Synthesis: load handling under unhealthy and categorical conditions.

### Pass 2 / Cluster C — Signed state, restart + PCES, reconnect

Six sub-lessons plus synthesis. The three topics share this cluster because they are the recovery surface — what the platform does when state must be reconstructed (restart) or fetched from a peer (reconnect), and what the canonical signed-state objects look like in memory and on disk.

40. `c-01-signed-state-and-reservations` — Signed state runtime types and reservation discipline.
41. `c-02-signed-state-on-disk` — Signed state on disk — layout and atomic rename.
42. `c-03-signed-state-lifecycle` — Signed state lifecycle — create, sign, save, dump, write, reclaim.
43. `c-04-inline-pces-and-restart` — Inline PCES and the restart sequence.
44. `c-05-pces-replay-and-iss-recovery` — PCES replay and offline ISS recovery.
45. `c-06-reconnect-lifecycle` — Reconnect lifecycle — detection, learner/teacher, validate, resume.
46. `c-syn-recovery-synthesis` — Synthesis: a node falls behind, reconnects, and resumes.

### Pass 2 / Cluster D — Freeze and upgrade

Three sub-lessons plus synthesis. The terminal Pass 2 cluster; covers the coordinated-stop primitive and the upgrade-boot path.

47. `d-01-freeze-trigger-and-detection` — Freeze trigger and the in-freeze predicate.
48. `d-02-freeze-procedure` — Round cutoff, state save, status transition.
49. `d-03-upgrade-startup` — Upgrade startup — booting on a freeze state.
50. `d-syn-freeze-upgrade-synthesis` — Synthesis: full freeze and upgrade cycle.

### Pass 3 — Canonical scenarios revisited at full depth

Each Pass 3 canonical revisits a Pass 1 scenario with the prerequisite Pass 2 mechanisms in place, so the trace can drop to code-anchor altitude and exercise the invariants the Pass 2 lessons established.

51. `pass3-01-transaction-to-consensus-deep` — Transaction to consensus, at full depth.
52. `pass3-02-node-falls-behind-deep` — Node falls behind, at full depth.
53. `pass3-03-coordinated-network-upgrade-deep` — Coordinated network upgrade, at full depth.
54. `pass3-04-event-creation-under-stress-deep` — Event creation under stress, at full depth.

### Pass 3 — Edge cases

Five cross-cluster edge cases. Each one is a perturbation of a canonical scenario by a state from a different cluster — the kind of interaction no single Pass 2 lesson can teach.

55. `pass3-edge-01-reconnect-during-freeze` — Reconnect attempted during freeze.
56. `pass3-edge-02-fall-behind-during-heavy-gossip` — Fall-behind triggered by Health Monitor during heavy gossip.
57. `pass3-edge-03-roster-change-during-reconnect` — Roster change during reconnect.
58. `pass3-edge-04-pces-replay-after-upgrade` — PCES replay after upgrade.
59. `pass3-edge-05-squelching-during-freeze` — Squelching during freeze.

## Notes for reviewers

The manifest is intended to be edited by humans. Likely review angles:

- **A.1 density.** Cluster A.1 carries seven sub-lessons plus a synthesis, above the prompt's "three to seven" guidance, because the cluster is anchored on nine concept files. Voting + coin-rounds and judges + consensus-order and event-lifecycle + stale-events are each merged to stay within range; splitting any of those back out is reasonable if a reviewer prefers finer-grained pacing.
- **Cluster B pairing.** Health monitor and reasons-not-to-gossip share Cluster B per the prompt. The two topics are distinct mental models (queue-driven vs. categorical) but are pedagogically adjacent — `b-syn-load-handling-synthesis` exercises both together.
- **Cluster C size.** Three topics share Cluster C, and the cluster has six sub-lessons plus a synthesis — the largest non-A.1 cluster. Splitting it into separate clusters (C.1 Signed state, C.2 Restart + PCES, C.3 Reconnect) is a reasonable alternative.
- **Cluster D thinness.** Cluster D carries three sub-lessons plus a synthesis. The freeze-and-upgrade topic has many open questions in the source `architecture/topics/freeze-and-upgrade.md` (multiple `[TBD]` markers); authoring runs will surface them in the lessons' Open questions sections.
- **Project brief absence.** No `tutor/brief.md` was found in the repo. The manifest's pass/cluster shape comes from the lesson-authoring meta-prompt itself (its lines 22–24). If a brief exists elsewhere with different scoping, regenerate the manifest after pointing the next bootstrap run at it.
- **KB silences.** `invariants.md` is pending; `delta-map/`, `decisions/`, `scenarios/` contain only `README.md`. Pass 2 lessons cite delta-map and invariants by convention — until those directories populate, each authored lesson will surface `[TBD]` markers for its delta-callout and invariant-link sections under Open questions. Pass 3 edges that correspond to documented near-misses will likewise carry `[TBD]` markers until `scenarios/` populates.
- **Glossary path.** The prompt names `glossary.md` under `consensus-layer/`; the actual glossary lives at `platform-sdk/docs/hashgraphGlossary.md` (per the layout README). Authoring runs follow the actual path.
