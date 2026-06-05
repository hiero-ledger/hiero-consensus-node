---
title: Quiescence
kind: architecture-topic
last_reviewed: TBD
---

# Quiescence

Quiescence is an opt-in feature that pauses self-event creation while a
network has no work to do, so an idle network stops producing empty events
and the blocks that would carry them. The specification is
[HIP-1238](#cross-references) (*Network Quiescence*); this file documents
what the consensus layer actually does today and where the rest of the
feature lives outside the consensus layer.

The division of labor is the key thing to hold onto. **Deciding** whether
to quiesce — counting outstanding user transactions, ignoring block- and
state-signature transactions, and tracking deadlines in consensus time —
happens entirely on the Execution side. The consensus layer never makes
that decision. Execution reduces the whole question to a single
[`QuiescenceCommand`](../../../../consensus-model/src/main/java/org/hiero/consensus/model/quiescence/QuiescenceCommand.java)
(`QUIESCE`, `BREAK_QUIESCENCE`, `DONT_QUIESCE`) and pushes it across the
boundary; the consensus layer's job is to **obey** the latest command —
pause or resume event creation, and handle the one edge case where a lone
node must create an event even though the tipset algorithm would not.

## Responsibilities

This topic covers how a `QuiescenceCommand` enters the consensus layer,
what changes in event creation while the command is `QUIESCE`, how the
node leaves that state, and how quiescence is (and is not) reflected in
platform status. It does **not** cover how Execution decides which command
to send.

- Owns: routing the command from the boundary to the event creator and the
  platform monitor; gating self-event creation on the command
  (`QuiescenceRule`); the quiescence-breaker event exception in the
  tipset creator; the way a quiescing node holds its platform status.
- Does not own: the quiescence **conditions** — the transaction counting,
  signature-transaction filtering, and Target Consensus Timestamp (TCT)
  tracking that decide the command. These are Execution concerns
  (`QuiescenceController` and friends in `hedera-node/hedera-app`), out of
  scope here; see
  [`../interfaces/consensus-execution-boundary.md`](../interfaces/consensus-execution-boundary.md).
- Does not own: gossip, which continues unchanged while quiescing (see
  [Behaviour during quiescence](#behaviour-during-quiescence)); event
  intake, likewise unchanged; the freeze procedure, which today stops event
  creation through its own mechanism, separate from quiescence (see
  [`freeze-and-upgrade.md`](freeze-and-upgrade.md)).

One cross-boundary contract supports Execution's counting and is worth
naming here: every pre-consensus event the consensus layer hands to
Execution must eventually be reported back as either a consensus event or
a stale event, so that Execution's transaction counter stays balanced.
Consensus is the central point that reports stale events. The mechanics of
that contract belong to [`../../concepts/stale-events.md`](../../concepts/stale-events.md);
quiescence relies on it but does not define it.

## State

The consensus layer holds no detection state for quiescence — no
transaction counters, no TCTs. The only consensus-side state is the
**latest command**, cached in the three places that need to react to it:

- [`QuiescenceRule`](../../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/QuiescenceRule.java)`#quiescenceCommand`
  — the field the event-creation rule chain reads. Initialized to
  `DONT_QUIESCE`.
- [`TipsetEventCreator`](../../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java)`#quiescenceCommand`,
  plus a `breakQuiescenceEventCreated` boolean that lets at most one
  quiescence-breaker event be created per quiescence period.
- [`DefaultPlatformMonitor`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/DefaultPlatformMonitor.java)`#lastQuiescenceCommand`
  and `#lastQuiescenceCommandTime` — the monitor records the current
  command and the wall-clock instant it last changed, for use by the
  status state machine.

There is no consensus-side "are we quiescing?" flag beyond "is the latest
command `QUIESCE`". The platform's contract is last-command-wins: per
[`Platform`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/Platform.java)`#quiescenceCommand`,
the latest command provided is the one used, with no ordering guarantee if
multiple threads call concurrently.

## Entry

There is no consensus-side trigger for entering quiescence. Entry is
simply the arrival of a `QUIESCE` command at the boundary:

1. Execution calls
   [`Platform`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/Platform.java)`#quiescenceCommand(QuiescenceCommand)`.
2. [`PlatformCoordinator`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformCoordinator.java)`#quiescenceCommand`
   fans the command out on two wires: to
   [`PlatformMonitor`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/PlatformMonitor.java)
   and to the event-creator module.
3. [`DefaultEventCreationManager`](../../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/DefaultEventCreationManager.java)`#quiescenceCommand`
   forwards it to both the `QuiescenceRule` and the `TipsetEventCreator`.

The conditions that produce a `QUIESCE` command — HIP-1238's Rule 1
(no outstanding user transactions), Rule 2 (signature transactions are not
counted), and Rule 3 (no TCT within `tctDuration`) — are evaluated on the
Execution side and are not visible to the consensus layer. The
`tctDuration` and the feature's enable flag are Execution configuration
(`QuiescenceConfig` in `hedera-config`, disabled by default), not
consensus-layer tunables. The one consensus-layer tunable in play is
[`platformStatus.activeStatusDelay`](../../tunables.md) (TUN-020), which
governs the status timing on exit, not entry.

> **See spec — HIP-1238 §Quiescing.** The HIP lists the three quiescence
> conditions and the three events that break quiescence. In current code
> those conditions are Execution's responsibility; the consensus layer
> sees only the resulting command.

## Behaviour during quiescence

### Event creation

While the command is `QUIESCE`, self-event creation is blocked.
[`QuiescenceRule`](../../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/QuiescenceRule.java)`#isEventCreationPermitted`
is the gate:

```java
return quiescenceCommand != QuiescenceCommand.QUIESCE;
```

The rule is one link in the aggregated `EventCreationRule` chain (see
[`event-creator.md`](event-creator.md)); when it denies creation it reports
`EventCreationStatus.QUIESCENCE`. Independently,
[`TipsetEventCreator`](../../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java)`#maybeCreateEvent`
returns `null` immediately when the command is `QUIESCE`, so no event is
built even if the rule chain were bypassed.

### Gossip

Gossip continues unchanged while quiescing. The `QuiescenceCommand`
javadoc is explicit that a quiescing node "will still gossip," and there
is no quiescence branch anywhere in `consensus-gossip-impl`. A quiescing
node still syncs with peers, relays events, and receives events — which is
how a quiescence-breaking event from one node propagates and pulls the rest
of the network back out. Quiescence is therefore **not** one of the
[reasons-not-to-gossip](reasons-not-to-gossip.md); gossip and event
creation are gated separately.

### Event intake

Event intake is likewise unchanged: there is no quiescence gating in
`consensus-event-intake-impl`. Incoming events are validated, ordered, and
fed onward exactly as in normal operation. A received event carrying user
transactions does not change consensus behaviour directly; it is Execution
that observes the new work and responds by sending `DONT_QUIESCE` (or
`BREAK_QUIESCENCE`), which is what actually resumes creation.

### Platform status

A quiescing node holds platform status `ACTIVE`; no dedicated quiescence
status exists. The mechanism:
[`DefaultPlatformMonitor`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/DefaultPlatformMonitor.java)`#heartbeat`
stamps each `TimeElapsedAction` with a
[`TimeElapsedAction.QuiescingStatus`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/actions/TimeElapsedAction.java)
record (`isQuiescing = lastQuiescenceCommand == QUIESCE`, plus the instant
the command last changed). In
[`ActiveStatusLogic`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/logic/ActiveStatusLogic.java)`#processTimeElapsedAction`,
while `isQuiescing` is true the node stays `ACTIVE` regardless of how long
it has been since one of its own events reached consensus — which would
otherwise drop it to `CHECKING`.

## Exit

The consensus layer leaves quiescence when a new command supersedes
`QUIESCE`. There are two shapes, both routed exactly as in
[Entry](#entry):

- **`DONT_QUIESCE`** — the whole network is resuming. The `QuiescenceRule`
  permits creation again and `TipsetEventCreator#maybeCreateEvent` resumes
  building ordinary tipset events. No special event is needed.
- **`BREAK_QUIESCENCE`** — this node alone has a transaction while its
  peers stay quiescing. With every peer idle the tipset algorithm may
  offer no advancing other-parent, so no ordinary event can be built.
  [`TipsetEventCreator`](../../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java)`#maybeCreateEvent`
  then builds a **quiescence-breaker (QB)** via
  `#createQuiescenceBreakEvent`, sets `breakQuiescenceEventCreated` so
  only one is made this period, and relies on gossip to carry it out so
  peers resume. The flag clears once ordinary creation resumes.

The QB is built on a **single self-parent only**, with no other-parent —
the simplest event that still propagates the waiting transactions.

On exit, platform status returns to normal via the grace period in
[`ActiveStatusLogic`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/logic/ActiveStatusLogic.java)`#processTimeElapsedAction`:
once `isQuiescing` is false, the node still stays `ACTIVE` until
`activeStatusDelay` (TUN-020) has elapsed since the stop command — giving a
freshly created post-quiescence event time to reach consensus — after which
the ordinary `ACTIVE` → `CHECKING` test resumes (drop to `CHECKING` if no
self event has reached consensus within `activeStatusDelay`). A node that
has just broken quiescence therefore typically stays `ACTIVE`, and only
slips to `CHECKING` if its events are not reaching consensus.

## Rationale

Quiescence was motivated by small, low-traffic networks that run at a high
round rate: without the feature they continuously produce blocks with
nothing in them, which is pure bandwidth, CPU, and long-term storage cost.
Pausing event creation when there is genuinely nothing to order removes
that waste with no effect on end users, who simply see the network resume
when they submit work.

The decision to keep all detection on the Execution side — counting
transactions, ignoring signature transactions, tracking TCTs — was driven
by where the necessary knowledge lives. To consensus, a transaction is
opaque bytes; only Execution can tell a user transaction from a
block- or state-signature transaction, and signature transactions must be
excluded or the network would never settle (each signed block produces more
signatures to sign). Concentrating the conditions in Execution also keeps
the consensus / execution boundary small: a single command rather than
several new cross-module touchpoints.

The choice not to introduce a dedicated quiescence platform status was made
because the status enum is already a single, heavily-relied-upon flag, with
much of the system keying off `ACTIVE`. Adding a quiescing state caused the
status transitions to multiply and produced awkward combinations (a node
that creates no events would otherwise drift into `CHECKING`, which
operators read as a fault). Keeping a quiescing node `ACTIVE` and carrying
the quiescence signal alongside the status, rather than inside it, avoided
that.

Finally, the quiescence-breaker exception exists for one specific
deadlock: if a single node receives a transaction while every peer is
quiescing, the tipset algorithm — which normally insists that a new
event advance consensus — would leave that node unable to create any
event, and the transaction would strand. The quiescence-breaker is the
deliberate exception that lets such a node emit an event regardless, so
the rest of the network observes the work and resumes.

## Cross-references

Topics:

- [`event-creator.md`](event-creator.md) — the `EventCreationRule` chain
  and `TipsetEventCreator`, where the quiescence gate and the
  quiescence-breaker event live.
- [`gossip.md`](gossip.md) — gossip continues throughout quiescence and
  carries the quiescence-breaker event that breaks it.
- [`event-intake.md`](event-intake.md) — intake is unchanged while
  quiescing.
- [`reasons-not-to-gossip.md`](reasons-not-to-gossip.md) — quiescence is
  **not** among them; event creation and gossip are gated separately.
- [`freeze-and-upgrade.md`](freeze-and-upgrade.md) — freeze also halts
  event creation, but through a separate mechanism today; the two are not
  unified.
- [`reconnect.md`](reconnect.md) — a reconnecting node resumes from a
  peer's recent state; re-establishing quiescence detection afterwards is
  Execution's concern, not the consensus layer's.

Concepts:

- [`../../concepts/stale-events.md`](../../concepts/stale-events.md) — the
  consensus → execution reporting contract that keeps Execution's
  transaction count balanced.

Interface:

- [`../interfaces/consensus-execution-boundary.md`](../interfaces/consensus-execution-boundary.md)
  — `Platform#quiescenceCommand` is the boundary operation; the
  `QuiescenceController` and the quiescence conditions live on the
  Execution side of this seam.

Spec:

- HIP-1238, *Network Quiescence* — the authoritative specification of
  intended behaviour: <https://hips.hedera.com/#hip-1238>.

Pending catalogs:

- Invariants — see [`../../invariants/`](../../invariants/); no entry tagged to this topic yet.
- Decisions — see [`../../decisions/`](../../decisions/); no entry tagged
  to this topic yet. The "detection lives on Execution" and "no dedicated
  quiescence status" choices are decision seeds.
- Scenarios — [TBD: SCN-NNN — quiescence entry/exit edge cases (lone-node
  break, quiescence-breaker propagation, status timing on resume) are
  likely scenario seeds].
