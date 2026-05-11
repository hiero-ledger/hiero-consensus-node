---
lesson_id: pass1-4-event-creation-under-stress
cluster: pass1
scenario_kind: canonical
depth: lightweight
components_touched: [event-creator, health-monitor-and-backpressure, gossip, reasons-not-to-gossip, restart-and-pces]
kb_refs:
  topics: [event-creator, health-monitor-and-backpressure, gossip, reasons-not-to-gossip, restart-and-pces]
  concepts: [birth-round, event-lifecycle]
  invariants: []
  scenarios: []
prerequisites: []
status: drafted
last_verified_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---

# Event creation under stress

## What we're tracing

A node has been minting self-events at steady cadence, then comes
under load — a slow disk on the PCES write path, a long GC pause, a
wave of arriving peer events the intake pipeline cannot drain quickly
enough. We follow how the system detects that pressure, decides to
stop creating new self-events, and ramps back up when the queues
recover. Cascade into reconnect, freeze-time interaction, and the
squelching protocol are out of scope; those return as Pass 3 edge
cases.

## Setup

Assume an `ACTIVE` node operating at steady state: roster stable, no
freeze pending, gossip flowing, the
[event creator](../../architecture/topics/event-creator.md) stamping
each new self-event with a
[birth round](../../concepts/birth-round.md) equal to the current
pending consensus round. One of the wired schedulers — the orphan
buffer's, the hashgraph's, the PCES writer's — falls behind the rate
at which work is arriving, and its unprocessed-task count climbs above
the capacity it was built with.

## Walkthrough

1. **A scheduler queue stays continuously over capacity.** Every
   scheduler in the wiring graph that was built with an
   `unhandledTaskCapacity` is watched. As long as the offending
   scheduler's queue depth stays over its capacity, the time it has
   been continuously unhealthy keeps growing. Schedulers built with
   unlimited capacity are skipped at registration time and never
   contribute to the signal.

2. **The health monitor publishes a single unhealthy-duration
   signal.** The
   [health monitor](../../architecture/topics/health-monitor-and-backpressure.md)
   polls all watched schedulers on a heartbeat and reports the *worst*
   — the longest continuously unhealthy duration of any one scheduler
   — onto a wire that the rest of the consensus layer subscribes to,
   plus a polled accessor that PCES replay reads directly. The signal
   is a detector, not an enforcer; it tells subscribers how bad things
   are, but it does not itself stop anything from running.

3. **Event creation is gated off.** The
   [event creator](../../architecture/topics/event-creator.md)
   consults a chain of permission rules each time it considers minting
   a new self-event. One rule reads the unhealthy-duration signal:
   when the duration crosses its configured threshold, the rule denies
   permission and no self-event is built. The tipset machinery and the
   rest of the rule chain still tick over, but the create call returns
   nothing as long as the gate is engaged. The
   [event lifecycle](../../concepts/event-lifecycle.md) of any event
   already in flight continues; only fresh self-event creation halts.

4. **Gossip backs off in parallel — durability does not.** Gossip
   reads the same signal independently of the event creator. After its
   own grace period it begins revoking outgoing sync permits at a
   configured rate; syncs already in progress are not interrupted, so
   the in-flight load drains naturally. By default the node also keeps
   *sending* its own events while pausing the *inbound* side: peers
   can still pull from us, but we stop pulling from them. The
   transaction-acceptance side of the
   [Consensus / Execution boundary](../../architecture/interfaces/consensus-execution-boundary.md)
   closes its own gate on the same signal, with its own threshold,
   rejecting application transactions at the door. None of this
   relaxes the
   [reasons-not-to-gossip](../../architecture/topics/reasons-not-to-gossip.md)
   durability rule: any self-event that *was* successfully created
   still threads through
   [inline PCES](../../architecture/topics/restart-and-pces.md) before
   reaching the network.

