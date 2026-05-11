---
title: Event intake
kind: architecture-topic
last_reviewed: TBD
---

# Event intake

## Responsibilities

Event intake takes unordered `PlatformEvent`s arriving from gossip,
PCES replay, or the local event creator, and emits a topologically
ordered stream that downstream stages can persist and feed into the
hashgraph. It owns event hashing (for peer events), four validation
stages, deduplication, orphan buffering, and the per-stage birth-round
"ancient" filter that drops events outside the current event window.

Intake does **not** own:

- The hashgraph algorithm (see [hashgraph.md](./hashgraph.md)).
- The gossip protocol stack (see [gossip.md](./gossip.md)).
- Self-event creation (see [event-creator.md](./event-creator.md)).
- The PCES replay procedure or signed-state internals (see
  [restart-and-pces.md](./restart-and-pces.md)).

The canonical implementation lives in the `consensus-event-intake` /
`consensus-event-intake-impl` modules, with `OrphanBuffer` and
supporting utilities under `consensus-utility`. The public surface is
the `EventIntakeModule` interface; the wiring is built by
`DefaultEventIntakeModule`.

## Inputs and outputs

Intake exposes its inputs and outputs through `EventIntakeModule`
([EventIntakeModule.java:24](../../../../consensus-event-intake/src/main/java/org/hiero/consensus/event/intake/EventIntakeModule.java:24)).
Component soldering happens in
[`PlatformWiring.wire`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:56).

**Inputs**

- `unhashedEventsInputWire()` → `EventHasher::hashEvent`. Two upstream
  sources solder here:
  - Peer events from gossip
    ([PlatformWiring.java:65-68](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:65)).
  - PCES replay on startup
    ([PlatformWiring.java:197-200](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:197)).
- `nonValidatedEventsInputWire()` → `InternalEventValidator::validateEvent`,
  bypassing the hasher. Self-events from the event creator solder here
  ([PlatformWiring.java:129-132](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:129));
  the creator pre-hashes its outputs, so a second hash would be
  wasteful.
- `eventWindowInputWire()` → broadcast to the deduplicator, signature
  validator, and orphan buffer, driving the ancient threshold.
- `rosterHistoryInputWire()` → routes only to the signature validator.
- `clearComponentsInputWire()` → broadcast `clear()` to deduplicator
  and orphan buffer.

**Output**

- `validatedEventsOutputWire()` is the flattened (`getSplitOutput()`)
  output of the orphan buffer
  ([DefaultEventIntakeModule.java:183](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java:183)).
  It solders to:
  - The PCES writer
    ([PlatformWiring.java:78-81](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:78)).
  - The `BranchDetector`
    ([PlatformWiring.java:112-115](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:112)).

> **Delta vs. orphan-buffer.md / sync-protocol.md:** the older docs
> imply a single hand-off path "intake → hashgraph". Today the path is
> "intake → PCES writer → hashgraph + gossip + event creator", with
> PCES persistence as a mandatory waypoint. The `validatedEventsOutputWire`
> name reflects an in-progress migration; the comment at
> [PlatformWiring.java:75-77](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:75)
> notes that some validation responsibilities are still moving into
> the new module.

## Validation pipeline

The pipeline is built in
[`DefaultEventIntakeModule.initialize`](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java:72)
with five components soldered in series (lines 103-131). Scheduler
shapes are configured in
[`EventIntakeWiringConfig`](../../../../consensus-event-intake/src/main/java/org/hiero/consensus/event/intake/config/EventIntakeWiringConfig.java).

### 1. Hashing

- **Anchor**: `EventHasher::hashEvent`,
  [EventHasher.java:18](../../../../consensus-utility/src/main/java/org/hiero/consensus/crypto/EventHasher.java:18);
  default impl `DefaultEventHasher`. Concurrent scheduler.
- **What it does**: computes the event hash and populates the event's
  descriptor.
