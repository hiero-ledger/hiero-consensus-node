---
id: ADR-005
title: Upgrade Software via a Coordinated Network-Wide Freeze Rather Than Rolling Upgrades
topics: [freeze-and-upgrade]
related:
  invariants: []
  decisions: [ADR-002]
  scenarios: []
  heuristics: []
  rules: []
status: accepted
date: 2026-06-04
deciders: []   # foundational design decision; individual authors not recorded — see Notes
curated_by: Kelly Greco (@poulok)
---

# ADR-005 — Upgrade Software via a Coordinated Network-Wide Freeze Rather Than Rolling Upgrades

## Context

The network is a leaderless ledger: every node independently discovers and operates on a
hashgraph of events, and from that hashgraph each node derives the **same** ordering of
transactions. Each node then handles those transactions, updates its state, and produces blocks
**deterministically** — same starting state plus same ordered transactions yields the same
resulting state and the same blocks, on every node.

The software that handles a transaction is what defines how state changes for that transaction.
This is the discriminating fact for upgrades: **two different software versions, given the exact
same starting state and the same transaction, may legitimately produce different state outcomes
and different blocks.** A change in handling logic is, by design, a change in the deterministic
function the network is computing.

State and blocks are agreed across the network. If more than a threshold of the network disagrees
on the resulting state or blocks, the divergence is **catastrophic** — the network can no longer
agree on what happened. Any upgrade mechanism must therefore guarantee that, at every block, at least a super majority of the network (>2/3)
is computing with the **same** handling logic. The same constraint extends to the consensus
layer: the logic that builds the hashgraph and derives ordering must also be identical across
nodes at any given point.

The question is how to move the whole network from version *N* to version *N+1* without ever
having two versions produce different blocks for the same block.

## Decision

**Upgrade the network through a coordinated, network-wide freeze.** All nodes agree in advance on
a point at which they stop processing transactions and producing blocks, and they stop at that
same point. Operators upgrade the software on each node, and once enough nodes are back online the
network resumes — now processing transactions and producing blocks with the new logic.

The defining property is a clean, network-agreed boundary: there is a definite last block produced
by version *N* and a definite first block produced by version *N+1*, and **every node observes the
same boundary**. No block is ever produced by two different versions on different nodes.

This category admits more than one implementation. The boundary is what matters, not whether the
node is briefly offline:

- **Today:** accept some downtime. The network performs a coordinated freeze, nodes go down,
  the upgrade is performed, and the network restarts on the new version. The freeze procedure
  itself — the coordinated halt, freeze-state save, and restart — is documented in the
  [freeze-and-upgrade topic](../architecture/topics/freeze-and-upgrade.md); see ADR-002 for the
  freeze-block signature handoff that runs as part of it.
- **Future (same category):** a side-by-side handover, in which the old and new software run
  alongside each other and perform an invisible cutover at a network-agreed point, reducing or
  eliminating downtime. This is still a coordinated network-wide boundary, not a rolling upgrade.

## Temporary Nature

The **downtime** is the temporary aspect, not the category. The accepted-today approach of taking
nodes offline during the freeze may be replaced by the side-by-side handover described above to
reduce or eliminate downtime. Any such replacement stays within the coordinated network-wide
category and preserves the single agreed boundary; rolling upgrades are not a future option.

## Consequences

### Positive

- **Blocks are always identical across the network.** A single agreed boundary means no block
  is ever produced by two software versions, eliminating the catastrophic divergence risk
  by construction.
- **New software does not have to emulate old software.** Version *N+1* can change handling and
  consensus logic freely; it is only ever responsible for blocks at or after the agreed boundary,
  so it never has to reproduce version *N*'s behavior bit-for-bit.
- **Simple, auditable correctness argument.** "Everyone stopped at the same block, everyone
  resumed on the same version" is straightforward to reason about and verify, compared with
  per-block version-switching logic.

### Negative

- **Downtime (today).** The coordinated freeze takes the network offline for the duration of the
  upgrade. Transaction processing and block production halt until enough nodes return.
- **Coordination cost.** Every upgrade requires the whole network to agree on the freeze point and
  to act on it, rather than upgrading nodes opportunistically and independently.

### Neutral

- The freeze boundary is a network-wide event with its own machinery (the coordinated halt,
  freeze-state save, and restart described in the
  [freeze-and-upgrade topic](../architecture/topics/freeze-and-upgrade.md), and the freeze-block
  signature handoff in ADR-002); upgrades are coupled to that mechanism rather than being a purely
  per-node operational action.
- Choosing this category leaves the door open to lowering downtime later (side-by-side handover)
  without revisiting the fundamental upgrade model.

## Alternatives Considered

### 1. Rolling upgrades

Upgrade one node at a time to the new software while the network keeps running. Because all nodes
must agree on state and blocks at every height, the new software would have to **behave exactly
like the old software** until a threshold of nodes had upgraded, then switch — all nodes agreeing
on the precise block at which handling flips from old logic to new. The same version-aware
switching would have to extend into the consensus layer.

**Rejected because:**

- It requires every new version to faithfully reproduce the previous version's handling and
  consensus behavior up to the switch point, then change in lockstep — effectively shipping two
  behaviors in one binary and a network-agreed switch between them.
- That dual-behavior, block-precise switching is too complex and too error-prone to be viable: a
  single discrepancy in the emulated old behavior produces exactly the catastrophic state/block
  divergence the whole scheme is meant to prevent.

### 2. Coordinated network-wide upgrade (selected)

See **Decision** above. Includes both the accept-downtime freeze used today and the future
side-by-side handover, which share the single network-agreed boundary.

## References

- [freeze-and-upgrade topic](../architecture/topics/freeze-and-upgrade.md) — how the coordinated
  freeze, freeze-state save, and restart work in current code; its "Upgrade startup" section makes
  the same point this decision rests on: every node restarts from exactly the same state, which is
  what makes it safe to bring up a software version that may interpret transactions or state
  differently than the prior one.
- [ADR-002](ADR-002-execution-freeze-signature-handoff.md) — freeze-block signature handoff that
  runs during the coordinated freeze this decision adopts.

## Notes

- This is a **retroactive** ADR: it records a foundational design decision that predates this
  repository and its decisions catalog by years. The `date` field is the date this entry was
  written, not the date the decision was made.
- `deciders` is intentionally empty. The choice traces back to the original Hashgraph/Swirlds
  platform design and the individual authors are not recorded here; rather than guess, the field
  is left empty. If an authoritative source for the original deciders is found, populate it then.
