---
lesson_id: pass1-2-node-falls-behind
cluster: pass1
scenario_kind: canonical
depth: lightweight
components_touched: [gossip, reconnect, reasons-not-to-gossip, signed-state-management, event-intake, restart-and-pces, hashgraph]
kb_refs:
  topics: [gossip, reconnect, reasons-not-to-gossip, signed-state-management, event-intake, restart-and-pces, hashgraph]
  concepts: [stale-events, birth-round, event-lifecycle]
  invariants: []
  scenarios: []
prerequisites: []
status: drafted
last_verified_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---

# Node falls behind

## What we're tracing

A node has been keeping up with its peers, then loses ground far enough
that it can no longer catch up by gossiping events alone. We follow it
from the moment its peers start reporting it as behind, through the
state teleport that replaces gossip as the recovery channel, and back
into the steady-state path. Reconnect-during-freeze, roster changes
mid-reconnect, and learner-side retry caps are out of scope for this
pass.

## Setup

Assume an `ACTIVE` node, stable roster, no freeze pending. Gossip is
flowing, but for some reason — long GC pause, slow disk on the PCES
write path, transient network drop, anything that lets the local
[event lifecycle](../../concepts/event-lifecycle.md) drift behind the
network's — the node's most-recent events sit far enough back that its
peers' event windows have advanced past them. Events the node would
need to admit are on the verge of becoming
[stale or expired](../../concepts/stale-events.md), and gossip alone
will no longer close the gap.

## Walkthrough

1. **Peers report this node as behind, and the node agrees.** Every
   gossip sync exchanges event-window summaries; when a peer sees that
   our window lags its own by more than the configured
   [birth-round](../../concepts/birth-round.md) margin, it records us
   as fallen behind on its side and adds its observation to the count
   on our side. Once the fraction of peers reporting "you're behind"
   crosses a threshold, the node concludes it is behind and signals
   that across the
   [Consensus / Execution boundary](../../architecture/interfaces/consensus-execution-boundary.md)
   so the recovery path can begin.

2. **The node stops gossiping.** A
   [reasons-not-to-gossip](../../architecture/topics/reasons-not-to-gossip.md)
   guard goes high: outstanding syncs drain, no new syncs are started,
   and peers that have already concluded we are behind stop pushing
   broadcast events to us. The intent is twofold — free the connection
   and the per-peer permits the recovery path will need, and stop
   spending bandwidth on a peer that is presumed to be on its way into
   reconnect anyway.

3. **A teacher is chosen and a recent signed state is teleported.**
   The [reconnect](../../architecture/topics/reconnect.md) protocol is
   one of three protocols multiplexed on the same TCP connection
   gossip uses; with gossip halted, the reconnect protocol takes the
   socket. A healthy peer is selected as teacher and the local node
   acts as learner; the teacher streams a recent
   [signed state](../../architecture/topics/signed-state-management.md)
   to the learner. State teleport replaces event-by-event catch-up
   precisely because the events the learner would need are no longer
   available from any peer.

4. **The state is validated, ownership crosses the boundary, and
   modules re-anchor.** The learner verifies the received state's
   signatures against the threshold of consensus weight. The wiring
   then pauses, ownership of the state passes from Execution to
   Consensus through the
   [boundary handoff](../../architecture/interfaces/consensus-execution-boundary.md),
   and the loaded replacement is installed.
   [Event intake](../../architecture/topics/event-intake.md) re-anchors
   on the new event window — events older than the post-state
   birth-round threshold are treated as ancient on arrival — the
   hashgraph picks up from the round captured in the state, the event
   creator is re-primed against the new tipsets, and
   [PCES](../../architecture/topics/restart-and-pces.md) writes resume
   from the post-reconnect round. Only after re-anchoring does the
   wiring resume.

5. **Gossip resumes, the platform returns to ACTIVE, and the steady
   state takes over.** The reasons-not-to-gossip guard from step 2
   clears, syncs are permitted again, the platform status walks
   `RECONNECT_COMPLETE → CHECKING → ACTIVE`, and from this point the
   transaction-to-consensus path described in
   [pass1-1](pass1-1-transaction-to-consensus.md) applies once more.
   The node may still be a few rounds behind its peers, but it is now
   close enough that gossip can finish the catch-up on its own.