- **Failure outcome**: pass-through; hashing does not filter events.
- **Note**: self-events bypass this stage by entering at the validator
  via `nonValidatedEventsInputWire()`.

### 2. Internal validation

- **Anchor**: `InternalEventValidator::validateEvent`,
  [DefaultInternalEventValidator.java:39](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/validation/DefaultInternalEventValidator.java:39).
  Concurrent scheduler.
- **What it does**: delegates to
  [`DefaultEventFieldValidator.isValid`](../../../../consensus-utility/src/main/java/org/hiero/consensus/event/validation/DefaultEventFieldValidator.java)
  to check field non-null, field length, transaction byte limits,
  parent descriptor uniqueness, and birth-round consistency.
- **Failure outcome**: returns `null` (drop). Calls
  `intakeEventCounter.eventExitedIntakePipeline(senderId)` and updates
  per-failure metrics inside `DefaultEventFieldValidator`.

[TBD: question for engineer — `InternalEventValidator` does not
short-circuit on `eventWindow.isAncient`, even though the next three
stages do. Is that deliberate (cheap field checks always run) or has
the gate just not been added there?]

### 3. Deduplication

- **Anchor**: `EventDeduplicator::handleEvent`,
  [StandardEventDeduplicator.java:96](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/deduplication/StandardEventDeduplicator.java:96).
  Sequential scheduler, capacity 5000.
- **What it does**: tracks seen `(descriptor, signature)` pairs in a
  birth-round-keyed `SequenceMap`. Drops any event whose
  descriptor+signature has already been seen
  ([line 114](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/deduplication/StandardEventDeduplicator.java:114)).
  When a descriptor is seen with a *new* signature, increments the
  `eventsWithDisparateSignature` accumulator
  ([line 107](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/deduplication/StandardEventDeduplicator.java:107))
  — a soft equivocation/branching signal — and lets the event continue.
- **Failure outcome**: duplicate → `null`, plus
  `duplicateEventsPerSecond` and the running `dupEvPercent` metric
  update.
- **Ancient gate**: drops at line 97 if `eventWindow.isAncient(event)`.

[TBD: question for engineer — the deduplicator runs **before** the
signature validator. A duplicate-descriptor event with a forged
signature would be detected as a duplicate and dropped without the
signature ever being checked. Is the ordering an explicit cost-saving
trade, an artifact of the `eventsWithDisparateSignature` metric semantics,
or something else?]

[TBD: question for engineer — `eventsWithDisparateSignature` increments
on descriptor match + new signature, which on its face looks like a
branching/equivocation indicator. Is this metric purely observability,
or does the downstream `BranchDetector` rely on it?]

### 4. Signature verification

- **Anchor**: `EventSignatureValidator::validateSignature`,
  [DefaultEventSignatureValidator.java:157](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/signature/DefaultEventSignatureValidator.java:157).
  Concurrent scheduler.
- **What it does**: verifies the event's cryptographic signature
  against the creator's public key, looked up in the current
  `RosterHistory`.
- **Failure outcome**: invalid signature → `null` and
  `validationFailedAccumulator.update(1)`
  ([line 173](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/signature/DefaultEventSignatureValidator.java:173)).
- **Bypasses**:
  - Ancient → returns `null` (line 158).
  - `EventOrigin.RUNTIME` (self-events) → returns the event without
    verification (line 164).

[TBD: question for engineer — the RUNTIME bypass at
`DefaultEventSignatureValidator.validateSignature:164` skips signature
verification for self-events. Self-events also enter via
`nonValidatedEventsInputWire`
([PlatformWiring.java:129-132](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:129)),
which ought to mean every event reaching this stage with origin
RUNTIME came from the local creator. Is the bypass purely defensive,
or is there a code path on which a RUNTIME event arrives via the
`unhashedEventsInputWire`?]

### 5. Orphan buffer (linking + ordering)

- **Anchor**: `OrphanBuffer::handleEvent`,
  [DefaultOrphanBuffer.java:105](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:105).
  Sequential scheduler, capacity 500.
