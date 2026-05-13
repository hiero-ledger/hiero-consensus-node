---
# Consensus Layer Tutor — curriculum manifest
#
# This file is the machine-readable manifest of every lesson the tutor curriculum
# plans to author. The list is in pedagogical order — `index` is the source of
# truth for "what comes next". A lesson exists when `tutor/lessons/<id>.md`
# exists on disk; the `status` field is metadata for reviewers and is not used
# by the authoring run to decide what to author.
#
# The manifest is human-editable between runs. Renames, reorderings, splits,
# and merges should be made here, then committed; the next authoring run picks
# up the edited shape. Re-running the bootstrap is a no-op while this file
# exists.
#
# Discovery notes from the bootstrap run that produced this file:
#   - No explicit "project brief" was found in the repo. The bootstrap used
#     `consensus-layer/README.md` (KB layout) plus `architecture/overview.md`
#     (module/topic map) as the structural anchors, the eleven
#     `architecture/topics/*.md` files as the per-topic content sources, the
#     nine `concepts/*.md` files as the conceptual anchors for Cluster A.1,
#     `platform-sdk/docs/hashgraphGlossary.md` (the canonical glossary, one
#     directory above the consensus-layer KB) as the term catalog, and
#     `tutor/prompts/tutor-system-prompt.md` for the pedagogical shape.
#   - `invariants.md` is marked "pending" in the KB README and does not yet
#     exist; Pass 2 lessons will populate `kb_invariants` with [TBD] markers
#     until that file lands.
#   - `delta-map/`, `decisions/`, and `scenarios/` contain only READMEs;
#     per-topic delta files and per-ID ADR/SCN files will be referenced as
#     they appear.
#
entries:
  # ---- Pass 1 — orientation scenarios -----------------------------------------
  - id: pass1-01-transaction-to-consensus
    index: 1
    pass: 1
    cluster: pass1
    title: From a user transaction to a consensus round
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/event-intake.md
      - architecture/topics/gossip.md
      - architecture/topics/hashgraph.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/signed-state-management.md
    status: drafted

  - id: pass1-02-node-falls-behind
    index: 2
    pass: 1
    cluster: pass1
    title: A node falls behind and recovers via reconnect
    source_topics:
      - architecture/topics/gossip.md
      - architecture/topics/reconnect.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/event-intake.md
    status: not_started

  - id: pass1-03-coordinated-network-upgrade
    index: 3
    pass: 1
    cluster: pass1
    title: A coordinated network upgrade through freeze
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
    status: not_started

  - id: pass1-04-event-creation-under-stress
    index: 4
    pass: 1
    cluster: pass1
    title: Event creation under stress — health, backpressure, and refusals to gossip
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
    status: not_started

  # ---- Pass 2 / Cluster 0 — Wiring Framework Foundation -----------------------
  - id: c0-01-task-schedulers
    index: 5
    pass: 2
    cluster: c0
    title: Task schedulers and threading policies
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: c0-02-wires-and-bindings
    index: 6
    pass: 2
    cluster: c0
    title: Input wires, output wires, and bindings
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: c0-03-soldering-and-transformers
    index: 7
    pass: 2
    cluster: c0
    title: Soldering, transformers, and splitters
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: c0-04-wire-level-backpressure
    index: 8
    pass: 2
    cluster: c0
    title: Wire-level backpressure and cycle handling
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: c0-05-wiring-model-and-health-wire
    index: 9
    pass: 2
    cluster: c0
    title: The wiring model lifecycle and the health wire
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: c0-syn-wiring-runtime-synthesis
    index: 10
    pass: 2
    cluster: c0
    title: Synthesis — assembling and running a consensus subgraph
    source_topics:
      - architecture/topics/wiring-framework.md
    status: not_started

  # ---- Pass 2 / Cluster A.1 — Hashgraph Algorithm -----------------------------
  - id: a1-01-hashgraph-dag
    index: 11
    pass: 2
    cluster: a1
    title: The hashgraph DAG — events, parents, and the linker
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started

  - id: a1-02-rounds-and-witnesses
    index: 12
    pass: 2
    cluster: a1
    title: Rounds and witnesses
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started

  - id: a1-03-strongly-seeing
    index: 13
    pass: 2
    cluster: a1
    title: The strongly-seeing relation and the memoized dot product
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started

  - id: a1-04-voting-and-coin-rounds
    index: 14
    pass: 2
    cluster: a1
    title: Fame voting paths and coin rounds
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started

  - id: a1-05-judges
    index: 15
    pass: 2
    cluster: a1
    title: Judges, branched-creator merge, and the famous-witness outcome
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started

  - id: a1-06-consensus-order
    index: 16
    pass: 2
    cluster: a1
    title: Consensus order within a round — the ConsensusSorter tie-break
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started

  - id: a1-07-birth-round
    index: 17
    pass: 2
    cluster: a1
    title: Birth round, the event window, and round emission
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started

  - id: a1-08-ancient-and-stale-events
    index: 18
    pass: 2
    cluster: a1
    title: Ancient thresholds and stale events
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started

  - id: a1-syn-hashgraph-synthesis
    index: 19
    pass: 2
    cluster: a1
    title: Synthesis — one event's life through round, witness, judge, and order
    source_topics:
      - architecture/topics/hashgraph.md
    status: not_started

  # ---- Pass 2 / Cluster A.2 — Event Intake ------------------------------------
  - id: a2-01-validation-pipeline
    index: 20
    pass: 2
    cluster: a2
    title: The five-stage validation pipeline
    source_topics:
      - architecture/topics/event-intake.md
    status: not_started

  - id: a2-02-deduplication-and-signature-validation
    index: 21
    pass: 2
    cluster: a2
    title: Deduplication, signature validation, and the ordering trade
    source_topics:
      - architecture/topics/event-intake.md
    status: not_started

  - id: a2-03-orphan-buffer
    index: 22
    pass: 2
    cluster: a2
    title: The orphan buffer — linking, release walks, and sequence numbers
    source_topics:
      - architecture/topics/event-intake.md
    status: not_started

  - id: a2-04-birth-round-filter-and-pces-handoff
    index: 23
    pass: 2
    cluster: a2
    title: Birth-round filtering and the PCES durability handoff
    source_topics:
      - architecture/topics/event-intake.md
      - architecture/topics/restart-and-pces.md
    status: not_started

  - id: a2-syn-event-intake-synthesis
    index: 24
    pass: 2
    cluster: a2
    title: Synthesis — a peer event from the wire to the hashgraph
    source_topics:
      - architecture/topics/event-intake.md
    status: not_started

  # ---- Pass 2 / Cluster A.3 — Gossip ------------------------------------------
  - id: a3-01-protocol-stack-and-rpc
    index: 25
    pass: 2
    cluster: a3
    title: The protocol stack and RPC sync layer
    source_topics:
      - architecture/topics/gossip.md
    status: not_started

  - id: a3-02-three-phase-sync
    index: 26
    pass: 2
    cluster: a3
    title: The three-phase sync protocol
    source_topics:
      - architecture/topics/gossip.md
    status: not_started

  - id: a3-03-simple-broadcast-and-overload
    index: 27
    pass: 2
    cluster: a3
    title: Simple broadcast and the RPC overload monitor
    source_topics:
      - architecture/topics/gossip.md
    status: not_started

  - id: a3-04-fair-sync-selector
    index: 28
    pass: 2
    cluster: a3
    title: The fair sync selector
    source_topics:
      - architecture/topics/gossip.md
    status: not_started

  - id: a3-syn-gossip-synthesis
    index: 29
    pass: 2
    cluster: a3
    title: Synthesis — two peers reconciling shadowgraphs end-to-end
    source_topics:
      - architecture/topics/gossip.md
    status: not_started

  # ---- Pass 2 / Cluster A.4 — Event Creator -----------------------------------
  - id: a4-01-tipset-and-advancement-score
    index: 30
    pass: 2
    cluster: a4
    title: Tipsets and the partial-weighted advancement score
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started

  - id: a4-02-snapshot-and-event-creation-rule
    index: 31
    pass: 2
    cluster: a4
    title: The snapshot baseline and the event-creation rule
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started

  - id: a4-03-selfishness-and-anti-selfishness
    index: 32
    pass: 2
    cluster: a4
    title: Selfishness scoring and the anti-selfishness pity pick
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started

  - id: a4-04-creation-rules-and-health-gates
    index: 33
    pass: 2
    cluster: a4
    title: Permission rules — health, platform status, sync lag, rate, quiescence
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/health-monitor-and-backpressure.md
    status: not_started

  - id: a4-syn-event-creator-synthesis
    index: 34
    pass: 2
    cluster: a4
    title: Synthesis — building one self-event under all gates
    source_topics:
      - architecture/topics/event-creator.md
    status: not_started

  # ---- Pass 2 / Cluster A.5 — Steady-state synthesis --------------------------
  - id: a5-syn-steady-state-synthesis
    index: 35
    pass: 2
    cluster: a5-syn
    title: Synthesis — gossip, intake, hashgraph, and event creation in steady state
    source_topics:
      - architecture/topics/gossip.md
      - architecture/topics/event-intake.md
      - architecture/topics/hashgraph.md
      - architecture/topics/event-creator.md
    status: not_started

  # ---- Pass 2 / Cluster B — Health Monitor & Reasons Not to Gossip -----------
  - id: b-01-health-monitor-detection
    index: 36
    pass: 2
    cluster: b
    title: Health-monitor detection — the unhealthy-duration signal
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/wiring-framework.md
    status: not_started

  - id: b-02-reaction-sites
    index: 37
    pass: 2
    cluster: b
    title: Reaction sites — event creation, gossip permits, transactions, PCES replay
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
      - architecture/topics/restart-and-pces.md
    status: not_started

  - id: b-03-reasons-not-to-gossip-catalog
    index: 38
    pass: 2
    cluster: b
    title: The categorical catalog — why a node may not gossip
    source_topics:
      - architecture/topics/reasons-not-to-gossip.md
    status: not_started

  - id: b-04-backpressure-vs-categorical
    index: 39
    pass: 2
    cluster: b
    title: Distinguishing graded backpressure from categorical refusals
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/reasons-not-to-gossip.md
    status: not_started

  - id: b-syn-stability-synthesis
    index: 40
    pass: 2
    cluster: b
    title: Synthesis — a saturated subgraph recovering under both regimes
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/reasons-not-to-gossip.md
    status: not_started

  # ---- Pass 2 / Cluster C — Signed State, Restart + PCES, Reconnect ----------
  - id: c-01-signed-state-runtime-types
    index: 41
    pass: 2
    cluster: c
    title: Signed-state runtime types and reservation discipline
    source_topics:
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: c-02-signed-state-lifecycle-and-on-disk-layout
    index: 42
    pass: 2
    cluster: c
    title: The six-phase lifecycle and the on-disk snapshot layout
    source_topics:
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: c-03-inline-pces-write-path
    index: 43
    pass: 2
    cluster: c
    title: The inline-PCES write path and the persist-before-gossip invariant
    source_topics:
      - architecture/topics/restart-and-pces.md
    status: not_started

  - id: c-04-restart-and-pces-replay
    index: 44
    pass: 2
    cluster: c
    title: The restart sequence and PCES replay
    source_topics:
      - architecture/topics/restart-and-pces.md
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: c-05-reconnect-detection-and-orchestration
    index: 45
    pass: 2
    cluster: c
    title: Reconnect — detection, peer selection, and the controller loop
    source_topics:
      - architecture/topics/reconnect.md
    status: not_started

  - id: c-06-learner-teacher-and-resumption
    index: 46
    pass: 2
    cluster: c
    title: The learner/teacher exchange and post-reconnect resumption
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: c-syn-recovery-synthesis
    index: 47
    pass: 2
    cluster: c
    title: Synthesis — a state's life from creation through reconnect and replay
    source_topics:
      - architecture/topics/signed-state-management.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/reconnect.md
    status: not_started

  # ---- Pass 2 / Cluster D — Freeze and Upgrade --------------------------------
  - id: d-01-freeze-trigger-and-platform-state
    index: 48
    pass: 2
    cluster: d
    title: The freeze trigger and the platform-state freeze fields
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
    status: not_started

  - id: d-02-freeze-round-and-event-cutoff
    index: 49
    pass: 2
    cluster: d
    title: The freeze round, the round-level cutoff, and event creation gating
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/hashgraph.md
      - architecture/topics/event-creator.md
    status: not_started

  - id: d-03-freeze-state-save-and-status-transitions
    index: 50
    pass: 2
    cluster: d
    title: The freeze-state save and the FREEZING → FREEZE_COMPLETE transition
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/signed-state-management.md
    status: not_started

  - id: d-04-upgrade-startup-path
    index: 51
    pass: 2
    cluster: d
    title: Upgrade startup — booting from a freeze state
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/restart-and-pces.md
    status: not_started

  - id: d-syn-freeze-upgrade-synthesis
    index: 52
    pass: 2
    cluster: d
    title: Synthesis — a network coordinated through freeze and resumed on a new binary
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
    status: not_started

  # ---- Pass 3 — canonicals revisited at full depth ----------------------------
  - id: pass3-01-transaction-to-consensus-deep
    index: 53
    pass: 3
    cluster: pass3-canonical
    title: From a user transaction to a consensus round — full depth
    source_topics:
      - architecture/topics/event-creator.md
      - architecture/topics/event-intake.md
      - architecture/topics/gossip.md
      - architecture/topics/hashgraph.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/health-monitor-and-backpressure.md
    status: not_started

  - id: pass3-02-node-falls-behind-deep
    index: 54
    pass: 3
    cluster: pass3-canonical
    title: A node falls behind — full depth across detection, transfer, and resumption
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/gossip.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/event-intake.md
      - architecture/topics/event-creator.md
    status: not_started

  - id: pass3-03-coordinated-network-upgrade-deep
    index: 55
    pass: 3
    cluster: pass3-canonical
    title: A coordinated network upgrade — full depth
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/restart-and-pces.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
      - architecture/topics/hashgraph.md
    status: not_started

  - id: pass3-04-event-creation-under-stress-deep
    index: 56
    pass: 3
    cluster: pass3-canonical
    title: Event creation under stress — full depth across all reactions
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
      - architecture/topics/event-intake.md
      - architecture/topics/restart-and-pces.md
    status: not_started

  # ---- Pass 3 — edges ---------------------------------------------------------
  - id: pass3-edge-01-reconnect-during-freeze
    index: 57
    pass: 3
    cluster: pass3-edge
    title: Reconnect during freeze
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/gossip.md
    status: not_started

  - id: pass3-edge-02-fall-behind-during-heavy-gossip
    index: 58
    pass: 3
    cluster: pass3-edge
    title: Fall-behind triggered by the health monitor during heavy gossip
    source_topics:
      - architecture/topics/health-monitor-and-backpressure.md
      - architecture/topics/gossip.md
      - architecture/topics/reconnect.md
      - architecture/topics/reasons-not-to-gossip.md
    status: not_started

  - id: pass3-edge-03-roster-change-during-reconnect
    index: 59
    pass: 3
    cluster: pass3-edge
    title: Roster change during reconnect
    source_topics:
      - architecture/topics/reconnect.md
      - architecture/topics/signed-state-management.md
      - architecture/topics/event-intake.md
      - architecture/topics/hashgraph.md
    status: not_started

  - id: pass3-edge-04-pces-replay-after-upgrade
    index: 60
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
    index: 61
    pass: 3
    cluster: pass3-edge
    title: Squelching during freeze
    source_topics:
      - architecture/topics/freeze-and-upgrade.md
      - architecture/topics/reasons-not-to-gossip.md
      - architecture/topics/event-creator.md
      - architecture/topics/gossip.md
    status: not_started
