---
type: scenario
id: SCN-002
title: Consensus-relevant threshold keyed on the local sequence number diverges the round short-circuit across nodes — ISS
symptoms: [SYM-002]
topics: [hashgraph]
kind: historical-incident
verification: test-reproduced
severity: critical
related:
  invariants: [INV-001]
  decisions: [ADR-008]
  scenarios: [SCN-001]
  tests:
    - swirlds-cli/src/test/java/org/hiero/consensus/pcli/MinConsensusRelevantThresholdTest.java
status: verified
provenance: hiero-consensus-node#26319 (fix); reproduced by MinConsensusRelevantThresholdTest
curated_by: Kelly Greco (@poulok)
---

# SCN-002 — Consensus-relevant threshold keyed on the local sequence number diverges the round short-circuit across nodes — ISS

## Summary

ADR-008's consensus stage re-keyed the decided-round short-circuit threshold
(RUL-005) from nGen to the orphan-buffer sequence number — a release-order counter,
not a graph height. A structurally-low event could then rank below the frontier on
some nodes and above it on others, flipping whether a decided-round judge's
metadata was preserved or recalculated and placing events in different consensus
rounds — an ISS. Reverted to nGen in #26319.

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
   (`isOlderThanDecidedRound…`, ConsensusImpl.java:~1141), or (b) all of `x`'s
   non-ancient parents are already `ROUND_NEGATIVE_INFINITY`
   (ConsensusImpl.java:~1188). An event with no parents instead gets `ROUND_FIRST`
   (=1). (observed — code)
3. The events in question were a node's **genesis event and its child**, in the
   ancestry of a decided-round judge `J` that was *not* the lowest-key judge and
   had no other judge in its ancestry. (observed)
4. **With nGen:** genesis (height 1) and its child had nGen below the lowest
   judge's nGen → short-circuit (a) → both `ROUND_NEGATIVE_INFINITY`, on every
   node. (observed)
5. **With the sequence number:** on the ISS node their sequence numbers were
   *higher* than the lowest judge's → short-circuit (a) false; genesis has no
   parents → `ROUND_FIRST` (=1); the child then inherited a real round. (observed)
6. In `recalculateAndVote`, a last-decided-round judge keeps its metadata **iff**
   the maximum round over all its parents is `ROUND_NEGATIVE_INFINITY`
   (ConsensusImpl.java:330–350). With nGen, `J`'s parents all resolved to
   `ROUND_NEGATIVE_INFINITY` → `J` preserved. With the sequence number, `J` had a
   parent with a real round → `J` was cleared and its round recalculated.
   (observed)
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

- **A height frontier keyed on a non-height value.** The threshold is a
  graph-height boundary, but the sequence number is a release-order counter: a
  structurally-low event received late gets a high number, and that number is
  node-local, so the frontier comparison diverged across nodes.
- **A safety argument that named the wrong property.** The rule at the time leaned
  on "the key is local and never consulted for agreement." True of the value — but
  the comparison feeds the `recalculateAndVote` preservation carve-out (INV-001,
  SCN-001), whose outcome does determine consensus. What mattered was height
  fidelity, not absolute determinism.
- **A migration that did not distinguish its consumers.** ADR-008 lumped together
  consumers needing only a topological order (tipset, sync) and this threshold,
  which needs graph height.

## Mitigation

Reverted the threshold to nGen in #26319. RUL-005 is the current rule and lists
its code anchors and why the nGen reset is benign here; ADR-008 records the
reversal and the graph-height-vs-release-order distinction it rests on. Regression
guard: `MinConsensusRelevantThresholdTest` replays the two nodes' PCES and asserts
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
