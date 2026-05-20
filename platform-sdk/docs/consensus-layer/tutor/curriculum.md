# Consensus Layer Tutor — Curriculum Manifest

This file is the curriculum manifest for the Consensus Layer Tutor. It was bootstrapped by the lesson-authoring meta-prompt at `prompts/lesson-authoring.md` from the brief at `brief.md`, the KB layout at `../README.md`, the 11 architecture topic files under `../architecture/topics/`, the 10 concept files under `../concepts/`, and the canonical glossary at `../../hashgraphGlossary.md`.

The manifest covers the full curriculum in pedagogical order:

- **Pass 1** — four canonical orientation scenarios that plant a complete-but-low-fidelity mental sketch.
- **Pass 2** — Cluster 0 (Wiring Framework Foundation), then Cluster A (Steady-state event flow, split into A.1–A.5), then Cluster B (Stress, health, self-throttling), Cluster C (State, persistence, recovery), Cluster D (Coordinated network events).
- **Pass 3** — the four canonical scenarios revisited at full depth, plus five edge cases that span clusters.

Each entry's `id` is the lesson filename slug under `tutor/lessons/<id>.md`. The `status` field is a reviewer aid — the authoring driver uses file existence as the source of truth for which lesson to author next.

This file is human-editable between authoring runs. Reorder, rename, split, or merge entries as the codebase and the curriculum evolve; the next run will pick up the first entry whose target file does not yet exist on disk.

## Machine-readable manifest