5. **The queues drain and the gates lift.** When the offending
   scheduler dips below capacity and stays there past the configured
   healthy-report interval, the health monitor publishes a healthy
   state. Event-creator gating clears at once; sync permits are
   returned at a different — typically slower — rate, but a configured
   floor of un-revoked permits snaps back immediately so the node is
   not stuck at zero permits while the slow return rate catches up.
   The node has fallen a few rounds behind its peers during the gating
   window, and gossip closes that gap. If the lag was deep enough to
   push past the fall-behind threshold, the
   [node-falls-behind](pass1-2-node-falls-behind.md) recovery path
   takes over instead.

## Components met

- [Event creator](../../architecture/topics/event-creator.md) — the
  primary throttle point, where the unhealthy-duration signal turns
  off self-event creation. Pass 2 cluster:
  [A.4 synthesis](A.4-4-event-creator-synthesis.md).
- [Health monitor and backpressure](../../architecture/topics/health-monitor-and-backpressure.md)
  — the detector that watches every wired scheduler and produces the
  single signal each reaction site reads. Pass 2 cluster:
  [B-1 health detection](B-1-health-detection.md) and
  [B-2 unhealthy reactions](B-2-unhealthy-reactions.md).
- [Gossip](../../architecture/topics/gossip.md) — the second major
  reaction site: outgoing sync permits are revoked under stress and
  returned more slowly than they were taken away. Pass 2 cluster:
  [A.3 synthesis](A.3-5-gossip-synthesis.md).
- [Reasons not to gossip](../../architecture/topics/reasons-not-to-gossip.md)
  — the catalog of categorical rules, including the
  durability-before-gossip rule that holds even while everything else
  is throttling. Pass 2 cluster:
  [B-3 reasons not to gossip](B-3-reasons-not-to-gossip.md).
- [Restart and PCES](../../architecture/topics/restart-and-pces.md) —
  the durability path that any self-event still has to thread before
  reaching gossip; PCES replay has its own (much tighter) health gate,
  but that fires at restart time, not in this steady-state story.
  Pass 2 cluster:
  [C-3 inline PCES write path](C-3-inline-pces-write-path.md).

## Comprehension prompt

The health monitor publishes one number — the worst single offender's
unhealthy duration — and several downstream sites each pick their own
threshold against it. Why is a shared signal with private thresholds
the right shape, given that each reaction site has a different cost
when it engages? And step 4 says that under default configuration the
node keeps sending its own events while it stops processing remote
ones; what would a symmetric "stop both directions" reaction cost us,
and where in the
[transaction-to-consensus](pass1-1-transaction-to-consensus.md) path
would that cost first show up?

## Open questions

> - [TBD] The KB does not yet have a populated
>   [`invariants.md`](../../invariants.md) catalog. The invariants
>   governing this scenario — durability before gossip holding under
>   stress, the unhealthy-duration signal being a maximum rather than
>   per-scheduler, the asymmetric send/receive behaviour during gating
>   — will be cited by `INV-NNN` once the catalog is filled in.
> - [TBD] No per-topic [`delta-map`](../../delta-map/) entries exist
>   for the touched topics; the proposed-design alternatives (notably
>   the consensus-layer proposal's module-API-level `nextRound` pull
>   that adds a coarser cross-boundary throttle on top of today's
>   per-queue health signal) will be referenced from the corresponding
>   delta callouts once written.
> - [TBD] No [`scenarios/`](../../scenarios/) catalog entries to
>   cross-link. When SCN entries land, this lesson should
>   forward-reference at least the edge cases the curriculum already
>   names — fall-behind triggered by Health Monitor during heavy
>   gossip (`pass3-6-fall-behind-during-heavy-gossip`), squelching
>   during freeze (`pass3-9-squelching-during-freeze`), and the
>   full-depth revisit at `pass3-4-event-creation-under-stress`.
> - [TBD] The `last_verified_against` SHA is taken from `origin/main`
>   at bootstrap time. Pass 1 lessons stay at altitude and carry no
>   in-body code anchors, so there is nothing to drift; the SHA is
>   recorded for the curriculum's audit trail.
