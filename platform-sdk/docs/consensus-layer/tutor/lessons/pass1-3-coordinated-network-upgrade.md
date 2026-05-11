---
lesson_id: pass1-3-coordinated-network-upgrade
cluster: pass1
scenario_kind: canonical
depth: lightweight
components_touched: [freeze-and-upgrade, hashgraph, event-creator, signed-state-management, gossip, restart-and-pces, event-intake]
kb_refs:
  topics: [freeze-and-upgrade, hashgraph, event-creator, signed-state-management, gossip, restart-and-pces, event-intake]
  concepts: [rounds-and-witnesses, birth-round, event-lifecycle]
  invariants: []
  scenarios: []
prerequisites: []
status: drafted
last_verified_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---

# Coordinated network upgrade

## What we're tracing

An operator schedules a network-wide pause so every node can be
restarted on a new software version. We follow the freeze signal from
the moment it lands as a transaction, through the consensus point at
which the network deterministically agrees to stop, the freeze state
that gets written to disk and signed, and out the other side when each
node boots back up and re-enters the steady-state path. Reconnect
arriving in the middle of a freeze, squelching mechanics during the
freeze, and PCES replay edge cases on the upgrade reboot are out of
scope for this pass — those return as Pass 3 edge cases.

## Setup

Assume an `ACTIVE` network at steady state: roster stable, no freeze
already pending, gossip flowing, the
[Consensus / Execution boundary](../../architecture/interfaces/consensus-execution-boundary.md)
is in normal operation. An operator submits a freeze transaction
(`FREEZE_ONLY` or `FREEZE_UPGRADE`) carrying a target `freezeTime`
that is far enough in the future for every node to receive and order
the transaction before consensus time reaches it.

## Walkthrough

1. **The freeze transaction reaches consensus and `freezeTime` is
   written into platform state.** The transaction takes the ordinary
   transaction-to-consensus path described in
   [pass1-1](pass1-1-transaction-to-consensus.md): it rides a
   self-event into the [hashgraph](../../architecture/topics/hashgraph.md)
   and arrives at Execution inside a delivered round. Execution's
   freeze handler writes `freezeTime` into platform state through the
   [Consensus / Execution boundary](../../architecture/interfaces/consensus-execution-boundary.md);
   from this round onward, every honest node's "are we in a freeze
   period" predicate is armed against the same instant.

2. **The hashgraph identifies the freeze round and stops advancing.**
   The first consensus round whose timestamp falls in the freeze
   period is detected by the
   [hashgraph](../../architecture/topics/hashgraph.md) round
   controller. That round is kept; any later rounds in the same batch
   are discarded. The round's event window is rewritten so the
   [birth round](../../concepts/birth-round.md) of the boundary
   matches the latest consensus round, marking pre- and post-upgrade
   events apart, and the consensus engine refuses any further events.
   Every honest node lands on the same freeze round because consensus
   time is itself agreed — the round-received timestamp is a property
   of the [round](../../concepts/rounds-and-witnesses.md), not of any
   individual receiver.

3. **Event creation halts; only signature traffic still flows.** The
   platform status walks into `FREEZING`, and the
   [event creator](../../architecture/topics/event-creator.md)
   stops building application self-events — the only self-events it
   may still produce are those that carry this node's signature on
   the freeze state. The
   [event lifecycle](../../concepts/event-lifecycle.md) for new
   application transactions effectively ends at the freeze round; any
   transactions still in the pool will be Execution's problem to
   resubmit on the post-upgrade network.

4. **The freeze state is saved and signatures distribute over
   gossip.** The freeze round emits its signed state; the
   [signed-state lifecycle](../../architecture/topics/signed-state-management.md)
   marks it as the freeze state and writes it synchronously to disk,
   recording the `freezeState` flag in the on-disk metadata.
   [Gossip](../../architecture/topics/gossip.md) does **not** halt at
   this point — peers continue to exchange signature transactions on
   the freeze state precisely so a quorum of signatures can reach
   every node, including any that are a few rounds behind. Once the
   freeze state has been written, the platform status advances from
   `FREEZING` to `FREEZE_COMPLETE` and stays there. No further rounds
   are produced; no application events are admitted. Operator
   tooling, not consensus, is what eventually stops the JVM.

