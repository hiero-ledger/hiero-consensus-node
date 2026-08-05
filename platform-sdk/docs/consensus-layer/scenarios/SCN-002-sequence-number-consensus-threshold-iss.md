---
type: scenario
id: SCN-002
title: Consensus-relevant threshold keyed on the sequence number exposes a latent roundCreated bug for a genesis event — ISS
symptoms: [SYM-002]
topics: [hashgraph]
kind: historical-incident
verification: test-reproduced
severity: critical
related:
  invariants: [INV-001, INV-015]
  decisions: [ADR-008]
  scenarios: [SCN-001]
  tests:
    - swirlds-cli/src/test/java/org/hiero/consensus/pcli/MinConsensusRelevantThresholdTest.java
status: verified
provenance: hiero-consensus-node#26319 (interim revert); re-diagnosed to #26529; reproduced by MinConsensusRelevantThresholdTest
curated_by: Kelly Greco (@poulok)
---

# SCN-002 — Consensus-relevant threshold keyed on the sequence number exposes a latent roundCreated bug for a genesis event — ISS

## Summary

ADR-008's consensus stage re-keyed the decided-round short-circuit threshold
(RUL-005) from nGen to the orphan-buffer sequence number. That exposed a **latent
bug** in `roundCreated` assignment (#26529): a genesis (parentless) event that is
not a descendant of the latest decided round's judges was assigned `ROUND_FIRST`
instead of `ROUND_NEGATIVE_INFINITY`, violating INV-015. nGen had masked the bug by
always sorting such an event below the frontier; keyed on the sequence number, the
event sorted above the frontier on one node, reached the buggy branch, and flipped
a decided-round judge's metadata preservation during recalculation — placing events
in different consensus rounds, an ISS. Reverted to nGen in #26319 as a stopgap; the
durable fix is #26529, after which the sequence number is safe here (ADR-008).

## Setup

**Preconditions:**

- A multi-node network. The reproduction replays two nodes (`node0`, `node3`)
  captured from a run that ISSed.
- The consensus algorithm keying its consensus-relevant threshold on the
  orphan-buffer **sequence number** — the ADR-008 consensus migration (#24844):
  `ConsensusRounds.consensusRelevantSeqNum`, `isOlderThanDecidedRoundSeqNum`,
  `RoundElections.minSeqNum`.
- Replay from genesis via PCES, so every node recalculates rounds from the start.

**Trigger:** recalculation (`ConsensusImpl.recalculateAndVote` → `calculateAndVote`
→ `round`) reaching a decided-round judge whose ancestry includes a node's genesis
event and its child, where the sequence-number frontier comparison for those two
events resolved differently across nodes.

## Sequence

1. ADR-008's consensus stage replaced nGen with the sequence number as the
   threshold key. (observed — the merged migration)
2. `ConsensusImpl.round(x)` assigns `ROUND_NEGATIVE_INFINITY` through two
   short-circuits: (a) `x` is below the threshold
   (`isOlderThanDecidedRound…`, ConsensusImpl.java:1141), or (b) all of `x`'s
   non-ancient parents are already `ROUND_NEGATIVE_INFINITY`
   (ConsensusImpl.java:1188). But an event with **no parents** takes neither branch:
   it is assigned `ROUND_FIRST` (=1) (ConsensusImpl.java:1149–1151). That last
   assignment is the latent defect (#26529) — a parentless event that is not a
   descendant of the decided judges must instead be assigned the value every node
   agrees on for it (`ROUND_NEGATIVE_INFINITY` in this implementation; INV-015), and
   with no parents it cannot reach short-circuit (b). (observed — code)
3. The events in question were a node's **genesis event and its child**, in the
   ancestry of a decided-round judge `J` that was *not* the lowest-key judge and
   had no other judge in its ancestry. (observed)
4. **With nGen (masking the defect):** genesis (height 1) and its child had nGen
   below the lowest judge's nGen → short-circuit (a) → both
   `ROUND_NEGATIVE_INFINITY`, on every node. The genesis event never reached the
   parentless `ROUND_FIRST` branch, so the bug stayed invisible. (observed)
5. **With the sequence number (exposing the defect):** on the ISS node their
   sequence numbers were *higher* than the lowest judge's → short-circuit (a) false;
   genesis has no parents → `ROUND_FIRST` (=1) via the buggy branch; the child then
   inherited a real round. Had #26529 been fixed, the parentless genesis would have
   been `ROUND_NEGATIVE_INFINITY` here too, matching the other nodes. (observed)
6. In `recalculateAndVote`, a last-decided-round judge keeps its metadata **iff**
   the maximum round over all its parents is `ROUND_NEGATIVE_INFINITY`
   (ConsensusImpl.java:330–350). With nGen, `J`'s parents all resolved to
   `ROUND_NEGATIVE_INFINITY` → `J` preserved. With the sequence number, `J` had a
   parent with a real round (the mis-assigned child of genesis) → `J` was cleared
   and its round recalculated. (observed)
7. Recalculated, `J` got a different round and stopped being a witness on the ISS
   node. Witness membership propagates upward — a witness must strongly see a
   supermajority of the prior round's witnesses — so the witness set, and then the
   judges decided, diverged into different consensus rounds. (observed / reasoned)
8. Concretely, three empty events landed in **round 3** on the healthy nodes but
   **round 4** on the ISS node; round 3 had fewer consensus events there.
   (observed)
9. Empty events carry no transactions, so they change nothing in state except the
   running event hash — which was therefore the only divergent part of the hashed
   state → ISS. (observed)

## Observable signature

ISS (SYM-002) in which the **only** divergent state component is the running event
hash — pointing at consensus event membership, not transaction handling. A round
with fewer consensus events on the diverging node. In the reproduction, `node0`
and `node3` diverge at round 3, and `RoundInternalEqualityValidation` fails when
the threshold is keyed on the sequence number.

## Contributing factors

- **A latent bug in `roundCreated` for parentless events (#26529).** `round(x)`
  assigns `ROUND_FIRST` to any event with no parents
  (ConsensusImpl.java:1149–1151), even a genesis event that is not a descendant of
  the latest decided round's judges and so must instead take the value every node
  agrees on (`ROUND_NEGATIVE_INFINITY` here; INV-015). The defect was always present;
  nothing before had let a genesis event reach that branch on one node but not
  another.
- **nGen masked the defect.** A genesis event carries `nGen = 1`, always below the
  frontier, so short-circuit (a) sent it to `ROUND_NEGATIVE_INFINITY` before it
  could reach the parentless branch. The bug stayed invisible for as long as the
  threshold was keyed on a graph height.
- **The sequence number exposed it, without being wrong itself.** As a node-local
  release-order counter it sorted the same genesis event above the frontier on the
  ISS node and below it on others. That cross-node difference is harmless on its
  own — a non-descendant collapses to `ROUND_NEGATIVE_INFINITY` whether the frontier
  catches it (short-circuit a) or its parents do (short-circuit b) — *except* for
  the parentless case #26529 mishandles, which has no parents to fall back on.

## Mitigation

Reverted the threshold to nGen in #26319 — a **stopgap** that re-masks the #26529
bug rather than fixing it. RUL-005 documents the current (nGen) rule and its code
anchors. The durable fix is #26529: assign `ROUND_NEGATIVE_INFINITY` to a parentless
event that is not a descendant of the decided judges, restoring INV-015; once it
lands, the threshold can key on the sequence number (ADR-008). Regression guard:
`MinConsensusRelevantThresholdTest` replays the two nodes' PCES and asserts
round-by-round internal equality.

## Verification

`test-reproduced`. `MinConsensusRelevantThresholdTest` (consensus-hashgraph) was built
from the healthy node's and the ISS node's captured PCES; it fails when the
threshold is keyed on the sequence number and passes on nGen. The underlying ISS
was also observed in a live run from genesis before the fix.

## Open questions

- Could the nGen reset defect ever perturb the threshold for an event that is
  *not* below the frontier — an all-parents-ancient event that is still
  consensus-relevant? Believed impossible, because such events sit at or below the
  ancient boundary, which is below the frontier, but not proven here. Answering it:
  a targeted analysis or test of the threshold under a reset event.

## Notes

- 2026-07-17 — created from the #26319 debugging effort. `(observed)` steps come
  from the reproduction (`MinConsensusRelevantThresholdTest`) and the
  merged-then-reverted code; `(reasoned)` steps from the witness/judge propagation
  argument — Kelly Greco (@poulok).
- 2026-07-27 — re-diagnosed: the ISS root cause is a latent `roundCreated` bug for
  parentless events (#26529), not the sequence number; the revert to nGen (#26319)
  re-masks it. Reframed the summary, sequence (steps 2, 4, 5), contributing factors,
  and mitigation; retitled; added INV-015 — Kelly Greco (@poulok).
