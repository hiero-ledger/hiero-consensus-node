---
id: INV-004
title: Honest hashgraphs share identical ancestor sub-DAGs for any shared event
class: agreement
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §IV-A / p. 3
provenance: paper-extraction-2026-05-20
curated_by: Michael Heinrichs (@netopyr)
---

# INV-004 — Honest hashgraphs share identical ancestor sub-DAGs for any shared event

## Statement
For any event x present in two honest nodes' hashgraphs, both
hashgraphs contain the same set of ancestors of x and the same
parent edges among those ancestors.

## Basis
Each event is a tuple containing the hashes of its two parents, a
transaction list, a timestamp, and a signature over the whole
tuple (Baird & Luykx 2020, §IV-A / p. 3). Because cryptographic
hashes are collision-resistant, the parent-of edges of any event
are determined by the event itself; the ancestor sub-DAG of x is
reconstructible from x's hash chain alone. Honest nodes only
accept events with valid signatures and resolvable parent hashes
(INV-005). Two honest nodes that both hold x therefore necessarily
hold the same ancestor sub-DAG. The paper states this directly:
"any two nodes have consistent hashgraphs: for any event x
contained in hashgraphs A and B, both A and B contain the same set
of ancestors for x, with the same parent edges between those
ancestors."

## Change risk
Any change that lets an honest node admit a divergent view of x's
parents — for example, treating an event as valid before all of
its ancestors are verified, accepting events with unresolved
parent hashes, or relaxing signature checks on parent references —
breaks this invariant. The downstream effect cascades: virtual
voting (which is defined on ancestor relations) produces different
votes on different nodes, and consensus order diverges.

## Notes
- Sits downstream of INV-005 (event authenticity): without
  authenticated, parent-resolvable events the hash-chain rigidity
  argument collapses.
- `status` is [TBD: confirm enforced in current implementation].
