---
type: architecture-topic
title: Event intake
last_reviewed: 2026-05-15
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
- `flush()` → not a wire but a direct method on `EventIntakeModule`
  ([EventIntakeModule.java:104](../../../../consensus-event-intake/src/main/java/org/hiero/consensus/event/intake/EventIntakeModule.java:104)).
  Drains all in-flight events through the internal components; used by
  callers that need to quiesce the pipeline (e.g. at shutdown or before
  a state-changing operation).

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
> PCES persistence as a mandatory waypoint. PCES is now its own
> module ([`consensus-pces`](../../../../consensus-pces)) rather than a
> stage of event intake. The migration of validation responsibilities
> into the intake module is complete: every event emitted on
> `validatedEventsOutputWire` is fully validated. The transitional
> comment at
> [PlatformWiring.java:75-77](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:75)
> is out of date and will be cleaned up.

## Validation pipeline

The pipeline is built in
[`DefaultEventIntakeModule.initialize`](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java:72)
with five components soldered in series (lines 103-131). Schedulers are configured in
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
  — an indicator that a node is misbehaving by improperly signing the
  same event (not a branching signal) — and lets the event continue.
- **Failure outcome**: duplicate → `null`, plus
  `duplicateEventsPerSecond` and the running `dupEvPercent` metric
  update.
- **Ancient gate**: drops at line 97 if `eventWindow.isAncient(event)`.

**Note on stage ordering:** the deduplicator runs **before** the
signature validator as a performance optimization. The dedup key is the
`(descriptor, signature)` pair, so a duplicate-descriptor event with a
forged signature does not cause the valid event that might be received
later to be dropped. Both are passed on, and the invalid one is rejected
by the signature validator. Running the cheap
dedup check first avoids the cost of signature verification on the
common-case true duplicates.

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

**Note on the RUNTIME bypass:** skipping signature verification for
self-events is a performance optimization. A `RUNTIME` event was just
created, hashed, and signed locally by this node — we trust ourselves,
so re-verifying the signature (and re-hashing) is unnecessary work.
Events received from gossip or replayed from PCES are not tagged
`RUNTIME` and go through the full signature check.

### 5. Orphan buffer (topological ordering)

- **Anchor**: `OrphanBuffer::handleEvent`,
  [DefaultOrphanBuffer.java:105](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:105).
  Sequential scheduler, capacity 500.
