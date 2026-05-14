---
# Consensus Layer Tutor — Curriculum Manifest
#
# Machine-readable entry list, in global pedagogical order. The
# lesson-authoring prompt scans this list in `index` order and authors the
# first entry whose target file at `tutor/lessons/<id>.md` does not exist
# on disk. The `status` field is reviewer-editable metadata; file
# existence is the source of truth for state detection.
#
# This file is human-editable between authoring runs. A reviewer can
# rename, reorder, split, or merge entries before the next run picks up.
# After such edits, keep `index` contiguous and `id` filename-safe.
#
# Field reference:
#   id              — lesson identifier; filename will be tutor/lessons/<id>.md
#   index           — global ordering; pedagogical sequence
#   pass            — 1 (orientation), 2 (cluster deep-dive), or 3 (cross-cluster)
#   cluster         — pass1 | c0 | a1 | a2 | a3 | a4 | a5-syn | b | c | d
#                     | pass3-canonical | pass3-edge
#   title           — human-readable lesson title
#   source_topics   — topic files the lesson draws from, relative to
#                     `platform-sdk/docs/consensus-layer/architecture/topics/`
#   status          — not_started | drafted | reviewed
entries:
  # ─── Pass 1 — Orientation map ────────────────────────────────────────────
  - id: pass1-01-tx-to-consensus
    index: 1
    pass: 1
    cluster: pass1
    title: "Transaction to consensus — orientation walkthrough"
    source_topics:
      - event-creator.md
      - event-intake.md
      - hashgraph.md
      - gossip.md
    status: drafted

  - id: pass1-02-node-falls-behind
    index: 2
    pass: 1
    cluster: pass1
    title: "A node falls behind — orientation walkthrough"
    source_topics:
      - gossip.md
      - health-monitor-and-backpressure.md
      - reconnect.md
    status: drafted

  - id: pass1-03-coordinated-upgrade
    index: 3
    pass: 1
    cluster: pass1
    title: "Coordinated network upgrade — orientation walkthrough"
    source_topics:
      - freeze-and-upgrade.md
      - signed-state-management.md
      - restart-and-pces.md
    status: drafted

  - id: pass1-04-event-creation-under-stress
    index: 4
    pass: 1
    cluster: pass1
    title: "Event creation under stress — orientation walkthrough"
    source_topics:
      - event-creator.md
      - health-monitor-and-backpressure.md
      - gossip.md
    status: drafted

  # ─── Pass 2 — Cluster 0: Wiring Framework Foundation ────────────────────
  - id: c0-01-components-and-schedulers
    index: 5
    pass: 2
    cluster: c0
    title: "Components, schedulers, and queues"
    source_topics:
      - wiring-framework.md
    status: not_started

  - id: c0-02-wires-and-soldering
    index: 6
    pass: 2
    cluster: c0
    title: "Wires and soldering — composing the runtime"
    source_topics:
      - wiring-framework.md
    status: not_started

  - id: c0-03-backpressure-modes
    index: 7
    pass: 2
    cluster: c0
    title: "Hard and soft backpressure at the wire level"
    source_topics:
      - wiring-framework.md
    status: not_started

  - id: c0-04-health-monitor-mechanics
    index: 8
    pass: 2
    cluster: c0
    title: "Health Monitor mechanics in the wiring framework"
    source_topics:
      - wiring-framework.md
      - health-monitor-and-backpressure.md
    status: not_started

  - id: c0-05-determinism-and-exceptions
    index: 9
    pass: 2
    cluster: c0
    title: "Deterministic mode, exception handling, and the JVM anchor"
    source_topics:
      - wiring-framework.md
    status: not_started

  - id: c0-syn-wiring-synthesis
    index: 10
    pass: 2
    cluster: c0
    title: "Wiring framework synthesis — assembling a small subgraph end to end"
    source_topics:
      - wiring-framework.md
    status: not_started

  # ─── Pass 2 — Cluster A.1: Hashgraph Algorithm ──────────────────────────
  - id: a1-01-hashgraph-dag
    index: 11
    pass: 2
    cluster: a1
    title: "Events, parents, and the hashgraph DAG"
    source_topics:
      - hashgraph.md
    status: not_started

  - id: a1-02-rounds-and-witnesses
    index: 12
    pass: 2
    cluster: a1
    title: "Rounds and witnesses"
    source_topics:
      - hashgraph.md
    status: not_started

  - id: a1-03-strongly-seeing
    index: 13
    pass: 2
    cluster: a1
    title: "Strongly seeing — the quorum-mediated visibility relation"
    source_topics:
      - hashgraph.md
    status: not_started

  - id: a1-04-judges
    index: 14
    pass: 2
    cluster: a1
    title: "Famous witnesses, voting, coin rounds, and judges"
    source_topics:
      - hashgraph.md
    status: not_started

  - id: a1-05-consensus-order
    index: 15
    pass: 2
    cluster: a1
    title: "Consensus order and timestamps"
    source_topics:
      - hashgraph.md
    status: not_started

  - id: a1-06-birth-round
    index: 16
    pass: 2
    cluster: a1
    title: "Birth round — the durable round-of-creation"
    source_topics:
      - hashgraph.md
    status: not_started

  - id: a1-07-ancient-and-stale
    index: 17
    pass: 2
    cluster: a1
    title: "Ancient threshold and stale events"
    source_topics:
      - hashgraph.md
    status: not_started

  - id: a1-syn-hashgraph-synthesis
    index: 18
    pass: 2
    cluster: a1
    title: "Hashgraph synthesis — from DAG to ordered consensus rounds"
    source_topics:
      - hashgraph.md
    status: not_started

  # ─── Pass 2 — Cluster A.2: Event Intake ─────────────────────────────────
  - id: a2-01-intake-overview-and-inputs
    index: 19
    pass: 2
    cluster: a2
    title: "Event Intake — inputs, outputs, and the pipeline shape"
    source_topics:
      - event-intake.md
    status: not_started

  - id: a2-02-validation-pipeline
    index: 20
    pass: 2
    cluster: a2
    title: "Validation pipeline — deduplication and signature verification"
    source_topics:
      - event-intake.md
    status: not_started

  - id: a2-03-orphan-buffer-and-topological-emission
    index: 21
    pass: 2
    cluster: a2
    title: "Orphan buffer and topological emission"
    source_topics:
      - event-intake.md
    status: not_started

  - id: a2-04-birth-round-filtering-and-branch-detection
    index: 22
    pass: 2
    cluster: a2
    title: "Birth-round filtering and branch detection"
    source_topics:
      - event-intake.md
    status: not_started

  - id: a2-05-pces-handoff-and-backpressure
    index: 23
    pass: 2
    cluster: a2
    title: "PCES handoff and backpressure interaction"
    source_topics:
      - event-intake.md
      - restart-and-pces.md
    status: not_started

  - id: a2-06-neighbor-discipline-reporting
    index: 24
    pass: 2
    cluster: a2
    title: "Neighbor-discipline reporting from Event Intake"
    source_topics:
      - event-intake.md
    status: not_started

  - id: a2-syn-intake-synthesis
    index: 25
    pass: 2
    cluster: a2
    title: "Event Intake synthesis — an event from the wire to the hashgraph"
    source_topics:
      - event-intake.md
    status: not_started

  # ─── Pass 2 — Cluster A.3: Gossip ───────────────────────────────────────
  - id: a3-01-protocol-stack-and-neighbors
    index: 26
    pass: 2
    cluster: a3
    title: "Gossip — protocol stack and neighbor selection"
    source_topics:
      - gossip.md
    status: not_started

  - id: a3-02-three-phase-sync
    index: 27
    pass: 2
    cluster: a3
    title: "The three-phase sync protocol"
    source_topics:
      - gossip.md
    status: not_started

  - id: a3-03-rpc-sync-and-simple-broadcast
    index: 28
    pass: 2
    cluster: a3
    title: "RPC sync and simple broadcast"
    source_topics:
      - gossip.md
    status: not_started

  - id: a3-04-fair-selector-and-buffer-management
    index: 29
    pass: 2
    cluster: a3
    title: "Fair sync selector, caching policy, and buffer management"
    source_topics:
      - gossip.md
    status: not_started

  - id: a3-05-falling-behind-and-roster
    index: 30
    pass: 2
    cluster: a3
    title: "Falling-behind detection and roster handling at gossip"
    source_topics:
      - gossip.md
    status: not_started

  - id: a3-syn-gossip-synthesis
    index: 31
    pass: 2
    cluster: a3
    title: "Gossip synthesis — a sync from neighbor selection to event delivery"
    source_topics:
      - gossip.md
    status: not_started

  # ─── Pass 2 — Cluster A.4: Event Creator ────────────────────────────────
  - id: a4-01-when-to-create
    index: 32
    pass: 2
    cluster: a4
    title: "When to create a self-event — cadence and max frequency"
    source_topics:
      - event-creator.md
    status: not_started

  - id: a4-02-tipset-other-parent-selection
    index: 33
    pass: 2
    cluster: a4
    title: "Tipset / Enhanced Other-Parent Selection"
    source_topics:
      - event-creator.md
    status: not_started

  - id: a4-03-filling-with-transactions
    index: 34
    pass: 2
    cluster: a4
    title: "Filling events with transactions from Execution"
    source_topics:
      - event-creator.md
    status: not_started

  - id: a4-04-vetoes-and-self-event-persistence
    index: 35
    pass: 2
    cluster: a4
    title: "Vetoes and self-event persistence"
    source_topics:
      - event-creator.md
    status: not_started

  - id: a4-syn-event-creator-synthesis
    index: 36
    pass: 2
    cluster: a4
    title: "Event Creator synthesis — from tipset choice to wire emission"
    source_topics:
      - event-creator.md
    status: not_started

  # ─── Pass 2 — Cluster A.5: Steady-state synthesis ───────────────────────
  - id: a5-01-steady-state-event-flow-trace
    index: 37
    pass: 2
    cluster: a5-syn
    title: "Steady-state event flow — a full trace across A.1–A.4"
    source_topics:
      - event-creator.md
      - event-intake.md
      - hashgraph.md
      - gossip.md
    status: not_started

  - id: a5-02-self-event-feedback-loop
    index: 38
    pass: 2
    cluster: a5-syn
    title: "The self-event feedback loop — creator to intake to hashgraph"
    source_topics:
      - event-creator.md
      - event-intake.md
      - hashgraph.md
    status: not_started

  - id: a5-syn-steady-state-synthesis
    index: 39
    pass: 2
    cluster: a5-syn
    title: "Cluster A synthesis — what lives in the cluster, not the parts"
    source_topics:
      - event-creator.md
      - event-intake.md
      - hashgraph.md
      - gossip.md
    status: not_started

  # ─── Pass 2 — Cluster B: Stress, health, and self-throttling ────────────
  - id: b-01-health-monitor-role
    index: 40
    pass: 2
    cluster: b
    title: "Health Monitor — role and downstream consequences"
    source_topics:
      - health-monitor-and-backpressure.md
    status: not_started

  - id: b-02-backpressure-pacing-and-throttling
    index: 41
    pass: 2
    cluster: b
    title: "Backpressure, Execution-Consensus pacing, and dynamic throttling"
    source_topics:
      - health-monitor-and-backpressure.md
    status: not_started

  - id: b-03-distinction-from-backpressure
    index: 42
    pass: 2
    cluster: b
    title: "Reasons-not-to-gossip vs. backpressure — distinct mechanisms"
    source_topics:
      - reasons-not-to-gossip.md
      - health-monitor-and-backpressure.md
    status: not_started

  - id: b-04-reasons-not-to-gossip-catalog
    index: 43
    pass: 2
    cluster: b
    title: "Catalog of reasons not to gossip"
    source_topics:
      - reasons-not-to-gossip.md
    status: not_started

  - id: b-syn-stress-response-synthesis
    index: 44
    pass: 2
    cluster: b
    title: "Stress-response synthesis — Health Monitor and self-throttling together"
    source_topics:
      - health-monitor-and-backpressure.md
      - reasons-not-to-gossip.md
    status: not_started

  # ─── Pass 2 — Cluster C: State, persistence, and recovery ───────────────
  - id: c-01-signed-state-creation-and-types
    index: 45
    pass: 2
    cluster: c
    title: "Signed state creation, runtime types, and round signing"
    source_topics:
      - signed-state-management.md
    status: not_started

  - id: c-02-reservation-discipline-and-on-disk
    index: 46
    pass: 2
    cluster: c
    title: "Reservation discipline, signed-state lifecycle, and on-disk layout"
    source_topics:
      - signed-state-management.md
    status: not_started

  - id: c-03-pces-write-and-replay
    index: 47
    pass: 2
    cluster: c
    title: "PCES — inline write path and restart replay"
    source_topics:
      - restart-and-pces.md
    status: not_started

  - id: c-04-fallen-behind-detection
    index: 48
    pass: 2
    cluster: c
    title: "Fallen-behind detection and the trigger to reconnect"
    source_topics:
      - reconnect.md
      - gossip.md
    status: not_started

  - id: c-05-reconnect-protocol
    index: 49
    pass: 2
    cluster: c
    title: "Reconnect — the learner/teacher protocol"
    source_topics:
      - reconnect.md
    status: not_started

  - id: c-06-post-reconnect-resumption
    index: 50
    pass: 2
    cluster: c
    title: "Post-reconnect resumption and boundary handoffs"
    source_topics:
      - reconnect.md
    status: not_started

  - id: c-syn-state-and-recovery-synthesis
    index: 51
    pass: 2
    cluster: c
    title: "State and recovery synthesis — durability, restart, and reconnect together"
    source_topics:
      - signed-state-management.md
      - restart-and-pces.md
      - reconnect.md
    status: not_started

  # ─── Pass 2 — Cluster D: Coordinated network events ─────────────────────
  - id: d-01-freeze-trigger-and-coordination
    index: 52
    pass: 2
    cluster: d
    title: "Freeze — trigger and network-wide coordination"
    source_topics:
      - freeze-and-upgrade.md
    status: not_started

  - id: d-02-freeze-time-behaviour
    index: 53
    pass: 2
    cluster: d
    title: "Freeze-time behaviour — what the node does (and doesn't do)"
    source_topics:
      - freeze-and-upgrade.md
    status: not_started

  - id: d-03-upgrade-startup
    index: 54
    pass: 2
    cluster: d
    title: "Upgrade startup — coming back up on the new version"
    source_topics:
      - freeze-and-upgrade.md
      - restart-and-pces.md
    status: not_started

  - id: d-syn-freeze-upgrade-synthesis
    index: 55
    pass: 2
    cluster: d
    title: "Freeze and upgrade synthesis — a coordinated network event end to end"
    source_topics:
      - freeze-and-upgrade.md
      - signed-state-management.md
      - restart-and-pces.md
    status: not_started

  # ─── Pass 3 — Canonical scenarios revisited at full depth ───────────────
  - id: pass3-01-tx-to-consensus-deep
    index: 56
    pass: 3
    cluster: pass3-canonical
    title: "Transaction to consensus — full-depth trace"
    source_topics:
      - event-creator.md
      - event-intake.md
      - hashgraph.md
      - gossip.md
    status: not_started

  - id: pass3-02-node-falls-behind-deep
    index: 57
    pass: 3
    cluster: pass3-canonical
    title: "A node falls behind — full-depth trace"
    source_topics:
      - gossip.md
      - health-monitor-and-backpressure.md
      - reasons-not-to-gossip.md
      - reconnect.md
    status: not_started

  - id: pass3-03-coordinated-upgrade-deep
    index: 58
    pass: 3
    cluster: pass3-canonical
    title: "Coordinated network upgrade — full-depth trace"
    source_topics:
      - freeze-and-upgrade.md
      - signed-state-management.md
      - restart-and-pces.md
    status: not_started

  - id: pass3-04-event-creation-under-stress-deep
    index: 59
    pass: 3
    cluster: pass3-canonical
    title: "Event creation under stress — full-depth trace"
    source_topics:
      - event-creator.md
      - event-intake.md
      - health-monitor-and-backpressure.md
      - reasons-not-to-gossip.md
      - gossip.md
    status: not_started

  # ─── Pass 3 — Edge cases that span clusters ─────────────────────────────
  - id: pass3-edge-01-reconnect-during-freeze
    index: 60
    pass: 3
    cluster: pass3-edge
    title: "Reconnect that races a freeze"
    source_topics:
      - reconnect.md
      - freeze-and-upgrade.md
      - gossip.md
      - reasons-not-to-gossip.md
    status: not_started

  - id: pass3-edge-02-fall-behind-during-heavy-gossip
    index: 61
    pass: 3
    cluster: pass3-edge
    title: "Fall-behind triggered by Health Monitor under heavy gossip"
    source_topics:
      - gossip.md
      - health-monitor-and-backpressure.md
      - reasons-not-to-gossip.md
      - reconnect.md
    status: not_started

  - id: pass3-edge-03-roster-change-during-reconnect
    index: 62
    pass: 3
    cluster: pass3-edge
    title: "Roster change during reconnect"
    source_topics:
      - reconnect.md
      - hashgraph.md
      - gossip.md
    status: not_started

  - id: pass3-edge-04-pces-replay-after-upgrade
    index: 63
    pass: 3
    cluster: pass3-edge
    title: "PCES replay after upgrade"
    source_topics:
      - restart-and-pces.md
      - freeze-and-upgrade.md
      - event-intake.md
    status: not_started

  - id: pass3-edge-05-squelching-during-freeze
    index: 64
    pass: 3
    cluster: pass3-edge
    title: "Squelching during a freeze"
    source_topics:
      - reconnect.md
      - freeze-and-upgrade.md
      - reasons-not-to-gossip.md
    status: not_started
