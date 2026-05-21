---
id: INV-006
title: No event can strongly see both branches of a fork
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §IV-C / p. 3
provenance: paper-extraction-2026-05-20; cross-refs-updated-2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-006 — No event can strongly see both branches of a fork

## Statement
For any pair of events y and z that form a fork (same creator,
neither an ancestor of the other), no event x can strongly see
both y and z.

## Basis
Strong seeing requires that x can see more than 2n/3 events by
distinct nodes, each of which can see the target (Baird & Luykx
2020, §IV-C / p. 3). If x strongly saw both y and z, the two
strong-seeing witness sets would overlap in more than n/3 distinct
nodes. Since fewer than n/3 nodes are malicious (§III / p. 2), at
least one honest node sits in the overlap. But an honest node
cannot see both branches of a fork by the same creator — seeing
excludes paths that pass through a creator's fork. The two
strong-seeings therefore cannot coexist. The paper states the
conclusion directly: "If y and z are on different branches of a
fork, then x can strongly see either y or z, but not both."

## Change risk
Any change that weakens the strong-seeing threshold below the
>2n/3 / >n/3 overlap argument — for example, lowering the
distinct-node count required for strong-seeing, or letting events
authored by the same forking creator count toward the threshold —
breaks this invariant. The downstream effect: an attacker who
forks an event can make different honest nodes strongly see
different branches, virtual voting diverges, fame decisions
diverge, and consensus splits.

## Notes
- Depends on the >2n/3 honest assumption from the §III threat
  model. The property is a protocol guarantee given that
  assumption; weakening the assumption is out of scope.
- Sibling to INV-012 (strongly-seeing monotonicity). This entry
  constrains strongly-seeing across forks (no event strongly sees
  both branches); INV-012 constrains strongly-seeing across time
  (the conclusion never flips as the hashgraph grows). Together
  they pin down strongly-seeing as both unambiguous on
  fork-shaped inputs and permanent once concluded.
- `status` is [TBD: confirm enforced in current implementation].
