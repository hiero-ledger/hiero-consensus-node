---
id: INV-017
title: The active address book never moves backward — roster and stake changes apply forward-only
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/FreezeRoundController.java — roster transitions occur at the software-upgrade (freeze) boundary; the post-upgrade roster applies going forward
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-017 — The active address book never moves backward

## Statement

As consensus advances from one round to the next, the active address book — the set of nodes and their stakes in force for a round — only ever moves forward: it reflects a non-decreasing set of applied membership and stake changes, and never reverts to an address book from an earlier point. Equivalently, the effective round whose consensus transactions are reflected in the active address book, `pendingRound(r) − numRoundsAddressBook(r)`, is non-decreasing as the pending round advances.

## Basis

In the hashgraph consensus algorithm, address-book changes — adding or removing nodes, or changing stake — take effect through consensus with a delay: the address book used for calculations during `pendingRound(r)` is based on the transactions that reached consensus in round `pendingRound(r) − numRoundsAddressBook(r)` and earlier. The algorithm bounds how that delay may change from round to round, so that the current active address book never goes back to an earlier one.

Because the active address book at round `r` is fully determined by the consensus transactions up through the effective round `pendingRound(r) − numRoundsAddressBook(r)`, and that effective round is constrained to be non-decreasing, the active address book reflects a monotonically growing set of applied changes. A membership or stake change, once in force, stays in force until itself superseded by a later change; the roster never rewinds.

## Change risk

The invariant is at risk from:

- **Letting the delay between consensus and effect change fast enough that the effective round regresses** — `pendingRound(r) − numRoundsAddressBook(r)` must never decrease as the pending round advances.
- **Applying a newer address book and later reverting to an older one** on any node — for example a reconnect or replay path that reconstructs the roster from an earlier point without carrying forward the intervening changes.
- **Deriving a node's active roster from node-local state** rather than from the agreed consensus sequence, so two nodes disagree on which book is in force.

A reverting address book re-admits departed nodes or restores stale stake, undermining the Byzantine stake thresholds and the orderly handoff between rosters.

## Notes

The address book can change today, but only at software-upgrade boundaries: a freeze round closes the current roster, and the node resumes after the upgrade on the new roster (see `FreezeRoundController`). Across that boundary the new address book applies going forward, and there is no path that resumes on an earlier roster, so the active address book does not move backward. The continuous in-protocol delay model (`numRoundsAddressBook`, a different roster for adjacent non-upgrade rounds) generalizes this; the forward-only guarantee already holds for the upgrade-boundary changes in force today. The companion property, that each round's voting is computed against that round's own roster, is INV-018.
