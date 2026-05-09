# Consensus Layer Tutor — Curriculum manifest

This file is the source of truth for the Consensus Layer Tutor curriculum.
It enumerates every lesson the curriculum will eventually contain, in the
order learners encounter them, and tracks which ones have been drafted.

The pass-and-cluster ordering is fixed:

1. Pass 1 — four canonical scenarios at lightweight depth (transaction
   reaches consensus; node falls behind; coordinated network upgrade;
   event creation under stress).
2. Pass 2 clusters in order — Cluster 0 (Wiring Framework Foundation),
   then A.1 (Hashgraph Algorithm), A.2 (Event Intake), A.3 (Gossip),
   A.4 (Event Creator), A.5 (Steady-state synthesis), B (Stress, health,
   self-throttling), C (State, persistence, recovery), D (Coordinated
   network events).
3. Pass 3 — the four Pass 1 canonicals revisited at full depth, then
   five edge-case scenarios.

Sub-lesson breakdown within each Pass 2 cluster reflects concept density
in the KB at bootstrap time. A.1 is concept-densest and yields the most
sub-lessons. Each cluster ends with a synthesis lesson that walks the
cluster's components collaborating, not just describing each in turn.
Prerequisites are advisory — the natural order in this manifest already
encodes the dependency graph.

This file is human-editable between authoring runs. The lesson-authoring
prompt re-reads it on every invocation and authors the first entry whose
`status` is `planned` and whose lesson file does not yet exist.

The bootstrap was generated against `origin/main` at the SHA recorded in
`generated_against` below; lesson files record their own per-lesson
verification SHAs in `last_verified_against` frontmatter.

---
curriculum_version: 1
generated_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---
- lesson_id: pass1-1-transaction-to-consensus
  cluster: pass1
  title: Transaction to consensus
  scenario_kind: canonical
  prerequisites: []
  status: drafted
- lesson_id: pass1-2-node-falls-behind
  cluster: pass1
  title: Node falls behind
  scenario_kind: canonical
  prerequisites: []
  status: planned
- lesson_id: pass1-3-coordinated-network-upgrade
  cluster: pass1
  title: Coordinated network upgrade
  scenario_kind: canonical
  prerequisites: []
  status: planned
- lesson_id: pass1-4-event-creation-under-stress
  cluster: pass1
  title: Event creation under stress
  scenario_kind: canonical
  prerequisites: []
  status: planned
- lesson_id: 0-1-task-schedulers
  cluster: '0'
  title: Task schedulers
  source_topic_id: wiring-framework
  prerequisites: [pass1-4-event-creation-under-stress]
  status: planned
- lesson_id: 0-2-wires-and-soldering
  cluster: '0'
  title: Wires and soldering
  source_topic_id: wiring-framework
  prerequisites: [0-1-task-schedulers]
  status: planned
- lesson_id: 0-3-backpressure-at-the-wire
  cluster: '0'
  title: Backpressure at the wire
  source_topic_id: wiring-framework
  prerequisites: [0-2-wires-and-soldering]
  status: planned
- lesson_id: 0-4-wiring-runtime-synthesis
  cluster: '0'
  title: Wiring runtime synthesis
  source_topic_id: wiring-framework
  prerequisites: [0-3-backpressure-at-the-wire]
  status: planned
- lesson_id: A.1-1-hashgraph-dag
  cluster: A.1
  title: The hashgraph DAG
  source_topic_id: hashgraph
  prerequisites: [0-4-wiring-runtime-synthesis]
  status: planned
- lesson_id: A.1-2-rounds-and-witnesses
  cluster: A.1
  title: Rounds and witnesses
  source_topic_id: hashgraph
  prerequisites: [A.1-1-hashgraph-dag]
  status: planned
- lesson_id: A.1-3-strongly-seeing
  cluster: A.1
  title: Strongly-seeing
  source_topic_id: hashgraph
  prerequisites: [A.1-2-rounds-and-witnesses]
  status: planned
- lesson_id: A.1-4-voting-and-coin-rounds
  cluster: A.1
  title: Voting and coin rounds
  source_topic_id: hashgraph
  prerequisites: [A.1-3-strongly-seeing]
  status: planned
- lesson_id: A.1-5-judges
  cluster: A.1
  title: Judges
  source_topic_id: hashgraph
  prerequisites: [A.1-4-voting-and-coin-rounds]
  status: planned
- lesson_id: A.1-6-event-lifecycle-and-birth-round
  cluster: A.1
  title: Event lifecycle and birth round
  source_topic_id: hashgraph
  prerequisites: [A.1-5-judges]
  status: planned