## Components met

- [Gossip](../../architecture/topics/gossip.md) — the channel whose
  pause is the precondition for reconnect, and whose window-and-tip
  exchange is what produces the fallen-behind reports in the first
  place. Pass 2 cluster:
  [A.3 synthesis](A.3-5-gossip-synthesis.md).
- [Reasons not to gossip](../../architecture/topics/reasons-not-to-gossip.md)
  — the catalog of categorical guards, including the global
  gossip-halted guard set before reconnect and the per-peer
  fallen-behind guard. Pass 2 cluster:
  [B-3 reasons not to gossip](B-3-reasons-not-to-gossip.md).
- [Reconnect](../../architecture/topics/reconnect.md) — orchestrates
  the recovery: detection, teacher selection, state transfer,
  validation, and the re-anchoring handshake. Pass 2 cluster:
  [C-5 reconnect detection and protocol](C-5-reconnect-detection-and-protocol.md)
  and [C-6 state and recovery synthesis](C-6-state-and-recovery-synthesis.md).
- [Signed state management](../../architecture/topics/signed-state-management.md)
  — owns the lifecycle of the artefact that gets teleported, including
  the signature quorum the learner verifies. Pass 2 cluster:
  [C-1 signed-state lifecycle](C-1-signed-state-lifecycle.md).
- [Event intake](../../architecture/topics/event-intake.md) — re-anchors
  on the new event window so events older than the post-state
  birth-round are filtered out instead of admitted. Pass 2 cluster:
  [A.2 synthesis](A.2-5-event-intake-synthesis.md).
- [Restart and PCES](../../architecture/topics/restart-and-pces.md) —
  resumes durability writes from the post-reconnect round. Pass 2
  cluster: [C-3 inline PCES write path](C-3-inline-pces-write-path.md).
- [Hashgraph](../../architecture/topics/hashgraph.md) — picks up its
  round counter and consensus-snapshot state from the loaded state.
  Pass 2 cluster:
  [A.1 synthesis](A.1-8-hashgraph-algorithm-synthesis.md).

## Comprehension prompt

Reconnect is the recovery path you take when gossip alone can no
longer catch you up. Why does step 2 — stopping gossip — happen
*before* step 3 ever picks a teacher, given that gossip is the very
thing that would otherwise help close the gap? And once a state
arrives in step 4, what is the work that re-anchoring does, and what
would go wrong if the hashgraph or the event creator started consuming
the new state before the boundary handoff completed?

## Open questions

> - [TBD] The KB does not yet have a populated
>   [`invariants.md`](../../invariants.md) catalog. The invariants
>   governing this scenario — gossip suspended during reconnect, the
>   fallen-behind threshold proportion, the state-ownership flip at
>   validation, the event-intake re-anchor on the post-state event
>   window — will be cited by `INV-NNN` once the catalog is filled in.
> - [TBD] No per-topic [`delta-map`](../../delta-map/) entries exist
>   for the touched topics; the proposed-design alternatives (notably
>   the proposal's single `PlatformReconnecter` and `StateSyncProtocol`
>   rename, and the consensus-layer proposal's relocation of reconnect
>   onto the Execution side) will be referenced from the corresponding
>   delta callouts once written.
> - [TBD] No [`scenarios/`](../../scenarios/) catalog entries to
>   cross-link. When SCN entries land, this lesson should
>   forward-reference at least the edge cases the curriculum already
>   names — fall-behind triggered by Health Monitor during heavy
>   gossip (`pass3-6-fall-behind-during-heavy-gossip`), reconnect
>   during freeze (`pass3-5-reconnect-during-freeze`), roster change
>   during reconnect (`pass3-7-roster-change-during-reconnect`).
> - [TBD] The `last_verified_against` SHA is taken from `origin/main`
>   at bootstrap time. Pass 1 lessons stay at altitude and carry no
>   in-body code anchors, so there is nothing to drift; the SHA is
>   recorded for the curriculum's audit trail.