```yaml
entries:
  # Pass 1 — orientation scenarios
  - id: pass1-01-tx-to-consensus
    index: 1
    pass: 1
    cluster: pass1
    title: Transaction to consensus
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/event-intake.md
      - architecture/topics/gossip.md
      - architecture/topics/hashgraph.md
      - architecture/topics/signed-state-management.md
    status: drafted
  - id: pass1-02-node-falls-behind
    index: 2
    pass: 1
    cluster: pass1
    title: Node falls behind and reconnects
    source_topics:
      - architecture/topics/gossip.md
      - architecture/topics/reconnect.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/event-intake.md
    status: drafted
  - id: pass1-03-coordinated-upgrade
    index: 3
    pass: 1
    cluster: pass1
    title: Coordinated network upgrade
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
    status: drafted
  - id: pass1-04-event-creation-under-stress
    index: 4
    pass: 1
    cluster: pass1
    title: Event creation under stress
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/event-intake.md
      - architecture/topics/gossip.md
    status: drafted

  # Cluster 0 — Wiring Framework Foundation
  - id: c0-01-task-schedulers-and-queues
    index: 5
    pass: 2
    cluster: c0
    title: Task schedulers and queues
    source_topics:
      - architecture/topics/wiring-framework.md
    status: drafted
  - id: c0-02-soldering-and-wires
    index: 6
    pass: 2
    cluster: c0
    title: Soldering, input wires, output wires
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started
  - id: c0-03-backpressure-and-health-monitor-mechanics
    index: 7
    pass: 2
    cluster: c0
    title: Wire-level backpressure and health-monitor mechanics
    source_topics:
      - architecture/topics/wiring-framework.md
      - architecture/topics/health-monitor-and-backpressure.md
    status: not_started
  - id: c0-04-graph-lifecycle-flush-squelch
    index: 8
    pass: 2
    cluster: c0
    title: Graph lifecycle, flush, squelching, deterministic mode, exception handling
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started
  - id: c0-syn-wiring-foundation-synthesis
    index: 9
    pass: 2
    cluster: c0
    title: Synthesis — a wired component from build to backpressure
    source_topics:
      - architecture/topics/wiring-framework.md
      - architecture/topics/event-intake.md
    status: not_started

  # Cluster A.1 — Hashgraph algorithm
  - id: a1-01-hashgraph-dag
    index: 10
    pass: 2
    cluster: a1
    title: The hashgraph DAG
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started
  - id: a1-02-rounds-and-witnesses
    index: 11
    pass: 2
    cluster: a1
    title: Rounds, round-created, round-received, witnesses
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started
  - id: a1-03-strongly-seeing-and-branching
    index: 12
    pass: 2
    cluster: a1
    title: Strongly-seeing and Byzantine branching tolerance
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started
  - id: a1-04-fame-voting
    index: 13
    pass: 2
    cluster: a1
    title: Fame voting and coin rounds
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started
  - id: a1-05-judges-and-consensus-order
    index: 14
    pass: 2
    cluster: a1
    title: Judges, consensus order, ConsensusSorter
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started
  - id: a1-06-birth-round
    index: 15
    pass: 2
    cluster: a1
    title: Birth round, future-event buffering
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started
  - id: a1-07-ancient-stale-lifecycle
    index: 16
    pass: 2
    cluster: a1
    title: Ancient threshold, expired threshold, stale events
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started
  - id: a1-syn-hashgraph-synthesis
    index: 17
    pass: 2
    cluster: a1
    title: Synthesis — a branched creator from arrival to judge merge
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started

  # Cluster A.2 — Event intake
  - id: a2-01-hashing-and-internal-validation
    index: 18
    pass: 2
    cluster: a2
    title: Hashing and internal validation
    source_topics:
      - architecture/topics/event-intake.md
    status: not_started
  - id: a2-02-deduplication-and-signature
    index: 19
    pass: 2
    cluster: a2
    title: Deduplication and signature verification (stage-ordering optimization)
    source_topics:
      - architecture/topics/event-intake.md
    status: not_started
  - id: a2-03-orphan-buffer
    index: 20
    pass: 2
    cluster: a2
    title: The orphan buffer and topological ordering
    source_topics:
      - architecture/topics/event-intake.md
    status: not_started
  - id: a2-04-birth-round-gating
    index: 21
    pass: 2
    cluster: a2
    title: Birth-round filtering at intake stages
    source_topics:
      - architecture/topics/event-intake.md
      - architecture/topics/hashgraph.md
    status: not_started
  - id: a2-05-durability-handoff
    index: 22
    pass: 2
    cluster: a2
    title: Durability handoff — intake to PCES writer to consensus, gossip, event creator
    source_topics:
      - architecture/topics/event-intake.md
      - architecture/topics/restart-and-pces.md
    status: not_started
  - id: a2-syn-intake-synthesis
    index: 23
    pass: 2
    cluster: a2
    title: Synthesis — a peer event through the full intake pipeline
    source_topics:
      - architecture/topics/event-intake.md
    status: not_started

  # Cluster A.3 — Gossip
  - id: a3-01-protocol-stack
    index: 24
    pass: 2
    cluster: a3
    title: Protocol stack — heartbeat, RPC, reconnect on one connection
    source_topics:
      - architecture/topics/gossip.md
    status: not_started
  - id: a3-02-rpc-pipeline
    index: 25
    pass: 2
    cluster: a3
    title: RPC pipeline — reader/dispatch/writer threads and per-peer state machine
    source_topics:
      - architecture/topics/gossip.md
    status: not_started
  - id: a3-03-three-phase-sync
    index: 26
    pass: 2
    cluster: a3
    title: Three-phase sync — window/tip exchange, known-tips, send-list traversal
    source_topics:
      - architecture/topics/gossip.md
    status: not_started
  - id: a3-04-simple-broadcast
    index: 27
    pass: 2
    cluster: a3
    title: Simple broadcast and the broadcast-vs-sync interaction
    source_topics:
      - architecture/topics/gossip.md
    status: not_started
  - id: a3-05-fair-sync-selector
    index: 28
    pass: 2
    cluster: a3
    title: Fair sync selector and per-peer fairness limits
    source_topics:
      - architecture/topics/gossip.md
    status: not_started
  - id: a3-syn-gossip-synthesis
    index: 29
    pass: 2
    cluster: a3
    title: Synthesis — a sync exchange end-to-end with overlap and overload
    source_topics:
      - architecture/topics/gossip.md
    status: not_started

  # Cluster A.4 — Event creator
  - id: a4-01-tipset-and-advancement-score
    index: 30
    pass: 2
    cluster: a4
    title: Tipsets and the advancement score
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started
  - id: a4-02-snapshot-update
    index: 31
    pass: 2
    cluster: a4
    title: Snapshot history and the super-majority update rule
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started
  - id: a4-03-event-creation-rule
    index: 32
    pass: 2
    cluster: a4
    title: The event-creation rule and parent selection
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started
  - id: a4-04-selfishness-and-pity-pick
    index: 33
    pass: 2
    cluster: a4
    title: Selfishness score and the anti-selfishness pity pick
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started
  - id: a4-05-creation-gates
    index: 34
    pass: 2
    cluster: a4
    title: Creation gates — rate, health, platform status, sync lag, quiescence
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/health-monitor-and-backpressure.md
    status: not_started
  - id: a4-syn-event-creator-synthesis
    index: 35
    pass: 2
    cluster: a4
    title: Synthesis — a self-event from registerEvent to maybeCreateEvent
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started

  # Cluster A.5 — Steady-state synthesis (entire cluster is synthesis)
  - id: a5-syn-01-peer-event-end-to-end
    index: 36
    pass: 2
    cluster: a5-syn
    title: Worked example — a peer event from gossip arrival to consensus
    source_topics:
      - architecture/topics/gossip.md
      - architecture/topics/event-intake.md
      - architecture/topics/hashgraph.md
      - architecture/topics/event-creator.md
    status: not_started
  - id: a5-syn-02-self-event-end-to-end
    index: 37
    pass: 2
    cluster: a5-syn
    title: Worked example — a self-event from creation through gossip
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/event-intake.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/gossip.md
      - architecture/topics/hashgraph.md
    status: not_started
  - id: a5-syn-03-steady-state-collaboration
    index: 38
    pass: 2
    cluster: a5-syn
    title: Synthesis — steady-state collaboration of all four sub-clusters
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/event-intake.md
      - architecture/topics/gossip.md
      - architecture/topics/hashgraph.md
    status: not_started

  # Cluster B — Stress, health, self-throttling
  - id: b-01-health-signal-and-consumers
    index: 39
    pass: 2
    cluster: b
    title: The unhealthy-duration signal and its uncoordinated consumers
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/wiring-framework.md
    status: not_started
  - id: b-02-event-creator-throttle
    index: 40
    pass: 2
    cluster: b
    title: Event-creation throttling via PlatformHealthRule
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/event-creator.md
    status: not_started
  - id: b-03-gossip-permit-throttle
    index: 41
    pass: 2
    cluster: b
    title: Gossip permits — grace, revoke, return, send-only mode
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/gossip.md
    status: not_started
  - id: b-04-tx-and-pces-replay-reactions
    index: 42
    pass: 2
    cluster: b
    title: Transaction-acceptance gate and PCES-replay throttling
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/restart-and-pces.md
    status: not_started
  - id: b-05-reasons-not-to-gossip
    index: 43
    pass: 2
    cluster: b
    title: Reasons not to gossip — the categorical-guard catalog
    source_topics:
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/gossip.md
    status: not_started
  - id: b-syn-stress-response-synthesis
    index: 44
    pass: 2
    cluster: b
    title: Synthesis — stress builds, reactions cascade, gossip rules engage
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
    status: not_started

  # Cluster C — State, persistence, recovery
  - id: c-01-signed-state-lifecycle-and-reservations
    index: 45
    pass: 2
    cluster: c
    title: Signed-state lifecycle and reservation discipline
    source_topics:
      - architecture/topics/signed-state-management.md
    status: not_started
  - id: c-02-on-disk-layout
    index: 46
    pass: 2
    cluster: c
    title: On-disk layout — round directory, executeAndRename, hard links
    source_topics:
      - architecture/topics/signed-state-management.md
    status: not_started
  - id: c-03-inline-pces-write-path
    index: 47
    pass: 2
    cluster: c
    title: Inline PCES write path and the persist-before-gossip invariant
    source_topics:
      - architecture/topics/restart-and-pces.md
      - architecture/topics/event-intake.md
    status: not_started
  - id: c-04-restart-and-pces-replay
    index: 48
    pass: 2
    cluster: c
    title: Restart sequence and PCES replay through the intake pipeline
    source_topics:
      - architecture/topics/restart-and-pces.md
      - architecture/topics/event-intake.md
    status: not_started
  - id: c-05-iss-disaster-recovery
    index: 49
    pass: 2
    cluster: c
    title: Offline ISS disaster-recovery procedure
    source_topics:
      - architecture/topics/restart-and-pces.md
      - architecture/topics/signed-state-management.md
    status: not_started
  - id: c-06-fallen-behind-detection
    index: 50
    pass: 2
    cluster: c
    title: FallenBehindMonitor and the fallen-behind threshold
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/gossip.md
    status: not_started
  - id: c-07-reconnect-state-transfer
    index: 51
    pass: 2
    cluster: c
    title: Learner/teacher protocol and state transfer
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/signed-state-management.md
    status: not_started
  - id: c-08-post-reconnect-resumption
    index: 52
    pass: 2
    cluster: c
    title: Post-reconnect resumption — PlatformCoordinator pause, swap, resume
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/event-intake.md
    status: not_started
  - id: c-syn-recovery-synthesis
    index: 53
    pass: 2
    cluster: c
    title: Synthesis — when each recovery path applies and how they compose
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/signed-state-management.md
    status: not_started

  # Cluster D — Coordinated network events (freeze and upgrade)
  - id: d-01-freeze-trigger-and-platform-state
    index: 54
    pass: 2
    cluster: d
    title: Freeze trigger and the freezeTime / isInFreezePeriod contract
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
    status: not_started
  - id: d-02-freeze-procedure
    index: 55
    pass: 2
    cluster: d
    title: Freeze procedure — FreezeRoundController and status transitions
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/hashgraph.md
    status: not_started
  - id: d-03-freeze-state-save
    index: 56
    pass: 2
    cluster: d
    title: Freeze-state save and SavedStateMetadata.freezeState
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/signed-state-management.md
    status: not_started
  - id: d-04-upgrade-startup
    index: 57
    pass: 2
    cluster: d
    title: Upgrade startup — boot path and PCES-replay interaction
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/restart-and-pces.md
    status: not_started
  - id: d-syn-freeze-upgrade-synthesis
    index: 58
    pass: 2
    cluster: d
    title: Synthesis — a full freeze-to-upgrade-restart cycle
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/gossip.md
      - architecture/topics/event-creator.md
    status: not_started

  # Pass 3 — canonical scenarios at full depth
  - id: pass3-01-tx-to-consensus-deep
    index: 59
    pass: 3
    cluster: pass3-canonical
    title: Transaction to consensus — full depth
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/event-intake.md
      - architecture/topics/gossip.md
      - architecture/topics/hashgraph.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/restart-and-pces.md
    status: not_started
  - id: pass3-02-node-falls-behind-deep
    index: 60
    pass: 3
    cluster: pass3-canonical
    title: Node falls behind and reconnects — full depth
    source_topics:
      - architecture/topics/gossip.md
      - architecture/topics/reconnect.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/event-intake.md
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/reasons-not-to-gossip.md
    status: not_started
  - id: pass3-03-coordinated-upgrade-deep
    index: 61
    pass: 3
    cluster: pass3-canonical
    title: Coordinated network upgrade — full depth
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
      - architecture/topics/hashgraph.md
    status: not_started
  - id: pass3-04-event-creation-under-stress-deep
    index: 62
    pass: 3
    cluster: pass3-canonical
    title: Event creation under stress — full depth
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/event-intake.md
      - architecture/topics/gossip.md
      - architecture/topics/reasons-not-to-gossip.md
    status: not_started

  # Pass 3 — cross-cluster edge cases
  - id: pass3-edge-01-reconnect-during-freeze
    index: 63
    pass: 3
    cluster: pass3-edge
    title: Reconnect during a freeze
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/gossip.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/reasons-not-to-gossip.md
    status: not_started
  - id: pass3-edge-02-fall-behind-during-heavy-gossip
    index: 64
    pass: 3
    cluster: pass3-edge
    title: Fall-behind triggered by Health Monitor during heavy gossip
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/gossip.md
      - architecture/topics/reconnect.md
      - architecture/topics/reasons-not-to-gossip.md
    status: not_started
  - id: pass3-edge-03-roster-change-during-reconnect
    index: 65
    pass: 3
    cluster: pass3-edge
    title: Roster change during reconnect
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/hashgraph.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/event-intake.md
    status: not_started
  - id: pass3-edge-04-pces-replay-after-upgrade
    index: 66
    pass: 3
    cluster: pass3-edge
    title: PCES replay after upgrade
    source_topics:
      - architecture/topics/restart-and-pces.md
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/event-intake.md
      - architecture/topics/hashgraph.md
    status: not_started
  - id: pass3-edge-05-squelching-during-freeze
    index: 67
    pass: 3
    cluster: pass3-edge
    title: Squelching during a freeze
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/gossip.md
      - architecture/topics/reconnect.md
    status: not_started
```