- lesson_id: A.1-7-stale-events
  cluster: A.1
  title: Stale events
  source_topic_id: hashgraph
  prerequisites: [A.1-6-event-lifecycle-and-birth-round]
  status: planned
- lesson_id: A.1-8-hashgraph-algorithm-synthesis
  cluster: A.1
  title: Hashgraph algorithm synthesis
  source_topic_id: hashgraph
  prerequisites: [A.1-7-stale-events]
  status: planned
- lesson_id: A.2-1-validation-pipeline
  cluster: A.2
  title: The validation pipeline
  source_topic_id: event-intake
  prerequisites: [A.1-8-hashgraph-algorithm-synthesis]
  status: planned
- lesson_id: A.2-2-orphan-buffer
  cluster: A.2
  title: The orphan buffer
  source_topic_id: event-intake
  prerequisites: [A.2-1-validation-pipeline]
  status: planned
- lesson_id: A.2-3-birth-round-filtering
  cluster: A.2
  title: Birth-round filtering
  source_topic_id: event-intake
  prerequisites: [A.2-2-orphan-buffer]
  status: planned
- lesson_id: A.2-4-durability-and-handoff
  cluster: A.2
  title: Durability and handoff
  source_topic_id: event-intake
  prerequisites: [A.2-3-birth-round-filtering]
  status: planned
- lesson_id: A.2-5-event-intake-synthesis
  cluster: A.2
  title: Event intake synthesis
  source_topic_id: event-intake
  prerequisites: [A.2-4-durability-and-handoff]
  status: planned
- lesson_id: A.3-1-protocol-stack-and-rpc-sync
  cluster: A.3
  title: Protocol stack and RPC sync
  source_topic_id: gossip
  prerequisites: [A.2-5-event-intake-synthesis]
  status: planned
- lesson_id: A.3-2-three-phase-sync-protocol
  cluster: A.3
  title: The three-phase sync protocol
  source_topic_id: gossip
  prerequisites: [A.3-1-protocol-stack-and-rpc-sync]
  status: planned
- lesson_id: A.3-3-simple-broadcast
  cluster: A.3
  title: Simple broadcast
  source_topic_id: gossip
  prerequisites: [A.3-2-three-phase-sync-protocol]
  status: planned
- lesson_id: A.3-4-fair-sync-selector
  cluster: A.3
  title: The fair sync selector
  source_topic_id: gossip
  prerequisites: [A.3-3-simple-broadcast]
  status: planned
- lesson_id: A.3-5-gossip-synthesis
  cluster: A.3
  title: Gossip synthesis
  source_topic_id: gossip
  prerequisites: [A.3-4-fair-sync-selector]
  status: planned
- lesson_id: A.4-1-tipset-algorithm
  cluster: A.4
  title: The tipset algorithm
  source_topic_id: event-creator
  prerequisites: [A.3-5-gossip-synthesis]
  status: planned
- lesson_id: A.4-2-event-creation-cadence
  cluster: A.4
  title: Event creation cadence
  source_topic_id: event-creator
  prerequisites: [A.4-1-tipset-algorithm]
  status: planned
- lesson_id: A.4-3-self-event-persistence
  cluster: A.4
  title: Self-event persistence
  source_topic_id: event-creator
  prerequisites: [A.4-2-event-creation-cadence]
  status: planned
- lesson_id: A.4-4-event-creator-synthesis
  cluster: A.4
  title: Event creator synthesis
  source_topic_id: event-creator
  prerequisites: [A.4-3-self-event-persistence]
  status: planned
- lesson_id: A.5-1-steady-state-synthesis
  cluster: A.5
  title: Steady-state synthesis
  prerequisites: [A.4-4-event-creator-synthesis]
  status: planned
- lesson_id: B-1-health-detection
  cluster: B
  title: Health detection
  source_topic_id: health-monitor-and-backpressure
  prerequisites: [A.5-1-steady-state-synthesis]
  status: planned
- lesson_id: B-2-unhealthy-reactions
  cluster: B
  title: Reactions to unhealthy state
  source_topic_id: health-monitor-and-backpressure
  prerequisites: [B-1-health-detection]
  status: planned
- lesson_id: B-3-reasons-not-to-gossip
  cluster: B
  title: Reasons not to gossip
  source_topic_id: reasons-not-to-gossip
  prerequisites: [B-2-unhealthy-reactions]
  status: planned
- lesson_id: B-4-stress-and-self-throttling-synthesis
  cluster: B
  title: Stress and self-throttling synthesis
  source_topic_id: reasons-not-to-gossip
  prerequisites: [B-3-reasons-not-to-gossip]
  status: planned
