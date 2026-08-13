---
type: decision
id: ADR-004
title: Retain the OBSERVING Platform Status for Self-Event Recovery
topics: [event-creator, restart-and-pces, platform-status]
related:
  invariants: []
  decisions: [ADR-008]
  scenarios: [SCN-004]
  heuristics: []
  rules: []
status: accepted
date: 2026-06-02
deciders:
  - Kelly Greco (@poulok)
  - Lazar Petrovic (@lpetrovic05)
curated_by: Kelly Greco (@poulok)
last_reviewed: TBD
---

# ADR-004 — Retain the OBSERVING Platform Status for Self-Event Recovery

## Context

The platform status state machine includes an `OBSERVING` status, entered after event replay, in which the node
**gossips but does not create events**. It holds there for a configured window (`platformStatus.observingStatusDelay`,
TUN-019) before advancing to `CHECKING`. The transition path and the event-creation gate that withholds creation until
`ACTIVE` or `CHECKING` are documented in
[`../architecture/topics/platform-status.md`](../architecture/topics/platform-status.md); the specific code anchors are
listed in this ADR's [References](#references).

The purpose of this pause is to give the node a high chance of **learning its latest self event before it starts
creating new ones**, so it does not create a new event off an old self-parent. A node that creates two events sharing
the same self-parent has *branched*. Branching is treated as malicious (Byzantine) behaviour, so it is important that an
honest node not branch even by accident.

### Why the status was introduced

`OBSERVING` predates the current PCES persistence guarantee. At the time, it was possible for a peer
to receive a node's event and hold it in memory before the creating node had persisted it to disk, and for the
creating node to then crash before that write completed. The peer did not need to have persisted the event itself. On restart the creator had no
local record of its latest self event, and the only way to rediscover it was to listen to gossip and have a peer hand it
back. Spending time in `OBSERVING` made that rediscovery likely. The mechanism was probabilistic and imperfect, but it
worked well in practice.

### What changed

PCES now provides a strong guarantee: **every event that was gossiped is also on disk after a graceful shutdown**
(see [`../architecture/topics/restart-and-pces.md`](../architecture/topics/restart-and-pces.md)). For a crash that
shuts down gracefully, a node therefore recovers its latest self event directly from its own PCES files on restart, with
no dependence on peers. Under this guarantee, `OBSERVING` is redundant for that case — the one the status was originally
built to cover.

Two failure modes fall outside the guarantee, and gossip during `OBSERVING` is the only recovery for both:

- **The disk is corrupted or wiped.** The node has no local record of its own history at all. The rest of the network
  almost certainly still holds the crashed node's events — in memory or on disk — and will gossip them back once the node
  rejoins.
- **The shutdown was not graceful.** On `SIGKILL` or loss of host power neither shutdown path runs, so the tail of the
  stream never reaches disk while peers already hold those events. The node replays a self event that is not its latest
  and must relearn the rest. This is the narrower and far more likely of the two, and it is not a hypothetical: SCN-004
  is a node branching because it relearned its missing self event during `OBSERVING` and the event creator discarded it.

In both cases gossip is the *only* way for the node to rediscover its latest self event, and rediscovering it before
resuming event creation is what keeps the recovering node from branching.

## Decision

**Keep the `OBSERVING` status and its behaviour unchanged.** Although PCES has made `OBSERVING` unnecessary for the
gracefully-shut-down crash it was first built for, retain it as the recovery mechanism for the cases PCES does not
cover: a node returning from a crash with a corrupted or wiped disk, or from a shutdown that never flushed the tail of
its stream.

No code changes follow from this decision; it records *why* the existing mechanism stays in place now that its original
justification no longer applies:

- The state machine continues to enter `OBSERVING` after event replay, gossip but not create events while there, and
  exit after `platformStatus.observingStatusDelay` (default `10s`)
  (`ObservingStatusLogic.java:176-187`).
- The event-creation gate continues to withhold creation in `OBSERVING`
  (`PlatformStatusRule.java#isEventCreationPermitted`).
- The default delay stays at `10s` (`PlatformStatusConfig.java#observingStatusDelay`); it remains operator-tunable.

## Limitations

`OBSERVING` is a best-effort, probabilistic safeguard, not a guarantee. It does not ensure the node learns its latest
self event before exiting the status. If no peer still holds the missing event, if the node is partitioned from those
that do, or if the configured delay expires before the event arrives, the node can still resume creation off an old
self-parent and branch. The status lowers the probability of an honest branch after disk loss or an ungraceful shutdown;
it does not eliminate it. Nor is the window sufficient on its own — the event creator must also adopt what it relearns
(ADR-008).

## Consequences

### Positive