## Human-readable rendering

### Pass 1 — Orientation scenarios (4)

Lightweight role-level walks that plant a complete-but-low-fidelity mental sketch. Each scenario walks through several components naming what they do without going deep on mechanism.

1. `pass1-01-tx-to-consensus` — Transaction to consensus.
2. `pass1-02-node-falls-behind` — Node falls behind and reconnects.
3. `pass1-03-coordinated-upgrade` — Coordinated network upgrade.
4. `pass1-04-event-creation-under-stress` — Event creation under stress.

### Cluster 0 — Wiring Framework Foundation (5)

Substrate underneath everything else. Deep enough to support the rest of the curriculum, no further. Health Monitor *mechanics* live here; its role in stress response is in Cluster B.

5. `c0-01-task-schedulers-and-queues` — Task schedulers and queues.
6. `c0-02-soldering-and-wires` — Soldering, input wires, output wires.
7. `c0-03-backpressure-and-health-monitor-mechanics` — Wire-level backpressure and health-monitor mechanics.
8. `c0-04-graph-lifecycle-flush-squelch` — Graph lifecycle, flush, squelching, deterministic mode, exception handling.
9. `c0-syn-wiring-foundation-synthesis` — Synthesis: a wired component from build to backpressure.

### Cluster A.1 — Hashgraph algorithm (8)

