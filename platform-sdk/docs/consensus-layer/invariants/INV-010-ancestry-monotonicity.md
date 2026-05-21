---
id: INV-010
title: Ancestry is monotone and eventually agreed
class: determinism
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §IV-B + §IV-C end / p. 3
provenance: split-from-INV-007 against §IV-C-end meta-claim + §IV-B definition of ancestry; 2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-010 — Ancestry is monotone and eventually agreed

## Statement
Whether one event is an ancestor of another is a monotone
deterministic conclusion: at any moment an honest node either has
no conclusion (it has not yet received enough of the hashgraph)
or has a conclusion that never changes as its hashgraph grows,
and every honest node eventually reaches the same conclusion.

## Basis
The paper directly names ancestry as the canonical example of
this property in Baird & Luykx 2020, §IV-B / p. 3: "ancestry is
an example of a deterministic function of a hashgraph that at
any given moment either gives no conclusion at all, or it makes a
conclusion that will never change, and all nodes eventually come
to that same conclusion." The worked example given immediately
before (a node concluding that D1 is an ancestor of C6, and a
peer that has D1 but not yet C6 having no conclusion until C6
arrives with all of its ancestors) illustrates the mechanism:
once a node accepts an event via gossip, it has received all of
that event's ancestors (INV-005), and the ancestor sub-DAG is
determined by the event's hash chain. The §IV-C end meta-claim
("All components of the hashgraph consensus algorithm have
mathematical proofs that they have this consistency property")
confirms the property is permanent rather than merely
illustrative.

## Change risk
Any change that lets the ancestry relation between two events in
an honest node's hashgraph change as the hashgraph grows breaks
this invariant. Mechanisms: accepting an event before all of its
ancestors are verified to be present (then later rediscovering
parent edges); deriving ancestry from receive order or sync
metadata rather than from the event's parent hash references;
mutating already-received events. Any such change makes ancestry
a function of mutable state rather than the frozen sub-DAG, and
every downstream conclusion (seeing, strongly-seeing, round,
fame, consensus order) inherits the flip-flopping.

## Notes
- One of six per-component instantiations of the deterministic-
  conclusion meta-property catalogued under INV-007 (the
  umbrella). The other five are INV-011 (seeing), INV-012
  (strongly-seeing), INV-013 (round), INV-014 (witness status),
  INV-015 (round received). Two further components — fame and
  consensus order — are absorbed into INV-008 and INV-002
  respectively.
- Sibling to INV-004 (which establishes cross-node identity of
  the ancestor sub-DAG; this entry establishes intra-node
  monotonicity of the ancestry predicate).
- Load-bearing for INV-011 (seeing is defined on the ancestor
  sub-DAG and on the absence of forks in y's ancestors) and
  indirectly for every conclusion downstream of seeing.
- `status` is [TBD: confirm enforced in current implementation].
