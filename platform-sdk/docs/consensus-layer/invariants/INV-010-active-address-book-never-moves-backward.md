---
type: invariant
id: INV-010
title: The active address book never moves backward — roster and stake changes apply forward-only
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: [ADR-006]
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/FreezeRoundController.java — roster transitions occur at the software-upgrade (freeze) boundary; the post-upgrade roster applies going forward
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-010 — The active address book never moves backward

## Statement

The active address book — the set of nodes and their stakes in force for consensus — only ever moves forward. A membership or stake change, once applied, stays in force until superseded by a later change; the active address book never reverts to an earlier one.

## Basis

In the hashgraph consensus algorithm, address-book changes — adding or removing nodes, or changing stake — are applied forward only: each change takes effect through consensus and remains in force until a later change supersedes it, so the sequence of active address books is monotonic and never rewinds. This forward-only discipline is what keeps the Byzantine stake thresholds and the handoff from one roster to the next sound across a change.

Today such changes occur only at software-upgrade boundaries: a freeze round closes the current roster, and the node resumes after the upgrade on the new roster (see `FreezeRoundController`). Across that boundary the new address book applies going forward, and no path resumes consensus on an earlier roster, so the active address book does not move backward.

## Change risk

- **Applying a newer address book and later reverting to an older one** on any node — for example a reconnect or replay path that reconstructs the roster from an earlier point without carrying forward the intervening changes.
- **Resuming after an upgrade on a pre-upgrade roster** — the freeze handoff must always continue on the post-upgrade address book.
- **Deriving a node's active roster from node-local state** rather than from the agreed consensus sequence, so two nodes disagree on which book is in force.

A reverting address book re-admits departed nodes or restores stale stake, undermining the Byzantine stake thresholds and the orderly handoff between rosters.