---

# Consensus Layer Tutor — Curriculum

This is the human-readable rendering of the manifest above. Edit either the
YAML or this list between authoring runs; the next run uses the manifest as
the source of truth for "which lesson comes next" (and uses file existence at
`tutor/lessons/<id>.md` as the actual state probe — `status` here is reviewer
metadata).

## Pass 1 — Orientation scenarios

Light, role-level scenarios that plant a complete-but-low-fidelity mental
sketch before Pass 2 introduces mechanism depth. No code anchors; no
delta callouts; misconception watchlist omitted.

1. **pass1-01-transaction-to-consensus** — From a user transaction to a consensus round
2. **pass1-02-node-falls-behind** — A node falls behind and recovers via reconnect
3. **pass1-03-coordinated-network-upgrade** — A coordinated network upgrade through freeze
4. **pass1-04-event-creation-under-stress** — Event creation under stress — health, backpressure, and refusals to gossip

## Pass 2 — Cluster 0 — Wiring Framework Foundation

The substrate. Every later cluster references the abstractions introduced
here.

5. **c0-01-task-schedulers** — Task schedulers and threading policies
6. **c0-02-wires-and-bindings** — Input wires, output wires, and bindings
7. **c0-03-soldering-and-transformers** — Soldering, transformers, and splitters
8. **c0-04-wire-level-backpressure** — Wire-level backpressure and cycle handling
9. **c0-05-wiring-model-and-health-wire** — The wiring model lifecycle and the health wire
10. **c0-syn-wiring-runtime-synthesis** — Synthesis — assembling and running a consensus subgraph

