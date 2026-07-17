---
type: scenario
id: SCN-003
title: Self-event recency keyed on the local sequence number picks an older self-parent after a fast reconnect — branching
symptoms: [SYM-003]
topics: [event-creator, reconnect]
kind: historical-incident
verification: test-reproduced
severity: high
related:
  invariants: []
  decisions: [ADR-008]
  scenarios: [SCN-002]
  tests:
    - consensus-otter-tests/src/testOtter/java/org/hiero/otter/test/ReconnectTest.java
status: verified
provenance: hiero-consensus-node#26376 (fix); reproduced by ReconnectTest.testSyntheticBottleneckReconnect
curated_by: Kelly Greco (@poulok)
---

# SCN-003 — Self-event recency keyed on the local sequence number picks an older self-parent after a fast reconnect — branching

## Summary

The event creator tracks its latest self event in `lastSelfEvent` and overwrites
it when a registered self event ranks higher by the local ordering key
(`TipsetEventCreator.registerEvent`). Keyed on the orphan-buffer sequence number,
a fast reconnect let a self-*ancestor*, re-received via gossip after the orphan
buffer had been cleared, get a fresh higher sequence number than the maintained
latest self event, overwrite it, and cause the node to create a new event on an
older self-parent — a branch. Fixed by keying on nGen in #26376; RUL-006 documents
the current rule.

## Setup

**Preconditions:**

- A node that barely falls behind and reconnects very quickly (a synthetic
  bottleneck). The JVM stays up, so the event creator is not reset and
  `lastSelfEvent` is not cleared.
- After reconnect, the node's latest self event and at least one self-ancestor are
  still non-ancient.
- The event creator keying self-event recency on the **sequence number**
  (pre-#26376): `lastSelfEvent.getSequenceNumber() < event.getSequenceNumber()`.

**Trigger:** receiving its own non-ancient self-ancestor back via gossip after the
orphan buffer was cleared on reconnect.

## Sequence

1. `registerEvent` overwrites `lastSelfEvent` only when a registered self event's
   key is **strictly greater** (TipsetEventCreator.java:173–174). (observed — code)
2. `lastSelfEvent` is the same `PlatformEvent` instance from creation onward; the
   orphan buffer stamps its sequence number (and nGen) onto it later, so
   `hasSequenceNumber()` becomes true with timing rather than at creation.
   (observed)
3. The node barely falls behind and reconnects; the orphan buffer is cleared. The
   event creator is not reset, so `lastSelfEvent` still points at the graph-latest
   self event, now carrying its assigned sequence number. (observed / reasoned)
4. A self-ancestor of `lastSelfEvent`, still non-ancient, is received again via
   gossip and re-released by the freshly-cleared orphan buffer, which hands it a
   **new** sequence number. Because the counter is a release-order counter that
   never reflects graph position, that new number is *higher* than the older,
   lower number `lastSelfEvent` carries. (observed / reasoned)
5. `lastSelfEvent.getSequenceNumber() < ancestor.getSequenceNumber()` is true →
   `lastSelfEvent` is overwritten with the self-ancestor. (observed)
6. Before the genuine latest self event is received again, the node creates an
   event on the graph-lower `lastSelfEvent` as self-parent → the new event is not a
   descendant of the true latest self event → a branch. (observed / reasoned)
7. Branch detection logs an `ERROR`; `testSyntheticBottleneckReconnect` fails
   because it permits no `ERROR`-level logs. (observed)

## Observable signature

A branch (self-fork) by an honest node shortly after a fast reconnect (SYM-003);
an `ERROR`-level branch-detection log. In the otter suite,
`ReconnectTest.testSyntheticBottleneckReconnect` fails its no-error-logs
assertion.

## Contributing factors

- **A release-order counter used as a graph height.** Re-receipt after a buffer
  clear re-numbers an old event upward, so "higher sequence number" stopped
  meaning "higher in the graph."
- **A guard that trusts the key's meaning.** The overwrite test assumes the
  ordering key means graph height; the key silently changed meaning across a
  reconnect.
- **An assumption from ADR-008.** The decision assumed the sequence number
  preserves per-creator monotonic ordering because a self-parent leaves the buffer
  before its child — true among events numbered since the last clear, but not
  across one: `clear()` does not reset the counter, so re-receipt after a clear
  re-numbers an old event above the copy released before it.

## Mitigation

Keyed self-event recency on nGen in #26376
(`TipsetEventCreator.registerEvent`, `hasNGen()` / `getNGen()`). RUL-006 is the
rule and states why nGen is safe here; ADR-008 records the reversal. Regression
guard: `ReconnectTest.testSyntheticBottleneckReconnect`.

## Verification

`test-reproduced`. `testSyntheticBottleneckReconnect` drives a fast
(synthetic-bottleneck) reconnect; it fails on the sequence-number key (branch →
`ERROR` log) and passes on nGen.

## Open questions

- Do other consumers of the sequence number assume "higher number = higher in the
  graph" and could break similarly on re-receipt after a buffer clear? Answering
  it: an audit of sequence-number comparisons across the layer.

## Notes

- 2026-07-17 — created from the #26376 fix. `(observed)` steps come from the code
  and the reproducing otter test; `(reasoned)` steps from the sequence-number
  re-release argument. Why nGen is the safe key lives in RUL-006 — Kelly Greco
  (@poulok).