Concepts in prerequisite order: DAG → rounds and witnesses → strongly-seeing (with branching tolerance) → fame voting (with coin rounds) → judges and consensus order → birth round → ancient/expired/stale.

10. `a1-01-hashgraph-dag` — The hashgraph DAG.
11. `a1-02-rounds-and-witnesses` — Rounds, round-created, round-received, witnesses.
12. `a1-03-strongly-seeing-and-branching` — Strongly-seeing and Byzantine branching tolerance.
13. `a1-04-fame-voting` — Fame voting and coin rounds.
14. `a1-05-judges-and-consensus-order` — Judges, consensus order, ConsensusSorter.
15. `a1-06-birth-round` — Birth round, future-event buffering.
16. `a1-07-ancient-stale-lifecycle` — Ancient threshold, expired threshold, stale events.
17. `a1-syn-hashgraph-synthesis` — Synthesis: a branched creator from arrival to judge merge.

### Cluster A.2 — Event intake (6)

The validation pipeline, the orphan buffer (now purely topological), birth-round gating at every stage, and the durability handoff into PCES.

18. `a2-01-hashing-and-internal-validation` — Hashing and internal validation.
19. `a2-02-deduplication-and-signature` — Deduplication and signature verification (stage-ordering optimization).
20. `a2-03-orphan-buffer` — The orphan buffer and topological ordering.
21. `a2-04-birth-round-gating` — Birth-round filtering at intake stages.
22. `a2-05-durability-handoff` — Durability handoff: intake → PCES writer → consensus, gossip, event creator.
23. `a2-syn-intake-synthesis` — Synthesis: a peer event through the full intake pipeline.