## Pass 2 — Cluster A.1 — Hashgraph Algorithm

The DAG, rounds, judges, and consensus order. Order is dependency-driven —
each lesson assumes the mental model of those above it.

11. **a1-01-hashgraph-dag** — The hashgraph DAG — events, parents, and the linker
12. **a1-02-rounds-and-witnesses** — Rounds and witnesses
13. **a1-03-strongly-seeing** — The strongly-seeing relation and the memoized dot product
14. **a1-04-voting-and-coin-rounds** — Fame voting paths and coin rounds
15. **a1-05-judges** — Judges, branched-creator merge, and the famous-witness outcome
16. **a1-06-consensus-order** — Consensus order within a round — the ConsensusSorter tie-break
17. **a1-07-birth-round** — Birth round, the event window, and round emission
18. **a1-08-ancient-and-stale-events** — Ancient thresholds and stale events
19. **a1-syn-hashgraph-synthesis** — Synthesis — one event's life through round, witness, judge, and order

## Pass 2 — Cluster A.2 — Event Intake

The five-stage validation pipeline and the durability handoff into PCES.

20. **a2-01-validation-pipeline** — The five-stage validation pipeline
21. **a2-02-deduplication-and-signature-validation** — Deduplication, signature validation, and the ordering trade
22. **a2-03-orphan-buffer** — The orphan buffer — linking, release walks, and sequence numbers
23. **a2-04-birth-round-filter-and-pces-handoff** — Birth-round filtering and the PCES durability handoff
24. **a2-syn-event-intake-synthesis** — Synthesis — a peer event from the wire to the hashgraph