---

# Consensus Layer Tutor — Curriculum Manifest

Pedagogical order, derived from `tutor/brief.md` and the consensus-layer KB.
The YAML block above is the machine-readable spec consumed by the
lesson-authoring run; the headings below are the human-readable rendering.

Total entries: **64**.

- Pass 1 (orientation): 4
- Pass 2 (cluster deep-dives): 51
  - Cluster 0 — Wiring Framework Foundation: 6
  - Cluster A.1 — Hashgraph Algorithm: 8
  - Cluster A.2 — Event Intake: 7
  - Cluster A.3 — Gossip: 6
  - Cluster A.4 — Event Creator: 5
  - Cluster A.5 — Steady-state synthesis: 3
  - Cluster B — Stress, health, and self-throttling: 5
  - Cluster C — State, persistence, and recovery: 7
  - Cluster D — Coordinated network events: 4
- Pass 3 (cross-cluster): 9
  - Canonical scenarios revisited: 4
  - Edge cases: 5

## Pass 1 — Orientation map

Four canonical end-to-end scenarios walked at role-and-component altitude.
Plant a complete-but-low-fidelity mental sketch before any cluster goes deep.

1. `pass1-01-tx-to-consensus` — Transaction to consensus — orientation walkthrough.
2. `pass1-02-node-falls-behind` — A node falls behind — orientation walkthrough.
3. `pass1-03-coordinated-upgrade` — Coordinated network upgrade — orientation walkthrough.
4. `pass1-04-event-creation-under-stress` — Event creation under stress — orientation walkthrough.

