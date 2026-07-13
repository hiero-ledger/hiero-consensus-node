---
type: decision
id: ADR-004
title: Retain the OBSERVING Platform Status for Self-Event Recovery After Disk Loss
topics: [event-creation, pces, platform-status]
related:
  invariants: []
  decisions: []
  scenarios: []
  heuristics: []
  rules: []
status: accepted
date: 2026-06-02
deciders:
  - Kelly Greco (@poulok)
  - Lazar Petrovic (@lpetrovic05)
curated_by: Kelly Greco (@poulok)
---

# ADR-004 — Retain the OBSERVING Platform Status for Self-Event Recovery After Disk Loss

## Context

The platform status state machine includes an `OBSERVING` status
(`platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/status/PlatformStatus.java:38-41`). On startup,
after replaying events, the platform transitions into `OBSERVING`
(`ReplayingEventsStatusLogic` → `OBSERVING`), where it **gossips but does not create events**: the event-creation gate
permits creation only in `ACTIVE` or `CHECKING`
(`platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java:37-45`).
The node remains in `OBSERVING` for a configured span of wall-clock time — `platformStatus.observingStatusDelay`,
default `10s` (`platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/config/PlatformStatusConfig.java:23`) —
then transitions to `CHECKING` (or `FREEZING` if a freeze boundary was crossed while observing)
(`platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/status/logic/ObservingStatusLogic.java:176-187`).

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

PCES now provides a strong guarantee: **every event that was gossiped is also on disk after JVM shutdown**
(see [`../architecture/topics/restart-and-pces.md`](../architecture/topics/restart-and-pces.md)). For an ordinary crash,
a node therefore recovers its latest self event directly from its own PCES files on restart, with no dependence on
peers. Under this guarantee, `OBSERVING` is redundant for the failure mode it was originally built to cover.

There is, however, one failure mode PCES cannot cover: a node crashes **and its disk is corrupted or wiped**. The node
then has no local record of its own history. In this case the rest of the network almost certainly still holds the
crashed node's events — in memory or on disk — and will gossip them back once the node rejoins. Gossip is the *only* way
for such a node to rediscover its latest self event, and rediscovering it before resuming event creation is what keeps
the recovering node from branching.

## Decision

**Keep the `OBSERVING` status and its behaviour unchanged.** Although PCES has made `OBSERVING` unnecessary for the
ordinary-crash case it was first built for, retain it as the recovery mechanism for the rare case where a node returns
from a crash with a corrupted or wiped disk.

No code changes follow from this decision; it records *why* the existing mechanism stays in place now that its original
justification no longer applies:

- The state machine continues to enter `OBSERVING` after event replay, gossip but not create events while there, and
  exit after `platformStatus.observingStatusDelay` (default `10s`)
  (`ObservingStatusLogic.java:176-187`).
- The event-creation gate continues to withhold creation in `OBSERVING`
  (`PlatformStatusRule.java:37-45`).
- The default delay stays at `10s` (`PlatformStatusConfig.java:23`); it remains operator-tunable.

## Limitations

`OBSERVING` is a best-effort, probabilistic safeguard, not a guarantee. It does not ensure the node learns its latest
self event before exiting the status. If no peer still holds the missing event, if the node is partitioned from those
that do, or if the configured delay expires before the event arrives, the node can still resume creation off an old
self-parent and branch. The status lowers the probability of an honest branch after disk loss; it does not eliminate it.

## Consequences

### Positive

- **A recovery path survives for disk loss/corruption.** This is the only failure mode that PCES does not cover, and
  gossip during `OBSERVING` is the only way a disk-wiped node can rediscover its latest self event before creating new
  events — the difference between an honest restart and an accidental branch.
- **No change risk.** Keeping working code avoids the regression risk of removing a status from the state machine and
  re-threading the transitions around it.
- **Cheap insurance.** The cost is a bounded, configurable startup delay (default `10s`), paid once per node start.

### Negative

- **A now-redundant startup delay in the common case.** For an ordinary crash, PCES has already restored the node's
  latest self event from local disk by the time `OBSERVING` begins, so the wait no longer protects against anything in
  that (overwhelmingly common) case — it is pure latency on the path to `ACTIVE`.
- **The guarantee it provides is weaker than it looks.** As noted under **Limitations**, `OBSERVING` does not guarantee
  self-event recovery after disk loss. Future readers should not treat the status as a hard branch-prevention barrier.

### Neutral

- **The rationale has shifted, not the mechanism.** `OBSERVING` was the *primary* safeguard for the ordinary-crash case;
  it is now a *fallback* for the rare disk-loss/corruption case. The transitions, the gate, and the default delay are
  identical — only the reason for keeping them has changed.
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

- [`../../core/platform-status.md`](../../core/platform-status.md) — the platform status explainer; describes `OBSERVING` and
  why the node listens to gossip before creating events.
- [`../architecture/topics/restart-and-pces.md`](../architecture/topics/restart-and-pces.md) — the PCES write/replay
  path and the guarantee that all gossiped events are on disk after shutdown, which is what made `OBSERVING` redundant
  for the ordinary-crash case.
- `platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/status/PlatformStatus.java:38-41` — the
  `OBSERVING` status definition.
- `platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/status/logic/ObservingStatusLogic.java:176-187`
  — the exit transition driven by `observingStatusDelay`.
- `platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/config/PlatformStatusConfig.java:23` —
  the `observingStatusDelay` config field (default `10s`).
- `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java:37-45`
  — the event-creation gate that withholds creation while in `OBSERVING`.