- **Role in the pipeline**: re-orders the otherwise-unordered stream
  so that no event is emitted before its non-ancient parents. See
  [Orphan buffer](#orphan-buffer) for internals.

[TBD: question for engineer — the orphan buffer's scheduler is
SEQUENTIAL with capacity 500, while the upstream signature validator is
CONCURRENT. Throughput-wise the buffer is the narrowest link; was
SEQUENTIAL chosen for ordering correctness, lock reduction in the
release walk, or another reason?]

## Orphan buffer

The orphan buffer is the linking stage. Its public surface is
[`OrphanBuffer`](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/OrphanBuffer.java),
implemented by
[`DefaultOrphanBuffer`](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java).
It exposes three methods:

- `handleEvent(PlatformEvent) → List<PlatformEvent>` — buffer or
  release.
- `setEventWindow(EventWindow) → List<PlatformEvent>` — advance the
  ancient threshold; return any orphans that are now releasable
  because their last missing parent just aged out.
- `clear()` — reset internal state.

> **Delta vs. orphan-buffer.md:** the source doc references an
> `EventLinker` class and a `newlyLinkedEvents` field; neither exists in
> current code. Linking is performed inline by
> [`DefaultOrphanBuffer.eventIsNotAnOrphan`](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:205).
> The buffer also lives in `consensus-utility`
> (`org.hiero.consensus.orphan`), not under the gossip module as the
> source doc implies. Generation-based ancient phrasing is superseded
> by birth-round filtering driven by `EventWindow.isAncient` (see
> [Birth-round filtering](#birth-round-filtering)).

### What it holds

- `eventsWithParents: SequenceMap<EventDescriptorWrapper, PlatformEvent>`
  ([line 59](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:59)) —
  events whose parents are linked or have aged out as ancient.
- `missingParentMap: SequenceMap<EventDescriptorWrapper, List<OrphanedEvent>>`
  ([line 65](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:65)) —
  for each missing parent descriptor, the orphans waiting on it.
- `eventSequenceNumber: AtomicLong`
  ([line 78](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:78)) —
  monotonic sequence number assigned at release; the topological-order
  contract for downstream consumers.
- `currentOrphanCount: int` — exposed as the `orphanBufferSize` gauge
  metric ([line 91](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:91)).

Both maps key on birth round via `EventDescriptorWrapper::birthRound`
and shift in lockstep with `EventWindow.ancientThreshold()`.

### Lifecycle

**Arrival** ([handleEvent:105](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:105)):

1. If `eventWindow.isAncient(event)` → drop, decrement intake counter,
   return empty list.
2. Otherwise call
   [`getMissingParents`](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:184),
   which scans `event.getAllParents()` and reports parents that are
   neither in `eventsWithParents` nor already ancient.
3. If no parents are missing → release immediately via
   `eventIsNotAnOrphan`.
4. If parents are missing → wrap in `OrphanedEvent` and index under
   each missing-parent descriptor. Return empty list.

**Release on parent arrival**
([eventIsNotAnOrphan:205](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:205)):

A non-recursive stack walk frees the event plus any descendants whose
last missing parent just resolved. The comment at line 211 records the
explicit choice to avoid recursion ("recursion yields pretty code but
can thrash the stack"). At each release:

- The event is added to `eventsWithParents` (line 227).
- `assignNGen(nonOrphan, eventsWithParents)` assigns a non-deterministic
  generation (line 228).
- A monotonic sequence number is assigned (line 229).
- Children indexed under the descriptor are revisited; any whose
  `missingParents` set is now empty are pushed onto the stack.

**Release on parent becoming ancient**
([missingParentBecameAncient:161](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:161)):

When `setEventWindow` advances the ancient threshold, the
`shiftWindow` callback collects each parent that is now ancient
together with the orphans that were waiting on it. For each such
parent, the orphans drop that parent from their `missingParents` set;
any orphan whose set becomes empty is released through the same
`eventIsNotAnOrphan` walk.

**Eviction**
([setEventWindow:132](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:132)):

Both `eventsWithParents` and `missingParentMap` are sequence-mapped on
birth round and shifted with `eventWindow.ancientThreshold()`. Entries
below the threshold drop; their orphans are released as above (or
themselves dropped if now ancient).

[TBD: question for engineer —
[`eventIsNotAnOrphan` line 220](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:220)
re-checks `eventWindow.isAncient` on each release, even though
`handleEvent` rejects ancient events at the door
([line 106](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:106)).
Is this defensive against a `setEventWindow` landing between buffering
and release, or is there another path on which a buffered event can
become ancient without `setEventWindow` firing?]

[TBD: question for engineer — `eventSequenceNumber` and `assignNGen`
fire on release
([lines 228-229](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:228)).
Which downstream component consumes nGen, and what is the contract:
monotonic in release order, monotonic in topological order, or
something else? Cross-link target should be defined in
[hashgraph.md](./hashgraph.md).]

[TBD: question for engineer —
[`clear` (line 262)](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:262)
resets the maps and `currentOrphanCount` but does not reset
`eventSequenceNumber`. Under what conditions is `clear` called
(reconnect? rebuild?), and is the non-reset of the sequence number an
invariant downstream consumers depend on?]

## Birth-round filtering

The intake-side ancient filter is `EventWindow.isAncient`, fed in
through the broadcast `eventWindowInputWire()` and stored on each
component that uses it. Three intake stages apply it; they share the
same predicate but differ in role:

|            Stage             |        Role         |                                                                                          Anchor                                                                                          |
|------------------------------|---------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Deduplicator                 | Door drop           | [StandardEventDeduplicator.java:97](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/deduplication/StandardEventDeduplicator.java:97)         |
| Signature validator          | Door drop           | [DefaultEventSignatureValidator.java:158](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/signature/DefaultEventSignatureValidator.java:158) |
| Orphan buffer (entry)        | Door drop           | [DefaultOrphanBuffer.java:106](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:106)                                                      |
| Orphan buffer (release)      | Re-check at release | [DefaultOrphanBuffer.java:220](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:220)                                                      |
| Orphan buffer (window shift) | Eviction trigger    | [DefaultOrphanBuffer.java:132](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:132)                                                      |

The hashgraph layer applies the same filter again as a defensive gate
at link time; that anchor lives in [hashgraph.md](./hashgraph.md). Failed
intake-side filters do not produce a "reason not to gossip" — they
simply drop the event and decrement `intakeEventCounter`. See
[reasons-not-to-gossip.md](./reasons-not-to-gossip.md) for the
gossip-side outcomes that *do* feed back into the gossip protocol.

## Durability and handoff

The intake module's output is **not** the consensus engine input. The
PCES writer sits between them, enforcing the durability rule that an
event must be persisted before it is gossiped or fed into consensus.
The wiring is in [PlatformWiring.java:78-96](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:78):

1. `eventIntakeModule().validatedEventsOutputWire()` →
   `pcesModule().eventsToWriteInputWire()`.
2. `pcesModule().writtenEventsOutputWire()` → `hashgraphModule().eventInputWire()`
   ([line 88](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:88));
   the in-source comment reads: "Make sure that an event is persisted
   before being sent to consensus."
3. `pcesModule().writtenEventsOutputWire()` → `gossipModule().eventToGossipInputWire()`
   ([line 93](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:93));
   "Make sure events are persisted before being gossipped."
4. `pcesModule().writtenEventsOutputWire()` → `eventCreatorModule().orderedEventInputWire()`
   ([line 96](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:96));
   "Avoid using events as parents before they are persisted."

The fourth wire is the intake-side enforcement of the inline-PCES rule:
the event creator does not see its own freshly created event back on
its `orderedEventInputWire` until that event has been written through
PCES, so it cannot build a successor self-event on top of an
unpersisted self-event. The event-creator-side mechanics are described
in [event-creator.md](./event-creator.md); the replay-side mechanics
are in [restart-and-pces.md](./restart-and-pces.md).

The PCES writer's sync behaviour is governed by
`event.preconsensus.inlinePcesSyncOption`, defined at
[PcesConfig.java:91](../../../../consensus-pces/src/main/java/org/hiero/consensus/pces/config/PcesConfig.java:91).
Valid values, from
[FileSyncOption.java](../../../../consensus-pces/src/main/java/org/hiero/consensus/pces/config/FileSyncOption.java):
`EVERY_EVENT`, `EVERY_SELF_EVENT`, `DONT_SYNC`.

> **Delta vs. inlinePces.md:** the source doc states the default for
> `event.preconsensus.inlinePcesSyncOption` is `EVERY_SELF_EVENT`. In
> current code
> ([PcesConfig.java:91](../../../../consensus-pces/src/main/java/org/hiero/consensus/pces/config/PcesConfig.java:91))
> the default is `DONT_SYNC`.

[TBD: question for engineer — `PcesConfig.java:91` defaults
`inlinePcesSyncOption` to `DONT_SYNC`, but `inlinePces.md` says the
default is `EVERY_SELF_EVENT`. Which is correct for current production
deployments? If `DONT_SYNC` is intentional, what guarantees the
no-branch-on-restart property the inline-PCES design requires — is
durability enforced elsewhere (e.g. at file rotation), or is the
guarantee weakened from "fsync per self-event" to something coarser?]

## Backpressure interaction

Each intake stage runs on a bounded scheduler defined in
[`EventIntakeWiringConfig`](../../../../consensus-event-intake/src/main/java/org/hiero/consensus/event/intake/config/EventIntakeWiringConfig.java);
the orphan buffer is the narrowest at sequential, capacity 500. When
queues fill, the wiring framework propagates backpressure upstream to
gossip and the event creator. The platform health monitor observes
queue saturation and feeds upstream throttling decisions; details live
in [health-monitor-and-backpressure.md](./health-monitor-and-backpressure.md).

## Cross-references

- **Topics**: [gossip.md](./gossip.md),
  [hashgraph.md](./hashgraph.md),
  [event-creator.md](./event-creator.md),
  [restart-and-pces.md](./restart-and-pces.md),
  [health-monitor-and-backpressure.md](./health-monitor-and-backpressure.md),
  [reasons-not-to-gossip.md](./reasons-not-to-gossip.md).
- **Source docs**:
  - `platform-sdk/docs/core/gossip/OOG/orphan-buffer.md` — superseded;
    delta callout under [Orphan buffer](#orphan-buffer).
  - `platform-sdk/docs/core/inlinePces/inlinePces.md` — current in
    spirit; default-value delta callout under
    [Durability and handoff](#durability-and-handoff).
  - `platform-sdk/docs/core/gossip/syncing/sync-protocol.md` —
    orientation only; the protocol detail belongs in
    [gossip.md](./gossip.md).
- **Invariants**: [TBD: INV-NNN once invariants.md catalog populates].
- **Decisions**: [TBD: ADR-NNN once decisions/ catalog populates].
- **Scenarios**: [TBD: SCN-NNN — orphan-buffer growth under sustained
  out-of-order arrival, validation-stage-ordering edge cases, and the
  inline-PCES default-mismatch behaviour are likely scenario seeds].

## Future state (sidebar)

The intake module is mid-migration. The comment at
[PlatformWiring.java:75-77](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:75)
records that the `validatedEventsOutputWire` is currently a transitional
surface — some validation-adjacent responsibilities still live in the
hashgraph layer and are expected to move under intake. The proposal at
`platform-sdk/docs/proposals/consensus-layer/Consensus-Layer.md` is
orientation only; this topic describes what has shipped, not what is
proposed.