## Pass 2 — Cluster deep-dives

### Cluster 0 — Wiring Framework Foundation

Substrate for everything else. Deep enough to understand the rest of the
curriculum, no further. Synthesis lesson at the end assembles a small
subgraph end to end so the abstractions become tangible.

5. `c0-01-components-and-schedulers`
6. `c0-02-wires-and-soldering`
7. `c0-03-backpressure-modes`
8. `c0-04-health-monitor-mechanics` *(role-in-stress-response is deferred to Cluster B)*
9. `c0-05-determinism-and-exceptions`
10. `c0-syn-wiring-synthesis`

### Cluster A.1 — Hashgraph Algorithm

Order rationale (from the authoring prompt): DAG → rounds → strongly-seeing
→ judges → consensus order → birth-round, with ancient and stale after
birth-round. Famous-witnesses / voting / coin-rounds machinery is folded
into the judges lesson; the `concepts/voting.md` and `concepts/coin-rounds.md`
files inform that lesson without anchoring separate sub-lessons.

11. `a1-01-hashgraph-dag`
12. `a1-02-rounds-and-witnesses`
13. `a1-03-strongly-seeing`
14. `a1-04-judges`
15. `a1-05-consensus-order`
16. `a1-06-birth-round`
17. `a1-07-ancient-and-stale`
18. `a1-syn-hashgraph-synthesis`

