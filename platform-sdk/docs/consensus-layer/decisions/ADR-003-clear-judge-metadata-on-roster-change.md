# ADR: Clear Judge Metadata When a Same-Round Judge Is in Ancestry

## Status

Accepted — bug fix implemented.

## Context

### Background: roster changes and the per-round metadata reset

The consensus algorithm was extended to support **roster changes** between rounds. A roster change means the set of
nodes (and their weights) used to calculate consensus differs from one round to the next. Because the roster influences
which events qualify as witnesses and judges (via which events the event "strongly sees"), events that
participate in the next round's calculation cannot keep metadata that was computed against the old roster.

To prepare for a possible roster change, at the start of every new round the platform clears metadata for events that
will participate in the next round's recalculation. The rules are:

- **Consensus and ancient events** have their `roundCreated` cleared to `NEGATIVE_INFINITY` (encoded as `0`).
- **Non-consensus events whose `nGen` is below the lowest judge in the latest decided round** also have their
  `roundCreated` cleared to `NEGATIVE_INFINITY`. This is an optimization: those events cannot become witnesses in the
  next round, so there is no need to recalculate their metadata.
- **Judges** historically kept their `roundCreated`, since the round they belong to is already decided.

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

The structural ingredient is that `A` sits between two round-4 judges on the same ancestry path. `J1` deeper in the
ancestry is what creates the possibility for an intermediate event to be promoted under a new roster; `J2` shallower
in the ancestry is the descendant judge whose stale `roundCreated = 4` ends up below its recalculated ancestor. This is
why the fix targets exactly those judges (like `J2`) that have another judge from the same round in their ancestry —
their metadata must be cleared so they can be recalculated alongside the events between them and `J1`.

Once the invariant is broken this way, consensus stalls.

### Why the rule targets judges with a same-round judge in their ancestry

The necessary condition for an event `A` to be assigned `roundCreated = 5` is that it strongly sees a supermajority of
round-4 witnesses. Because the next round's roster is not assumed to bear any relationship to `R_old`, we cannot make
any assumption about which subset of round-4 witnesses constitutes a supermajority under the new roster. Conservatively,
**any event that is a descendant of a round-4 judge is a candidate to be promoted to round 5** under some new roster.

To preserve the invariant, the metadata of every such descendant must therefore be cleared and recalculated under the
new roster. The existing clearing rules (consensus events, ancient events, low-`nGen` non-consensus events) already
cover most descendants of round-4 judges. The one descendant they do not cover is **a round-4 judge that itself descends
from another round-4 judge** — that judge was previously exempted from clearing because it belongs to a decided round.

The new rule fills exactly this gap: clear any round-4 judge that has another round-4 judge in its ancestry, so it is
recalculated like any other descendant.

### Why preserving the remaining round-4 judges is safe (and necessary)

Recalculation needs an anchor. At least one round-4 judge per ancestry chain must keep `roundCreated = 4` so that
descendants can be assigned consistent rounds under the new roster. The rule above guarantees this: a round-4 judge with
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

- **Restores the parent/child `roundCreated` invariant** across roster changes, eliminating the class of bugs where
  consensus stalls because a recalculated ancestor's round exceeds a descendant judge's round.
- **Does not invalidate already-decided rounds.** Cleared judges are recalculated, but the decided round's judge set and
  ordering are not revisited; no new election is run for the decided round.
- **Localized change.** The fix lives in the metadata-clearing step that already runs at the start of each round; no
  change to the election or fame-decision logic is required.

### Negative

- **Slightly more recalculation work per round** along ancestry paths that contain stacked same-round judges. In
  practice this is a small fraction of judges in a round.
- **A judge's `roundCreated` is no longer stable across a roster change** for judges that fall under this rule.
  Downstream code that reads judge `roundCreated` must tolerate that a cleared judge may be re-classified as a witness
  in a later round.

### Neutral

- The correctness of this fix depends on the (now documented) invariant. The invariant itself should be captured in the
  consensus algorithm documentation alongside this ADR so the rationale for the rule is not lost.

## Alternatives Considered

### 1. Clear metadata on all judges every round

Wipe all judge metadata at every new round, including the "earliest" round-4 judges in each ancestry chain.

**Rejected because:**

- **Removes the anchor that recalculation depends on.** Recalculation under the new roster needs at least one round-4
  judge per ancestry chain to keep `roundCreated = 4` so that descendants can be assigned consistent rounds. Clearing
  every round-4 judge leaves the recalculation with no anchor.
- Even setting the anchor requirement aside, clearing the earliest round-4 judges does no useful work — those judges are
  not descendants of any round-4 judge, so they cannot be the source of an invariant violation under any new roster.

### 2. Detect and repair invariant violations after recalculation

Run the existing recalculation, then scan for `roundCreated(parent) > roundCreated(child)` and fix it post hoc.

**Rejected because:**

- Detect-and-repair is more complex and error-prone than preventing the violation up front.
- It is unclear what a safe "repair" looks like once the invariant has been broken — bumping the descendant judge's
  round retroactively could disturb an already-decided outcome.
- Adds an extra full-graph pass per round.

### 3. Forbid roster changes that could produce the bug

Block roster changes whose delta could cause an ancestor to gain enough strongly-seen witnesses to be promoted across
rounds.

**Rejected because:**

- Such a precondition is expensive to compute, would meaningfully constrain when rosters may change, and conflates
  roster-change policy with a correctness fix that belongs in the consensus algorithm.

### 4. Status quo — keep judge metadata across roster changes

Previously in effect; superseded by this decision after the bug was identified.

## References

- Related: [consensus layer architecture overview](../architecture/overview.md) — describes how rosters are carried as
  round metadata and which roster applies to which round.
- Related (to be authored): a concept doc capturing the invariant `roundCreated(child) >= roundCreated(parent)` and the
  metadata-clearing rules at the start of each round.

## Authors / Deciders

- Leemon Baird (@lbaird) - decider
- Lazar Petrovic (@lpetrovic) - decider
- Kelly Greco (@poulok) - author