- lesson_id: C-1-signed-state-lifecycle
  cluster: C
  title: Signed-state lifecycle
  source_topic_id: signed-state-management
  prerequisites: [B-4-stress-and-self-throttling-synthesis]
  status: planned
- lesson_id: C-2-reservation-discipline-and-on-disk-layout
  cluster: C
  title: Reservation discipline and on-disk layout
  source_topic_id: signed-state-management
  prerequisites: [C-1-signed-state-lifecycle]
  status: planned
- lesson_id: C-3-inline-pces-write-path
  cluster: C
  title: Inline PCES write path
  source_topic_id: restart-and-pces
  prerequisites: [C-2-reservation-discipline-and-on-disk-layout]
  status: planned
- lesson_id: C-4-restart-and-replay
  cluster: C
  title: Restart and replay
  source_topic_id: restart-and-pces
  prerequisites: [C-3-inline-pces-write-path]
  status: planned
- lesson_id: C-5-reconnect-detection-and-protocol
  cluster: C
  title: Reconnect detection and protocol
  source_topic_id: reconnect
  prerequisites: [C-4-restart-and-replay]
  status: planned
- lesson_id: C-6-state-and-recovery-synthesis
  cluster: C
  title: State and recovery synthesis
  source_topic_id: reconnect
  prerequisites: [C-5-reconnect-detection-and-protocol]
  status: planned
- lesson_id: D-1-freeze-trigger-and-round-controller
  cluster: D
  title: Freeze trigger and round controller
  source_topic_id: freeze-and-upgrade
  prerequisites: [C-6-state-and-recovery-synthesis]
  status: planned
- lesson_id: D-2-freeze-procedure
  cluster: D
  title: Freeze procedure
  source_topic_id: freeze-and-upgrade
  prerequisites: [D-1-freeze-trigger-and-round-controller]
  status: planned
- lesson_id: D-3-upgrade-startup
  cluster: D
  title: Upgrade startup
  source_topic_id: freeze-and-upgrade
  prerequisites: [D-2-freeze-procedure]
  status: planned
- lesson_id: D-4-freeze-and-upgrade-synthesis
  cluster: D
  title: Freeze and upgrade synthesis
  source_topic_id: freeze-and-upgrade
  prerequisites: [D-3-upgrade-startup]
  status: planned
- lesson_id: pass3-1-transaction-to-consensus
  cluster: pass3
  title: Transaction to consensus (full depth)
  scenario_kind: canonical
  prerequisites: [A.5-1-steady-state-synthesis]
  status: planned
- lesson_id: pass3-2-node-falls-behind
  cluster: pass3
  title: Node falls behind (full depth)
  scenario_kind: canonical
  prerequisites: [C-6-state-and-recovery-synthesis]
  status: planned
- lesson_id: pass3-3-coordinated-network-upgrade
  cluster: pass3
  title: Coordinated network upgrade (full depth)
  scenario_kind: canonical
  prerequisites: [D-4-freeze-and-upgrade-synthesis]
  status: planned
- lesson_id: pass3-4-event-creation-under-stress
  cluster: pass3
  title: Event creation under stress (full depth)
  scenario_kind: canonical
  prerequisites: [B-4-stress-and-self-throttling-synthesis]
  status: planned
- lesson_id: pass3-5-reconnect-during-freeze
  cluster: pass3
  title: Reconnect during freeze
  scenario_kind: edge-case
  prerequisites: [C-6-state-and-recovery-synthesis, D-4-freeze-and-upgrade-synthesis]
  status: planned
- lesson_id: pass3-6-fall-behind-during-heavy-gossip
  cluster: pass3
  title: Fall-behind triggered by Health Monitor during heavy gossip
  scenario_kind: edge-case
  prerequisites: [B-4-stress-and-self-throttling-synthesis, C-6-state-and-recovery-synthesis]
  status: planned
- lesson_id: pass3-7-roster-change-during-reconnect
  cluster: pass3
  title: Roster change during reconnect
  scenario_kind: edge-case
  prerequisites: [C-6-state-and-recovery-synthesis]
  status: planned
- lesson_id: pass3-8-pces-replay-after-upgrade
  cluster: pass3
  title: PCES replay after upgrade
  scenario_kind: edge-case
  prerequisites: [C-6-state-and-recovery-synthesis, D-4-freeze-and-upgrade-synthesis]
  status: planned
- lesson_id: pass3-9-squelching-during-freeze
  cluster: pass3
  title: Squelching during freeze
  scenario_kind: edge-case
  prerequisites: [B-4-stress-and-self-throttling-synthesis, D-4-freeze-and-upgrade-synthesis]
  status: planned