### Cluster A.2 — Event Intake

The largest topic file in the KB and the most pipeline-shaped subsystem.
Sub-lessons follow the topic file's section structure: inputs/outputs,
validation, orphan buffer, birth-round filtering, PCES handoff,
neighbor-discipline reporting.

19. `a2-01-intake-overview-and-inputs`
20. `a2-02-validation-pipeline`
21. `a2-03-orphan-buffer-and-topological-emission`
22. `a2-04-birth-round-filtering-and-branch-detection`
23. `a2-05-pces-handoff-and-backpressure`
24. `a2-06-neighbor-discipline-reporting`
25. `a2-syn-intake-synthesis`

### Cluster A.3 — Gossip

Sub-lessons anchored on the gossip topic file's section structure:
protocol stack, three-phase sync, RPC sync / simple broadcast, fair
selector with caching/buffers, falling-behind plus roster handling.

26. `a3-01-protocol-stack-and-neighbors`
27. `a3-02-three-phase-sync`
28. `a3-03-rpc-sync-and-simple-broadcast`
29. `a3-04-fair-selector-and-buffer-management`
30. `a3-05-falling-behind-and-roster`
31. `a3-syn-gossip-synthesis`

### Cluster A.4 — Event Creator

The most-coupled subsystem in Cluster A — tipset uses hashgraph state,
output goes to Intake, Gossip distributes. Taught last in Cluster A so
the dependencies are established.

