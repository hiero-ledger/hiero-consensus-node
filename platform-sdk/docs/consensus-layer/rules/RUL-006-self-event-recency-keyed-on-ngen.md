---
type: rule
id: RUL-006
title: The event creator keys latest-self-event tracking on nGen, so a re-received self-ancestor cannot become the self-parent
class: protocol
topics: [event-creator]
components:
  - consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java
related:
  invariants: []
  decisions: [ADR-008]
  scenarios: [SCN-003]
  heuristics: []
status: holds
confidence: high
provenance: elicitation-2026-07-17; #26376 (lastSelfEvent reverted from sequence number to nGen)
curated_by: Kelly Greco (@poulok)
---

# RUL-006 — The event creator keys latest-self-event tracking on nGen, so a re-received self-ancestor cannot become the self-parent

## Statement

In `TipsetEventCreator.registerEvent`, the tracked latest self event
(`lastSelfEvent`) is overwritten by an incoming self event only when that event's
nGen is strictly greater — `lastSelfEvent.hasNGen() && lastSelfEvent.getNGen() <
event.getNGen()` — so a re-received self-ancestor can never replace the true
latest self event and become the self-parent of the next created event.

## Context

`lastSelfEvent` is the self-parent the event creator uses for the next event it
authors (`TipsetEventCreator.maybeCreateUnsignedEvent`). If it is set to a self
event that is *not* the creator's latest — for example an older self-ancestor —
the next event is built off that older self-parent and is not a descendant of the
true latest self event: the creator branches (`concepts/branching.md`), an honest
node equivocating by accident. The ordering key used to decide "which self event
is later" therefore has to reflect the creator's true position in its own self
chain.

## Why it holds now

`registerEvent` compares by nGen (`getNGen`), guarded by `hasNGen()`
(`TipsetEventCreator.java`). Two facts make that correct:

- **nGen approximates graph height, and its error is one-directional.** nGen is
  one plus the maximum tracked-parent nGen; along a non-ancient self chain it
  strictly increases, so a self-ancestor's nGen is below its self-descendant's.
  The one nGen defect — a reset to 1 when an event's parents are already ancient
  (ADR-008) — can only *under*-count height, never over-count it. Because the
  overwrite fires only on a strictly *greater* value, an under-count can never
  cause a spurious overwrite: a graph-lower self event cannot present a higher
  nGen.
- **nGen is stable across re-receipt.** `lastSelfEvent` is the same
  `PlatformEvent` instance from creation onward; the orphan buffer stamps its nGen
  (and sequence number) later, so the guard's `hasNGen()` reflects timing. When a
  node re-receives its own self events via gossip after a reconnect clears the
  orphan buffer, an event's nGen is unchanged — it is derived from graph position,
  not release order.

The orphan-buffer sequence number has neither property for this use: it is a
release-order counter, so a self-ancestor re-released after a buffer clear gets a
*new, higher* number and would satisfy the strictly-greater guard, overwriting
`lastSelfEvent` with an ancestor. Keying this check on the sequence number
did exactly that and caused branching (SCN-003); it was reverted to nGen in #26376.
See [ADR-008](../decisions/ADR-008-replace-ngen-with-sequence-number.md) for the topological-order-vs-height distinction this rests on.

## Change risk

- **Re-keying the comparison to a release-order or re-assignable value.** Most
  concretely the orphan-buffer **sequence number**, which a buffer clear re-numbers
  upward (see *Why it holds now*): a re-received self-ancestor then out-ranks the
  latest and the next created event branches (SCN-003). Any key that is not
  one-directional in its error, or that changes on re-receipt, reintroduces this.
- **Dropping the `hasNGen()` guard.** A just-created self event carries no nGen
  yet — `getNGen()` returns `GENERATION_UNDEFINED` (0) until the orphan buffer
  stamps it. Without the guard, `0 < event.getNGen()` holds for any numbered
  incoming self event (nGen ≥ 1), so the genuine just-created latest is displaced
  by an older, already-numbered self event — a graph-lower self-parent, the
  branching failure this rule guards against.
- **Weakening `<` to `<=`.** The change only affects an nGen *tie*: an ancestor
  has strictly smaller nGen and fails `<=` just as it fails `<`, so no ancestor
  is admitted on a healthy self chain, where nGen strictly
  increases and distinct self events never tie. The hazard is narrow — an nGen
  reset (ADR-008) can collide a graph-lower event's nGen with the latest's (both
  reset to 1), and `<=` then lets that event win a tie the strict `<` keeps shut.
- **Changing nGen so its error is no longer one-directional.** The safety here
  depends on nGen only ever *under*-counting height. A change that let nGen
  *over*-count (rank a lower event above a higher one) would let an ancestor
  win the guard.

Breaking this rule is a **flag for confirmation**. Confirmation looks like
answering: does the new key still guarantee that a genuinely-later self event
always compares strictly greater than any earlier one — including a self-ancestor
re-received after a buffer clear? If yes, the change is safe; if not, it
reintroduces a branching risk.

## Notes

- RUL-002 (intake pipeline flushed in topological order) protects the same
  no-branching property through a different mechanism; SCN-003 is the incident
  this rule guards against.
- The `lastSelfEvent` set on creation (`maybeCreateEvent` → `signEvent`) has no
  nGen yet; `hasNGen()` gates the comparison until the orphan buffer stamps the
  instance, so a freshly-created self event is not spuriously compared against a
  numbered incoming event.
