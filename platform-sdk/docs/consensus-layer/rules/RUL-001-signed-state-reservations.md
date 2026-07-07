---
type: rule
id: RUL-001
title: A SignedState must remain reserved while any consumer can still access it
class: structural
topics: [signed-state-management]
components:
  - consensus-state/src/main/java/org/hiero/consensus/state/signed/SignedState.java
  - consensus-state/src/main/java/org/hiero/consensus/state/signed/ReservedSignedState.java
  - consensus-state/src/main/java/org/hiero/consensus/state/signed/SignedStateReference.java
  - consensus-state/src/main/java/org/hiero/consensus/state/signed/DefaultStateGarbageCollector.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/SignedStateReserver.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/StateWithHashComplexityReserver.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/StateWithHashComplexityToStateReserver.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/state/nexus/LockFreeStateNexus.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/state/nexus/DefaultLatestCompleteStateNexus.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/state/signed/DefaultStateSignatureCollector.java
related:
  invariants: []
  decisions: []
  scenarios: []
  heuristics: []
status: holds
confidence: high
provenance: extracted from architecture/topics/signed-state-management.md, 2026-05-27
curated_by: Kelly Greco (@poulok)
---

# RUL-001 — A SignedState must remain reserved while any consumer can still access it

## Statement

Every `SignedState` must hold at least one outstanding reservation for as
long as any code path can still read, write, or otherwise interact with
it. "Can still access" includes an in-flight method call holding a
reference, a queued task that has not yet executed, a value sitting in a
holder field, and any derived view of the state's merkle tree.

**Failure to maintain this property is almost always fatal to a node.**

## Why it holds now

`SignedState` lifetime rests on the generic `Reservable` reference-counting
pattern in `org.hiero.base` (`Reservable`, `AbstractReservable`, destroy
callback fires when the count reaches zero). The same pattern governs the
merkle tree, but two independent counters meet at the `SignedState`
boundary:

- The `SignedState`'s own `ReferenceCounter` is what every caller-visible
  reservation increments and decrements. `SignedState.reserve(reason)` and
  `ReservedSignedState.close()` act on this counter; the fan-out
  reservations, holder fields, and wiring-graph mints all live here.
- The underlying merkle root (`VirtualMap`, via `AbstractVirtualRoot
  extends AbstractReservable`) has its *own* `Reservable` counter. The
  `SignedState` constructor takes exactly one reservation on the merkle
  root (`state.getRoot().reserve()`) and holds it for the
  `SignedState`'s entire lifetime.

When the `SignedState`'s counter reaches zero, an `onDestroy` callback
marks the state eligible for deletion and `delete()` runs — synchronously
on the releasing thread, or asynchronously via
`DefaultStateGarbageCollector` for states routed to it. `delete()` calls
`state.release()`, decrementing the merkle root's counter by the one
reservation `SignedState` was holding. **If that release drives the merkle
counter to zero, active destruction runs**: the `VirtualMap`'s `onDestroy`
fires and the tree's native, off-heap, and disk-backed resources are
freed. After that point the state is no longer safe to touch from any
thread.

The failure path is active destruction, not passive Java GC. So the last
`SignedState` reservation must not be released until every consumer that
could still access the state is provably done — anything else is a
use-after-free against a tree whose resources have already been
reclaimed. The reference chain is maintained at every surface where
ownership of a state crosses a boundary:

- **Direct calls.** A `SignedState` parameter is only guaranteed valid
  until the method returns; a callee that needs the state to outlive the
  call takes a fresh reservation via `SignedState.reserve(reason)`. A
  `ReservedSignedState` parameter obligates the receiving method to call
  `close()` — preferably in a try-with-resources block. Prefer a non-null
  `ReservedSignedState` wrapping a `null` value over a `@Nullable`
  parameter. Outside the wiring graph, prefer passing a raw `SignedState`
  unless retention is required.
- **Newly created states.** When constructing a `SignedState`, an
  explicit reservation is taken before the state is passed to any other
  component. No code path relies on an implicit initial reference.
- **Wiring fan-out.** Three `AdvancedTransformation` reservers
  (`SignedStateReserver`, `StateWithHashComplexityReserver`,
  `StateWithHashComplexityToStateReserver`) mint a fresh reservation per
  downstream listener *before* the work item enters the scheduler queue,
  so a state cannot become eligible for deletion while a task waits in
  queue. Their `inputCleanup` releases the upstream reservation only
  after every listener has received its own. See
  [Reservation in the wiring graph](../architecture/topics/signed-state-management.md#reservation-in-the-wiring-graph)
  for the mechanism.
- **Holders.** `LockFreeStateNexus`, `DefaultLatestCompleteStateNexus`,
  and the `incompleteStates` map inside `DefaultStateSignatureCollector`
  retain a reservation while the value is held and release it on
  replacement, `clear()`, or eviction.
- **Concurrent reads.** A single `ReservedSignedState` is not safe to
  read from one thread while another thread calls `close()` on it.
  Threads that need concurrent access either each take their own
  reservation or share a `SignedStateReference`, which manages the
  reservation under thread-safe holder semantics.
- **Reason strings.** Reservations carry a `reason` string passed to
  `reserve(...)`. Choose a string unique enough that a reference-count
  exception can be traced back to the responsible call site by grep.

## Change risk

Two opposite failure modes, both fatal:

**Premature release → use-after-free.** A reservation closed before the
last consumer is finished — closing too early, omitting the per-listener
mint on a new fan-out edge, handing a raw `SignedState` across a
method-return boundary without re-reserving — drives the refcount to
zero while a consumer still holds the state. Active destruction then
runs against a tree that still has live users. Whether the symptom
surfaces is a question of *timing*: the consumer's next access has to
land in the window between the premature release and the destructive
work that follows. Under most schedules the bug is silent, and it may
only manifest when scheduler queueing, GC pauses, or thread interleaving
line up adversarially — days or months after the offending change
merged.

**Missed release → state leak.** A reservation taken but never released
— failing to release in `inputCleanup`, replacing a holder field
without closing the prior reservation, dropping a `ReservedSignedState`
without calling `close()` — keeps the refcount above zero forever. The
state is never destroyed; its merkle data source, off-heap memory, and
backing files are never reclaimed. Leaks accumulate steadily as new
states are produced, so the node grows over time and eventually OOMs.
Slower to surface than UAF, but no less fatal.

When a change touches any of the `components:` files above, the
reviewer's two questions are: *is every reservation eventually released
exactly once?* and *does that release land strictly after the last
consumer access?* If either is not obvious from the diff, treat the
change as a flag for confirmation: the change may be a correct redesign
of the ownership model or an accidental regression, and the two are
indistinguishable until intent is confirmed.

## Notes

- This rule is contingent on reference counting being the lifetime
  mechanism. A redesign that replaces it with another scheme
  (epoch-based reclamation, owning handles, GC integration, etc.) would
  retire this rule rather than violate it.
- Two adjacent operational cautions are kept in the architecture topic
  rather than this rule because they are not about the reservation
  property itself: the merkle reference-count API must not be used to
  pin a `SignedState`, and `state.debugStackTracesEnabled` must never be
  enabled in production.