32. `a4-01-when-to-create`
33. `a4-02-tipset-other-parent-selection`
34. `a4-03-filling-with-transactions`
35. `a4-04-vetoes-and-self-event-persistence`
36. `a4-syn-event-creator-synthesis`

### Cluster A.5 — Steady-state synthesis

Worked examples that exercise all four sub-clusters together. Where the
cluster lives, not just the parts.

37. `a5-01-steady-state-event-flow-trace`
38. `a5-02-self-event-feedback-loop`
39. `a5-syn-steady-state-synthesis`

### Cluster B — Stress, health, and self-throttling

Health Monitor in its stress-response role (mechanics already covered in
Cluster 0), Execution↔Consensus pacing, dynamic throttling, and the full
catalog of reasons not to gossip. The distinction-from-backpressure
sub-lesson surfaces a load-bearing conceptual split before the catalog
lands.

40. `b-01-health-monitor-role`
41. `b-02-backpressure-pacing-and-throttling`
42. `b-03-distinction-from-backpressure`
43. `b-04-reasons-not-to-gossip-catalog`
44. `b-syn-stress-response-synthesis`

### Cluster C — State, persistence, and recovery

Signed state, PCES, and reconnect — durability, resumption, and the
dance between them. The synthesis lesson stitches all three; the
fallen-behind-detection sub-lesson sits between PCES and the reconnect
protocol because it is the trigger that decides which recovery path runs.

