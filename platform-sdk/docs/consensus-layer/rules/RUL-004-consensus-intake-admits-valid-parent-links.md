---
type: rule
id: RUL-004
title: Consensus intake admits only non-ancient parent links whose claimed birth round matches the actual parent
class: protocol
topics: [event-intake, hashgraph]
components:
  - consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java
related:
  invariants: [INV-002, INV-012]
  decisions: []
  scenarios: []
  heuristics: []
status: holds
confidence: high
provenance: originally 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# RUL-004 — Consensus intake admits only non-ancient parent links whose claimed birth round matches the actual parent

## Statement

The consensus engine links an event to a parent only when that parent is non-ancient and its actual birth round equals the birth round the child claims for it. A parent link that fails either check is dropped and the event is processed as if that parent were absent — so the hashgraph handed to the consensus algorithm contains only non-ancient, birth-round-consistent parent links.

## Context

The consensus algorithm computes rounds, the ancient boundary, and virtual votes over the parent DAG. A parent link carrying a false birth-round claim, or pointing at an ancient event, would feed those computations a corrupted graph. Intake forecloses this with exclusion-for-lying: an event whose claimed parent birth round (or creator) does not match the actual parent is held out — or has the offending link dropped — until the claim concerns a round so old it is ancient and unverifiable, at which point the parent link is simply absent.

The protocol relies on this graph being clean, but the *way* it is achieved is an implementation choice: a different correct engine could ignore the claims entirely and re-derive each parent's birth round from the actual parent event, in which case "claimed matches actual" would not even be a meaningful property. That contingency is what makes this a rule rather than an invariant — the property holds because the linker enforces it, not because no correct implementation could differ.

## Why it holds now

`ConsensusLinker.getParentToLink` returns `null` — dropping the link — whenever the candidate parent's actual `getBirthRound()` differs from the birth round carried in the child's parent descriptor; and `ConsensusLinker.linkEvent` never links a parent that the event window classifies as ancient (`EventWindow.isAncient`). Because every parent link is established through `getParentToLink`, every link that survives points at a non-ancient parent whose real birth round equals the claim.

The property is contingent on the linker performing both checks at admission. If the linker were changed to trust the claimed birth rounds, to re-derive them from the actual parents, or to retain ancient links, this exact property would no longer hold — and that could be a legitimate redesign rather than a defect.

## Change risk

- **Linking a parent without comparing its actual birth round to the child's claimed birth round.**
- **Retaining ancient parents** in the graph the algorithm runs over.
- **Admitting an event into consensus on the strength of an unverified parent claim**, before the claim is confirmed or aged out.

Breaking this rule is a **flag for confirmation**, not automatically a defect. A redesign that replaces trust-then-validate with re-deriving parent birth rounds from the actual parents, or that changes how ancient links are represented, could legitimately rewrite or retire this rule. Confirmation looks like answering: *does the consensus algorithm still only ever compute over parent links whose metadata matches reality?* If the new design guarantees that by other means, the change is correct and this rule should be revised; if it lets forged or stale parent metadata reach the round and voting computations, it is a defect.

## Notes

- The DAG integrity it maintains is what the agreement and ordering invariants (INV-002, INV-007, INV-016) ultimately run on.
- The birth-round *ordering* relation (a child's birth round ≥ each parent's) is INV-012 — a genuine invariant, true by construction rather than by enforcement.