5. **Nodes boot the new software and re-enter steady state.** When
   each node comes back up, the
   [restart path](../../architecture/topics/restart-and-pces.md)
   reads the saved state, sees the freeze flag in its metadata, runs
   PCES replay over any events captured between the freeze state and
   the prior shutdown, and primes the in-memory hashgraph from the
   round captured in the state. The
   [event intake](../../architecture/topics/event-intake.md)
   pipeline re-anchors on the post-freeze event window: events older
   than the rewritten birth-round threshold are treated as ancient on
   arrival, so any pre-upgrade events still in flight cannot
   contaminate the post-upgrade DAG. Once status walks back through
   `CHECKING` to `ACTIVE`, the
   [transaction-to-consensus path](pass1-1-transaction-to-consensus.md)
   applies once more — on a possibly different binary, against a
   roster that may also have changed at the boundary.

## Components met

- [Freeze and upgrade](../../architecture/topics/freeze-and-upgrade.md)
  — owns the freeze trigger, the `FREEZING` → `FREEZE_COMPLETE`
  transition, and the documented points where the upgrade restart
  path picks back up. Pass 2 cluster:
  [D-4 freeze and upgrade synthesis](D-4-freeze-and-upgrade-synthesis.md).
- [Hashgraph](../../architecture/topics/hashgraph.md) — identifies
  the freeze round, rewrites its event window, and stops admitting
  further events. Pass 2 cluster:
  [A.1 synthesis](A.1-8-hashgraph-algorithm-synthesis.md).
- [Event creator](../../architecture/topics/event-creator.md) — gated
  off in `FREEZING` except for self-events that carry signature
  transactions on the freeze state. Pass 2 cluster:
  [A.4 synthesis](A.4-4-event-creator-synthesis.md).
- [Signed state management](../../architecture/topics/signed-state-management.md)
  — captures the freeze state, marks it on disk, and records the
  metadata flag the upgrade-startup path keys off. Pass 2 cluster:
  [C-1 signed-state lifecycle](C-1-signed-state-lifecycle.md).
- [Gossip](../../architecture/topics/gossip.md) — kept alive through
  `FREEZING` and `FREEZE_COMPLETE` so freeze-state signatures can
  reach every node. Pass 2 cluster:
  [A.3 synthesis](A.3-5-gossip-synthesis.md).
- [Restart and PCES](../../architecture/topics/restart-and-pces.md) —
  drives the post-upgrade boot: state load, PCES replay, and the
  re-priming of consensus modules from the freeze state. Pass 2
  cluster:
  [C-4 restart and replay](C-4-restart-and-replay.md).
- [Event intake](../../architecture/topics/event-intake.md) —
  re-anchors on the post-freeze event window so pre-upgrade events
  cannot leak into the new DAG. Pass 2 cluster:
  [A.2 synthesis](A.2-5-event-intake-synthesis.md).

## Comprehension prompt

Step 4 is the surprising one: gossip stays running after the network
has agreed to stop producing rounds. What property of the freeze
state would be at risk if gossip halted the moment the freeze round
was identified, and which step further down the pipeline would notice
the loss first? And step 2 says every honest node lands on the same
freeze round — what is the consensus property the network is leaning
on for that, and where in the earlier
[transaction-to-consensus](pass1-1-transaction-to-consensus.md) walk
is that property already being established for the freeze
transaction itself?

## Open questions

> - [TBD] The KB does not yet have a populated
>   [`invariants.md`](../../invariants.md) catalog. The invariants
>   governing this scenario — every honest node agreeing on the same
>   freeze round, the freeze-state-save preceding the
>   `FREEZE_COMPLETE` transition, gossip remaining permitted in
>   `FREEZING` and `FREEZE_COMPLETE`, post-upgrade ancient filtering
>   on the rewritten birth round — will be cited by `INV-NNN` once
>   the catalog is filled in.
> - [TBD] No per-topic [`delta-map`](../../delta-map/) entries exist
>   for the touched topics; the proposed-design alternatives
>   (notably the consensus-layer proposal's relocation of the freeze
>   procedure entirely onto the Execution side, leaving the
>   consensus side with a simpler "stop after round N" signal in
>   place of today's distributed `freezeTime` reads) will be
>   referenced from the corresponding delta callouts once written.
> - [TBD] No [`scenarios/`](../../scenarios/) catalog entries to
>   cross-link. When SCN entries land, this lesson should
>   forward-reference at least the edge cases the curriculum
>   already names — reconnect during freeze
>   (`pass3-5-reconnect-during-freeze`), PCES replay after upgrade
>   (`pass3-8-pces-replay-after-upgrade`), squelching during freeze
>   (`pass3-9-squelching-during-freeze`), and the full-depth
>   revisit at `pass3-3-coordinated-network-upgrade`.
> - [TBD] The `last_verified_against` SHA is taken from
>   `origin/main` at bootstrap time. Pass 1 lessons stay at altitude
>   and carry no in-body code anchors, so there is nothing to
>   drift; the SHA is recorded for the curriculum's audit trail.