45. `c-01-signed-state-creation-and-types`
46. `c-02-reservation-discipline-and-on-disk`
47. `c-03-pces-write-and-replay`
48. `c-04-fallen-behind-detection`
49. `c-05-reconnect-protocol`
50. `c-06-post-reconnect-resumption`
51. `c-syn-state-and-recovery-synthesis`

### Cluster D — Coordinated network events

Freeze and upgrade. Multi-node coordination; touches everything; taught
last in Pass 2.

52. `d-01-freeze-trigger-and-coordination`
53. `d-02-freeze-time-behaviour`
54. `d-03-upgrade-startup`
55. `d-syn-freeze-upgrade-synthesis`

## Pass 3 — Cross-cluster scenarios and edge cases

The four Pass 1 canonical scenarios revisited at full depth, then the
five cross-cluster edge cases named in the authoring prompt.

### Canonical scenarios revisited

56. `pass3-01-tx-to-consensus-deep`
57. `pass3-02-node-falls-behind-deep`
58. `pass3-03-coordinated-upgrade-deep`
59. `pass3-04-event-creation-under-stress-deep`

### Edge cases

60. `pass3-edge-01-reconnect-during-freeze`
61. `pass3-edge-02-fall-behind-during-heavy-gossip`
62. `pass3-edge-03-roster-change-during-reconnect`
63. `pass3-edge-04-pces-replay-after-upgrade`
64. `pass3-edge-05-squelching-during-freeze`

