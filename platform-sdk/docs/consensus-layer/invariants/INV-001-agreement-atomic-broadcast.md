---
id: INV-001
title: Honest nodes agree on whether a transaction is output
class: agreement
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §III Definition 1 (Agreement) + §IV Theorem 1 / p. 2
provenance: paper-extraction-2026-05-20
curated_by: Michael Heinrichs (@netopyr)
---

# INV-001 — Honest nodes agree on whether a transaction is output

## Statement
If any honest node outputs a transaction T, then every honest node
eventually outputs T.

## Basis
This is the Agreement property of atomic broadcast, stated as part
of Definition 1 in Baird & Luykx 2020, §III / p. 2: "if any
honest node outputs a transaction, then every honest node outputs
that transaction." Theorem 1 (§IV / p. 2) establishes that the
hashgraph protocol satisfies Definition 1 with probability 1.
Mechanism: every event a node creates eventually propagates to
every honest node via the gossip protocol (§IV-A), and every
honest node computes its output from a deterministic function of
its local hashgraph (§IV-B, §IV-D-3). Two honest nodes therefore
output the same set of transactions in the long run.

## Change risk
Any change that allows an honest node to emit a transaction
without that transaction being in an event reachable by other
honest nodes via gossip breaks this invariant — for example,
producing output from local-only events that are excluded from
gossip-sync, or pruning events from the hashgraph before they
reach a quorum of honest peers. Equivalently, any change that
makes the consensus-order computation non-deterministic across
honest nodes breaks it.

## Notes
- Theorem 1 bundles Agreement, Total Order, and Liveness; this
  entry covers Agreement only. The Total Order and Liveness
  components are captured by INV-002 and INV-003 respectively.
- `status` is [TBD: confirm enforced in current implementation] —
  paper-derivedness does not by itself determine whether the
  current code upholds the property.
