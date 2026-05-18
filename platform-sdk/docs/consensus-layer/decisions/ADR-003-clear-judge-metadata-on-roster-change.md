# ADR: Clear Judge Metadata When a Same-Round Judge Is in Ancestry

## Status

Accepted — bug fix implemented.

## Context

### Background: roster changes and the per-round metadata reset

The consensus algorithm was extended to support **roster changes** between rounds. A roster change means the set of
nodes (and their weights) used to calculate consensus differs from one round to the next. Because the roster influences
which events qualify as witnesses and judges (via which events the event "strongly sees"), events that
participate in the next round's calculation cannot keep metadata that was computed against the old roster.

To prepare for a possible roster change, at the start of every new round the platform assigns each event's
`roundCreated` to one of two states:

- **`NEGATIVE_INFINITY` (encoded as `0`)** — a terminal value. The event's metadata is *not* a candidate for
  recalculation under the new roster. This is set when the event cannot meaningfully participate in the next round's
  witness search:
  - **Consensus events**
  - **Ancient events**
  - **Non-consensus events whose `nGen` is below the lowest judge in the latest decided round** — these can never
    become witnesses, so recalculating their `roundCreated` would be wasted work (the only reason to compute
    `roundCreated` is to find witnesses).
- **`ROUND_UNDEFINED` (cleared)** — the event's metadata is wiped so a fresh `roundCreated` can be assigned under the
  new roster. This is what we mean by "clearing" an event's metadata. Under the original rules, this applied to every
  recent event that was not already terminal and was not a judge.

Judges historically were exempt from clearing — they retained their existing `roundCreated`. That exemption is the
source of the bug described below.

### The invariant

The consensus algorithm relies on the following invariant:

> **For every event, `roundCreated(child) >= roundCreated(parent)`.**
> Equivalently, no ancestor may have a `roundCreated` greater than any of its descendants.

This invariant has not been documented previously. If it is broken, the consensus algorithm can **get stuck and never
progress**.

### The bug

Letting all judges keep their `roundCreated` across a roster change can break this invariant.

Concrete walkthrough:

1. Round 4 reaches consensus under roster `R_old`. The set of round-4 judges is fixed.
2. Round 5 is prepared, possibly under a new roster `R_new`. Eligible events have their metadata cleared and
   `roundCreated` is recalculated.
3. Consider a round-4 judge `J2` that has **another round-4 judge `J1` in its ancestry**, and an event `A` on the path
   between them: `J1` is an ancestor of `A`, and `A` is an ancestor of `J2`. The ancestry chain runs `J1 → A → J2`, and
   every event strictly between `J1` and `J2` (including `A`) has `roundCreated = 4` under `R_old`.
4. `A` is not a judge, so its metadata was cleared at the start of the round. Under `R_new`, `A` may now strongly see a
   supermajority of round-4 witnesses that it did not strongly see under `R_old`. The recalculation therefore assigns
   `A` a `roundCreated` of 5.
5. `J2` is a descendant of `A`, but `J2` kept its `roundCreated` of 4 from the original calculation — under the old
   rules judges were exempt from clearing.
6. Now `roundCreated(A) = 5 > roundCreated(J2) = 4`, while `A` is an ancestor of `J2`. The invariant is broken.

Note the structural pattern: `A` sits between two round-4 judges on the same ancestry path. `J1` deeper in the
ancestry is what makes it plausible for an intermediate event to be promoted under a new roster; `J2` shallower in the
ancestry is the descendant judge whose stale `roundCreated = 4` ends up below its recalculated ancestor.

Once the invariant is broken this way, consensus stalls.

### Why the rule targets judges with a same-round judge in their ancestry

The necessary condition for an event `A` to be assigned `roundCreated = 5` is that it strongly sees a supermajority of
round-4 witnesses. Because the next round's roster is not assumed to bear any relationship to `R_old`, we cannot make
any assumption about which subset of round-4 witnesses constitutes a supermajority under the new roster. Conservatively,
**any event that is a descendant of a round-4 judge is a candidate to be promoted to round 5** under some new roster.

To preserve the invariant, every such descendant must have its metadata cleared and recalculated under the new roster.
Under the original rules, every recent non-judge event was already cleared, which covered the vast majority of round-4
judges' descendants. The one class of descendants left uncovered was **round-4 judges that themselves descend from
another round-4 judge** — these were exempted from clearing because they belonged to a decided round.

The new rule fills exactly this gap: clear any round-4 judge that has another round-4 judge in its ancestry, so it is
recalculated like any other descendant.

### Why preserving the remaining round-4 judges is safe (and necessary)

Recalculation needs an anchor. At least one round-4 judge per ancestry chain must keep `roundCreated = 4` so that
descendants can be assigned the correct next round under the new roster. The rule above guarantees this: a round-4 judge with
**no** other round-4 judge in its ancestry is preserved, and every ancestry chain that contains a round-4 judge
necessarily contains an "earliest" one whose ancestry has no other round-4 judge. Recalculation is therefore
well-defined.

## Decision

**At the start of each round, in addition to the existing metadata-clearing rules, also clear the metadata of any judge
that has another judge from the same round in its ancestry.**

A cleared judge is then treated like any other event during recalculation:

- It is assigned a fresh `roundCreated` under the (possibly new) roster.
- It may end up a witness in the same round again — but **no elections are run for that round** because the round is
  already decided.
- Or it may be assigned a higher `roundCreated` (e.g., become a round-5 witness), in which case it participates normally
  in the next round's elections and can again become famous.

This is the minimal change that satisfies a more general principle: every descendant of a round-4 judge must be
eligible for recalculation under the new roster, while at least one round-4 judge per ancestry chain (the earliest, by
definition not a descendant of another round-4 judge) is preserved as an anchor. Together this preserves
`roundCreated(child) >= roundCreated(parent)` across roster changes while leaving the already-decided round's judge set
and ordering untouched.

## Consequences

### Positive

- **Restores the parent/child `roundCreated` invariant** across roster changes, eliminating the bug where
  consensus stalls because a recalculated ancestor's round exceeds a descendant judge's round.
- **Does not invalidate already-decided rounds.** Cleared judges are recalculated, but the decided round's judge set
- are not revisited; no new election is run for the decided round.
- **Localized change.** The fix lives in the metadata-clearing step that already runs at the start of each round; no
  change to the election or fame-decision logic is required.

### Negative

- **A cleared judge no longer appears as a judge.** After clearing and recalculation, a cleared round-4 judge is
  classified as a witness rather than a judge — non-famous in round 4 if the roster does not change (since round 4 is
  already decided and no fame-election is re-run for it), or a round-5 witness if the new roster promotes it. This is a
  debugging-only concern: the GUI shows the recalculated classification, but the consensus outcome of the decided round
  (its judge set and ordering) is preserved.

### Neutral

- The correctness of this fix depends on the (now documented) invariant. The invariant itself should be captured in the
  consensus algorithm documentation alongside this ADR so the rationale for the rule is not lost.

## References

- Related: [consensus layer architecture overview](../architecture/overview.md) — describes how rosters are carried as
  round metadata and which roster applies to which round.
- Related (to be authored): a concept doc capturing the invariant `roundCreated(child) >= roundCreated(parent)` and the
  metadata-clearing rules at the start of each round.

## Authors / Deciders

- Leemon Baird (@lbaird) - decider
- Lazar Petrovic (@lpetrovic) - decider
- Kelly Greco (@poulok) - author
