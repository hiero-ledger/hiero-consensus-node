---
type: invariant
id: INV-010
title: Consensus runs on a hashgraph whose parent links are non-ancient and have matching claimed birth rounds
class: integrity
topics: [event-intake, hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java — `getParentToLink` drops a link on birth-round mismatch; ancient parents are not linked
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-010 — Consensus parents are non-ancient with matching claimed birth rounds

## Statement

In the hashgraph used for consensus, every parent link points to a non-ancient parent whose actual birth round equals the birth round the child claims for that parent. Any link that would violate this is absent — the child is treated as if that parent were not a parent.

## Basis

It is an invariant of the hashgraph consensus algorithm that consensus runs on a hashgraph where every event has pointers only to non-ancient parents whose birth rounds match what it claims for them.

The mechanism is exclusion-for-lying: an event whose claimed parent birth round or creator does not match the actual parent is not added — or has the offending link dropped — until the claim concerns a round so old it is ancient and unverifiable, at which point the parent link is simply absent. Consequently every parent link present in the consensus hashgraph is to a verified, non-ancient parent whose real birth round matches the claim.

## Change risk

- **Linking a parent without checking the claimed birth round against the parent's actual birth round.**
- **Linking ancient parents** into the graph the algorithm runs on.
- **Admitting an event to consensus on the strength of an unverified parent claim**, before the claim is either confirmed or aged out.

A forged or mismatched ancestry corrupts the DAG the round and voting computations run over.

## Notes

This is about the structure of parent links. The birth-round *ordering* relation (child ≥ parent) is INV-012; per-event signature authenticity is INV-011.