- **Role in the pipeline**: re-orders the otherwise-unordered stream
  so that no event is emitted before its non-ancient parents. See
  [Orphan buffer](#orphan-buffer) for internals.

**Why SEQUENTIAL:** the orphan buffer must release events in
topological order, so processing arrivals concurrently is not an
option — the release walk has to run on a single thread to preserve
that order.

## Orphan buffer

The orphan buffer is the topological-ordering stage: it holds back any
event whose non-ancient parents have not yet been emitted, and releases
events only once all such parents are out. Its public surface is
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

> **Delta vs. orphan-buffer.md:** the source doc describes an earlier
> version of the orphan buffer that also performed *linking* —
> attaching each event's parent `PlatformEvent` references — and
> exposed an `EventLinker` class with a `newlyLinkedEvents` queue.
> Linking has since been factored out of the orphan buffer: only the
> consensus algorithm needs linked events, so it is now done
> just-in-time by
> [`ConsensusLinker`](../../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java)
> in the hashgraph module (see [hashgraph.md](./hashgraph.md)). The
> orphan buffer today only enforces topological ordering. The buffer
> also lives in `consensus-utility` (`org.hiero.consensus.orphan`),
> not under the gossip module as the source doc implies.
> Generation-based ancient phrasing is superseded by birth-round
> filtering driven by `EventWindow.isAncient` (see
> [Birth-round filtering](#birth-round-filtering)).

### What it holds

- `eventsWithParents: SequenceMap<EventDescriptorWrapper, PlatformEvent>`
  ([line 59](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:59)) —
  events that have already been released because each of their
  non-ancient parents was either previously released or has aged out
  as ancient.
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

**Why the release-time ancient re-check:**
[`eventIsNotAnOrphan` line 220](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:220)
re-checks `eventWindow.isAncient` on each release even though
`handleEvent` rejects ancient events at the door
([line 106](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:106)).
When `setEventWindow` advances the ancient threshold while events are
buffered, two things can happen and both flow through this same
release walk:

- An orphan was waiting on parents we had never seen, identified only
  by their descriptors on the orphan's event. The window advance
  ages those missing parents out, so the orphan no longer has any
  non-ancient missing parents and is now releasable.
  `missingParentBecameAncient`
  ([line 161](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:161))
  drives this transition.
- A buffered orphan was itself non-ancient when it arrived but has
  since aged out while waiting for parents. It must be dropped at
  release time rather than emitted.

The line 220 check is what distinguishes these two cases on release:
events still non-ancient are emitted, events that have aged out are
dropped.

**Note on `eventSequenceNumber` vs. `assignNGen`:** both fire on
release
([lines 228-229](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:228))
because the orphan buffer is mid-transition. `nGen` (non-deterministic
generation) is the legacy identifier; it has a defect that surfaces in
an edge case during reconnect, which makes it undesirable. The new
monotonic sequence number is its replacement and eliminates that
defect. Both are assigned today so consumers can be migrated
incrementally; once the migration is complete, `assignNGen` will be
removed.

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

|            Stage             |              Role              |                                                                                                                                                                                Anchor                                                                                                                                                                                |
|------------------------------|--------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Deduplicator                 | Door drop and eviction trigger | [StandardEventDeduplicator.java:97](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/deduplication/StandardEventDeduplicator.java:97), [StandardEventDeduplicator.java:132](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/deduplication/StandardEventDeduplicator.java:132) |
| Signature validator          | Door drop                      | [DefaultEventSignatureValidator.java:158](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/signature/DefaultEventSignatureValidator.java:158)                                                                                                                                                                             |
| Orphan buffer (entry)        | Door drop                      | [DefaultOrphanBuffer.java:106](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:106)                                                                                                                                                                                                                                  |
| Orphan buffer (release)      | Re-check at release            | [DefaultOrphanBuffer.java:220](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:220)                                                                                                                                                                                                                                  |
| Orphan buffer (window shift) | Eviction trigger               | [DefaultOrphanBuffer.java:132](../../../../consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java:132)                                                                                                                                                                                                                                  |

The hashgraph layer applies the same filter again as a defensive gate
at link time; that anchor lives in [hashgraph.md](./hashgraph.md).

**On `intakeEventCounter`.** Each drop in the pipeline decrements
`intakeEventCounter` — not because dropping an event is itself a
reason to stop gossiping, but because the counter tracks, per peer,
how many events from a given sync are still in flight through the
intake pipeline. Gossip uses the counter to delay starting the next
sync with that peer until every event the peer sent in the last sync
has either been dropped or made it all the way through to the
shadowgraph. Once an event lands in the shadowgraph, the local node
can advertise it to the peer (so the peer does not re-send it), which
is what makes it safe to sync with that peer again. The counter is
**only used for sync-received events**, not for events received by
broadcast: broadcast-received events are tagged with a sender id of
`-1`, and the pipeline skips counter updates for that sender, so
broadcast traffic neither increments the counter on arrival nor
decrements it on drop. Sync and broadcast as gossip mechanisms are
described in detail in [gossip.md](./gossip.md); the gossip-side
mechanics of the sync delay — and the other, distinct conditions that
legitimately stop gossip — live in
[reasons-not-to-gossip.md](./reasons-not-to-gossip.md).

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

The fourth wire feeds the event creator with persisted events so it
has an up-to-date pool of *other-parent* candidates drawn from peer
events. It does **not** govern self-parent selection: the event creator
tracks its own most recent self-event locally and uses it as the
self-parent of the next self-event whether or not that prior self-event
has been written to PCES yet. The event-creator-side mechanics are
described in [event-creator.md](./event-creator.md); the replay-side
mechanics are in [restart-and-pces.md](./restart-and-pces.md).

The PCES writer's sync behaviour is governed by
`event.preconsensus.inlinePcesSyncOption`, defined at
[PcesConfig.java:91](../../../../consensus-pces/src/main/java/org/hiero/consensus/pces/config/PcesConfig.java:91).
Valid values, from
[FileSyncOption.java](../../../../consensus-pces/src/main/java/org/hiero/consensus/pces/config/FileSyncOption.java):
`EVERY_EVENT`, `EVERY_SELF_EVENT`, `DONT_SYNC`.

> **Delta vs. inlinePces.md:** the source doc states the default is
> `EVERY_SELF_EVENT`. The current default in
> [PcesConfig.java:91](../../../../consensus-pces/src/main/java/org/hiero/consensus/pces/config/PcesConfig.java:91)
> is `DONT_SYNC`, and that is intentional — the source doc is out of
> date. `DONT_SYNC` is sufficient because the OS guarantees buffered
> writes are flushed to disk before JVM shutdown, so PCES's
> crash-recovery and no-branch-on-restart guarantees still hold
> without an explicit per-event fsync. See
> [restart-and-pces.md](./restart-and-pces.md) for the full reasoning.

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
- **Invariants**: INV-011 — every event used in consensus has a verified creator signature; INV-012 — birth round is monotonic along ancestry.
- **Rules**: RUL-004 — consensus intake admits only non-ancient parent links whose claimed birth round matches the actual parent.
- **Decisions**: [TBD: ADR-NNN once decisions/ catalog populates].
- **Scenarios**: [TBD: SCN-NNN — orphan-buffer growth under sustained
  out-of-order arrival, validation-stage-ordering edge cases, and the
  inline-PCES default-mismatch behaviour are likely scenario seeds].

## Future state (sidebar)

The intake module migration is complete: every event emitted on
`validatedEventsOutputWire` is fully validated. The transitional
comment at
[PlatformWiring.java:75-77](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:75)
is out of date and slated for cleanup. The proposal at
`platform-sdk/docs/proposals/consensus-layer/Consensus-Layer.md` is
orientation only; this topic describes what has shipped, not what is
proposed.