### Cluster A.3 — Gossip (6)

The protocol stack on a shared connection, the RPC pipeline, the three-phase sync, simple broadcast, and the fair sync selector.

24. `a3-01-protocol-stack` — Protocol stack: heartbeat, RPC, reconnect on one connection.
25. `a3-02-rpc-pipeline` — RPC pipeline: reader/dispatch/writer threads and per-peer state machine.
26. `a3-03-three-phase-sync` — Three-phase sync: window/tip exchange, known-tips, send-list traversal.
27. `a3-04-simple-broadcast` — Simple broadcast and the broadcast-vs-sync interaction.
28. `a3-05-fair-sync-selector` — Fair sync selector and per-peer fairness limits.
29. `a3-syn-gossip-synthesis` — Synthesis: a sync exchange end-to-end with overlap and overload.

### Cluster A.4 — Event creator (6)

The tipset algorithm, the snapshot baseline, the create-or-skip rule, anti-selfishness, and the orchestration rule chain.

30. `a4-01-tipset-and-advancement-score` — Tipsets and the advancement score.
31. `a4-02-snapshot-update` — Snapshot history and the super-majority update rule.
32. `a4-03-event-creation-rule` — The event-creation rule and parent selection.
33. `a4-04-selfishness-and-pity-pick` — Selfishness score and the anti-selfishness pity pick.
34. `a4-05-creation-gates` — Creation gates: rate, health, platform status, sync lag, quiescence.
35. `a4-syn-event-creator-synthesis` — Synthesis: a self-event from `registerEvent` to `maybeCreateEvent`.