## Pass 2 — Cluster A.3 — Gossip

The protocol stack, sync, broadcast, and the fair selector.

25. **a3-01-protocol-stack-and-rpc** — The protocol stack and RPC sync layer
26. **a3-02-three-phase-sync** — The three-phase sync protocol
27. **a3-03-simple-broadcast-and-overload** — Simple broadcast and the RPC overload monitor
28. **a3-04-fair-sync-selector** — The fair sync selector
29. **a3-syn-gossip-synthesis** — Synthesis — two peers reconciling shadowgraphs end-to-end

## Pass 2 — Cluster A.4 — Event Creator

Tipset, advancement score, snapshot updates, selfishness, and the rule chain.

30. **a4-01-tipset-and-advancement-score** — Tipsets and the partial-weighted advancement score
31. **a4-02-snapshot-and-event-creation-rule** — The snapshot baseline and the event-creation rule
32. **a4-03-selfishness-and-anti-selfishness** — Selfishness scoring and the anti-selfishness pity pick
33. **a4-04-creation-rules-and-health-gates** — Permission rules — health, platform status, sync lag, rate, quiescence
34. **a4-syn-event-creator-synthesis** — Synthesis — building one self-event under all gates

## Pass 2 — Cluster A.5 — Steady-state synthesis

A single synthesis lesson that integrates the four A clusters at steady
state, before B introduces the destabilising surfaces.