- **A recovery path survives for the cases PCES does not cover.** Gossip during `OBSERVING` is the only way a node whose
  disk was wiped, or whose shutdown never flushed the tail of its stream, can rediscover its latest self event before
  creating new events — the difference between an honest restart and an accidental branch.
- **No change risk.** Keeping working code avoids the regression risk of removing a status from the state machine and
  re-threading the transitions around it.
- **Cheap insurance.** The cost is a bounded, configurable startup delay (default `10s`), paid once per node start.

### Negative

- **A now-redundant startup delay in the common case.** For a crash that shut down gracefully, PCES has already restored
  the node's latest self event from local disk by the time `OBSERVING` begins, so the wait no longer protects against
  anything in that (overwhelmingly common) case — it is pure latency on the path to `ACTIVE`. It is not redundant after
  an ungraceful shutdown, which is a good deal more common than the disk loss the status was retained for.
- **The guarantee it provides is weaker than it looks.** As noted under **Limitations**, `OBSERVING` does not guarantee
  self-event recovery after disk loss. Future readers should not treat the status as a hard branch-prevention barrier.
- **The window alone is not sufficient.** Relearning a self event during `OBSERVING` only prevents a branch if the event
  creator then adopts it. SCN-004 is a branch that occurred with the window working exactly as intended; the adoption
  rule is ADR-008's.

### Neutral

- **The rationale has shifted, not the mechanism.** `OBSERVING` was the *primary* safeguard for the ordinary-crash case;
  it is now a *fallback* for disk loss or corruption and for an ungraceful shutdown. The transitions, the gate, and the
  default delay are identical — only the reason for keeping them has changed.
- **The default delay is a soft assumption.** `10s` is assumed to be enough for a peer to gossip back a missing self
  event after disk loss, but nothing enforces or verifies that this is sufficient under real network conditions.

## Alternatives Considered

### 1. Remove the OBSERVING status and rely solely on PCES

Drop `OBSERVING` from the state machine, transition directly from event replay to `CHECKING`, and trust PCES to restore
the latest self event on every restart.

**Rejected because:**

- It leaves no recovery path for the disk-corruption/wipe case. PCES restores nothing when the disk is gone, and gossip
  during `OBSERVING` is the only remaining way for the node to rediscover its self events.
- Without that pause, a disk-wiped node would resume event creation off an old (or empty) self-parent and branch.
  Branching is treated as malicious behaviour, so an honest node branching is a serious outcome to risk for a small
  startup-latency saving.
- The status already exists and works; the cost of keeping it is a bounded startup delay, which is cheap relative to the
  failure it guards against.

### 2. Keep OBSERVING but shorten or skip it when PCES recovery succeeds

Make the delay conditional — e.g. skip or shorten `OBSERVING` when local PCES replay produced the node's recent self
events, and only observe fully when local history is missing.

**Rejected because:**

- A node cannot reliably distinguish "my disk holds everything" from "my disk was wiped or partially corrupted." The
  case that needs `OBSERVING` most is exactly the one where the node has the least trustworthy local signal about its
  own completeness.
- It adds branching logic to the startup path to save, at most, a bounded delay that is already small and tunable. The
  added complexity is not worth the risk of getting the condition wrong in precisely the recovery scenario that matters.

### 3. Keep the OBSERVING status unchanged (selected)

See **Decision** above.

## References

- [`../architecture/topics/platform-status.md`](../architecture/topics/platform-status.md) — the platform status topic;
  describes `OBSERVING` and why the node listens to gossip before creating events.
- [`../architecture/topics/restart-and-pces.md`](../architecture/topics/restart-and-pces.md) — the PCES write/replay
  path and the guarantee that all gossiped events are on disk after a graceful shutdown, which is what made `OBSERVING`
  redundant for that case, and the residual window on `SIGKILL` or power loss, which is what keeps it necessary.
- `platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/status/PlatformStatus.java:38-41` — the
  `OBSERVING` status definition.
- `platform-sdk/consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/logic/ObservingStatusLogic.java:176-187`
  — the exit transition driven by `observingStatusDelay`.
- `platform-sdk/consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/config/PlatformStatusConfig.java#observingStatusDelay` —
  the `observingStatusDelay` config field (default `10s`).
- `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java#isEventCreationPermitted`
  — the event-creation gate that withholds creation while in `OBSERVING`.

## Notes

- 2026-08-12 — broadened the rationale. `OBSERVING` was recorded as a fallback for disk loss alone, on the reading that
  PCES covers every ordinary crash; the durability guarantee holds only for a graceful shutdown, so an ungraceful one
  leaves the same relearn dependency by a far more likely route (SCN-004). Also recorded that the window is not
  sufficient on its own — the event creator must adopt what it relearns. The decision is unchanged — Kelly Greco
  (@poulok).