### Cluster A.5 — Steady-state synthesis (3)

The whole cluster is synthesis. Two worked examples plus a combined integration lesson; this is where the four sub-clusters of A live together as a single mechanism.

36. `a5-syn-01-peer-event-end-to-end` — Worked example: a peer event from gossip arrival to consensus.
37. `a5-syn-02-self-event-end-to-end` — Worked example: a self-event from creation through gossip.
38. `a5-syn-03-steady-state-collaboration` — Synthesis: steady-state collaboration of all four sub-clusters.

### Cluster B — Stress, health, self-throttling (6)

The detector publishes one signal; each reaction site reads it independently. "Reasons not to gossip" is the cross-cutting categorical-guard catalog. Cluster A is a prerequisite.

39. `b-01-health-signal-and-consumers` — The unhealthy-duration signal and its uncoordinated consumers.
40. `b-02-event-creator-throttle` — Event-creation throttling via `PlatformHealthRule`.
41. `b-03-gossip-permit-throttle` — Gossip permits: grace, revoke, return, send-only mode.
42. `b-04-tx-and-pces-replay-reactions` — Transaction-acceptance gate and PCES-replay throttling.
43. `b-05-reasons-not-to-gossip` — Reasons not to gossip: the categorical-guard catalog.
44. `b-syn-stress-response-synthesis` — Synthesis: stress builds, reactions cascade, gossip rules engage.

### Cluster C — State, persistence, recovery (9)

Signed-state lifecycle, PCES write-path and replay, and reconnect. Tight cross-references across these three topics — A and B are prerequisites.

45. `c-01-signed-state-lifecycle-and-reservations` — Signed-state lifecycle and reservation discipline.
46. `c-02-on-disk-layout` — On-disk layout: round directory, `executeAndRename`, hard links.
47. `c-03-inline-pces-write-path` — Inline PCES write path and the persist-before-gossip invariant.
48. `c-04-restart-and-pces-replay` — Restart sequence and PCES replay through the intake pipeline.
49. `c-05-iss-disaster-recovery` — Offline ISS disaster-recovery procedure.
50. `c-06-fallen-behind-detection` — `FallenBehindMonitor` and the fallen-behind threshold.
51. `c-07-reconnect-state-transfer` — Learner/teacher protocol and state transfer.
52. `c-08-post-reconnect-resumption` — Post-reconnect resumption: `PlatformCoordinator` pause, swap, resume.
53. `c-syn-recovery-synthesis` — Synthesis: when each recovery path applies and how they compose.

### Cluster D — Coordinated network events (5)