35. **a5-syn-steady-state-synthesis** — Synthesis — gossip, intake, hashgraph, and event creation in steady state

## Pass 2 — Cluster B — Health Monitor & Backpressure, Reasons Not to Gossip

The two "what slows down or stops" topics, contrasted: graded
queue-driven backpressure vs categorical guards.

36. **b-01-health-monitor-detection** — Health-monitor detection — the unhealthy-duration signal
37. **b-02-reaction-sites** — Reaction sites — event creation, gossip permits, transactions, PCES replay
38. **b-03-reasons-not-to-gossip-catalog** — The categorical catalog — why a node may not gossip
39. **b-04-backpressure-vs-categorical** — Distinguishing graded backpressure from categorical refusals
40. **b-syn-stability-synthesis** — Synthesis — a saturated subgraph recovering under both regimes

## Pass 2 — Cluster C — Signed State, Restart + PCES, Reconnect

The three recovery-flow topics. Reservation discipline first, then write
paths, then both online and offline catch-up.

41. **c-01-signed-state-runtime-types** — Signed-state runtime types and reservation discipline
42. **c-02-signed-state-lifecycle-and-on-disk-layout** — The six-phase lifecycle and the on-disk snapshot layout
43. **c-03-inline-pces-write-path** — The inline-PCES write path and the persist-before-gossip invariant
44. **c-04-restart-and-pces-replay** — The restart sequence and PCES replay
45. **c-05-reconnect-detection-and-orchestration** — Reconnect — detection, peer selection, and the controller loop
46. **c-06-learner-teacher-and-resumption** — The learner/teacher exchange and post-reconnect resumption
47. **c-syn-recovery-synthesis** — Synthesis — a state's life from creation through reconnect and replay