## Notes for future authoring runs

- **Glossary path.** The authoring prompt names `glossary.md` at the
  consensus-layer root. The actual glossary lives at
  `platform-sdk/docs/hashgraphGlossary.md` (per the KB layout README).
  Phase 2 lesson authoring should read from that path.
- **Invariants file is pending.** `consensus-layer/invariants.md` does
  not yet exist. Pass 2 and Pass 3 lessons that need an INV-NNN claim
  should mark the absence as `[TBD]` and surface it under Open questions
  until the invariants catalog lands.
- **Delta-map entries are pending.** `delta-map/` currently contains
  only a README. Pass 2 and Pass 3 lessons should point to the expected
  `delta-map/<topic>.md` file and mark the delta callout `[TBD]` where
  the file is missing.
- **Scenarios catalog is empty.** `scenarios/` currently contains only
  a README. Pass 3 edge-case lessons that would normally cite an
  `SCN-NNN` near-miss should mark the citation `[TBD]` until those
  entries are written.
- **Concept files mapping.** Of the nine concept files,
  `hashgraph-dag.md`, `rounds-and-witnesses.md`, `strongly-seeing.md`,
  `judges.md`, `birth-round.md`, and `stale-events.md` each anchor a
  Cluster A.1 sub-lesson. `voting.md` and `coin-rounds.md` inform
  `a1-04-judges`. `event-lifecycle.md` informs Cluster A.2 sub-lessons.
- **Cluster B sub-scope risk.** The project brief flags "Reasons not to
  gossip" as needing explicit enumeration before Cluster B authoring
  (open decision #1). Until the enumeration is settled, expect
  `b-04-reasons-not-to-gossip-catalog` to surface `[TBD]` markers for
  rules that aren't yet enumerated in the topic file.