Freeze and upgrade. Multi-node coordination that touches every prior cluster — taught last.

54. `d-01-freeze-trigger-and-platform-state` — Freeze trigger and the `freezeTime` / `isInFreezePeriod` contract.
55. `d-02-freeze-procedure` — Freeze procedure: `FreezeRoundController` and status transitions.
56. `d-03-freeze-state-save` — Freeze-state save and `SavedStateMetadata.freezeState`.
57. `d-04-upgrade-startup` — Upgrade startup: boot path and PCES-replay interaction.
58. `d-syn-freeze-upgrade-synthesis` — Synthesis: a full freeze-to-upgrade-restart cycle.

### Pass 3 — Canonical scenarios at full depth (4)

Each Pass 1 scenario revisited with the mechanism the Pass 2 clusters established.

59. `pass3-01-tx-to-consensus-deep` — Transaction to consensus, full depth.
60. `pass3-02-node-falls-behind-deep` — Node falls behind and reconnects, full depth.
61. `pass3-03-coordinated-upgrade-deep` — Coordinated network upgrade, full depth.
62. `pass3-04-event-creation-under-stress-deep` — Event creation under stress, full depth.

### Pass 3 — Cross-cluster edge cases (5)

The five edge cases named in the brief. Each spans multiple clusters and exercises invariants that no single Pass 2 lesson can teach in isolation.

63. `pass3-edge-01-reconnect-during-freeze` — Reconnect during a freeze.
64. `pass3-edge-02-fall-behind-during-heavy-gossip` — Fall-behind triggered by Health Monitor during heavy gossip.
65. `pass3-edge-03-roster-change-during-reconnect` — Roster change during reconnect.
66. `pass3-edge-04-pces-replay-after-upgrade` — PCES replay after upgrade.
67. `pass3-edge-05-squelching-during-freeze` — Squelching during a freeze.

## Authoring notes from the bootstrap run

A few notes for the reviewer and for downstream authoring runs:

- **Glossary path.** The meta-prompt names `platform-sdk/docs/consensus-layer/glossary.md`; the canonical glossary actually lives at `platform-sdk/docs/hashgraphGlossary.md` per the KB layout README. The bootstrap resolved the path silently. Future Pass 2 and Pass 3 authoring runs should use the real path; consider editing `prompts/lesson-authoring.md` to reflect this.
- **`invariants.md` is pending.** Listed in the KB layout README as "pending". The bootstrap did not need it to scope clusters; subsequent Pass 2 authoring runs will, and will write `[TBD]` markers in lesson frontmatter `kb_invariants` and in lesson bodies wherever an INV-NNN claim is needed.
- **`delta-map/` per-topic files are not yet present.** Only the README is in the directory. The delta callouts in Pass 2 and Pass 3 lessons will need to either point at the per-topic delta-map entries once they exist, or write `[TBD: delta-map/<topic>.md not yet present]` in the interim.
- **`scenarios/` SCN-NNN entries do not yet exist.** Pass 3 edge cases will note the scenario seeds the topic files already flag (orphan-buffer growth, reconnect failure modes, freeze-time anomalies, ISS recovery) and `[TBD]` the SCN identifiers until the catalog populates.
- **ADRs.** Two are in the repo today (`ADR-001-pces-state-snapshot-coordination`, `ADR-002-execution-freeze-signature-handoff`). Pass 2 lessons that touch PCES write coordination (`c-03`, `c-04`) and freeze signature handoff (`d-02`, `d-03`) should link these by ID rather than restating the rationale.
- **Cluster A.5 cluster id.** Treated as `a5-syn` (per the meta-prompt's example list) because the entire cluster is synthesis; individual lesson IDs use the `a5-syn-NN-…` form.
- **Length.** 67 entries total — within the brief's estimated 47–73 hours of curriculum. Reviewers should feel free to split or merge entries before the next authoring run picks them up; in particular Cluster A.1 (8 entries) and Cluster C (9 entries) are the densest clusters and could be tightened if the synthesis lessons absorb material from earlier sub-lessons.