## Pass 2 — Cluster D — Freeze and Upgrade

The coordinated network-wide stop, freeze-state save, and upgrade boot.

48. **d-01-freeze-trigger-and-platform-state** — The freeze trigger and the platform-state freeze fields
49. **d-02-freeze-round-and-event-cutoff** — The freeze round, the round-level cutoff, and event creation gating
50. **d-03-freeze-state-save-and-status-transitions** — The freeze-state save and the FREEZING → FREEZE_COMPLETE transition
51. **d-04-upgrade-startup-path** — Upgrade startup — booting from a freeze state
52. **d-syn-freeze-upgrade-synthesis** — Synthesis — a network coordinated through freeze and resumed on a new binary

## Pass 3 — Canonicals revisited at full depth

Each Pass 1 scenario, returned to with mechanism-level depth, code anchors,
invariants, deltas, and cross-cluster stitch points.

53. **pass3-01-transaction-to-consensus-deep** — From a user transaction to a consensus round — full depth
54. **pass3-02-node-falls-behind-deep** — A node falls behind — full depth across detection, transfer, and resumption
55. **pass3-03-coordinated-network-upgrade-deep** — A coordinated network upgrade — full depth
56. **pass3-04-event-creation-under-stress-deep** — Event creation under stress — full depth across all reactions

## Pass 3 — Edges

Five cross-cluster scenarios that no single Pass 2 lesson can teach in
isolation. Each one is the perturbation that exposes a cluster boundary.

57. **pass3-edge-01-reconnect-during-freeze** — Reconnect during freeze
58. **pass3-edge-02-fall-behind-during-heavy-gossip** — Fall-behind triggered by the health monitor during heavy gossip
59. **pass3-edge-03-roster-change-during-reconnect** — Roster change during reconnect
60. **pass3-edge-04-pces-replay-after-upgrade** — PCES replay after upgrade
61. **pass3-edge-05-squelching-during-freeze** — Squelching during freeze
