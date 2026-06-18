---
type: delta-map
title: Delta map — sheriff
last_reviewed: TBD
---

# Delta map: sheriff

## Summary

The Sheriff is a proposal-only module — it has no current code and therefore
no architecture topic. The proposal gives it the central role in peer
discipline: a per-node misbehaviour ledger feeding shun/welcome verdicts that
Gossip and the Event Creator enforce, with reporting to Execution (`onBadNode`) and
network-coordinated enforcement back from Execution (`badNode`). None of that
exists; what current code has are precursors — sender attribution on events,
pre-consensus branch detection, and local rate limiting — that the Sheriff
would consume.

## Changes

| Change | Proposal state | Proposal source | Current state | Status | Anchor / TBD |
|---|---|---|---|---|---|
| Sheriff module (misbehaviour ledger and verdicts) | A dedicated module tracks the types of misbehaviour each node is accused of and decides whether to shun a node or welcome it back, possibly time-boxed. | [§ Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module) | No Sheriff type or reputation scoring exists; discipline is local per-mechanism rate limiting. | **not-started** | `LruSyncGuard`, `SyncPermitProvider` (`consensus-gossip-impl`) — local-only, pre-proposal shape |
| Shun/welcome enforcement in Gossip | The Sheriff instructs Gossip to terminate connections and refuse a shunned peer, and later to welcome it back. | [§ Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module), [§ Neighbor Discipline](../../proposals/consensus-layer/Consensus-Layer.md#neighbor-discipline) | No shun mechanism; the connection set is driven by the roster only. | **not-started** | `SyncGossipModular` (`consensus-gossip-impl`) — roster-driven peer set intact |
| Shun/welcome enforcement in Event Creator | A shunned peer is excluded from "other parent" selection, so the creator stops building on a shunned node's events until it is welcomed back (per the Event Creator module diagram). | [§ Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module), [§ Event Creator Module](../../proposals/consensus-layer/Consensus-Layer.md#event-creator-module) | No shun mechanism; other-parent selection is roster-driven only. | **not-started** | `TipsetEventCreator`, `ChildlessEventTracker` (`consensus-event-creator-impl`) — roster-driven other-parent selection, no shun exclusion |
| `onBadNode` reporting to Execution | Consensus notifies Execution when a node goes bad or returns to good graces, so Execution can record misbehaviour in state or publish it network-wide. | [§ onBadNode](../../proposals/consensus-layer/Consensus-Layer.md#onbadnode), [§ Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module) | The boundary's callback surface has no bad-node notification. | **not-started** | `AppNotifier` (`swirlds-platform-core`) — no bad-node callback on the existing surface |
| `badNode` network-coordinated enforcement | Execution informs Consensus of enforcement decisions the network has come to consensus on. | [§ badNode](../../proposals/consensus-layer/Consensus-Layer.md#badnode) | No inbound path for enforcement decisions exists on the boundary. | **not-started** | `Platform` (`swirlds-platform-core`) — boundary interface has no such operation |
| Sender attribution for discipline | Gossip captures which node *sent* each event and passes it as event metadata; discipline targets the sender, not the creator. | [§ Neighbor Discipline](../../proposals/consensus-layer/Consensus-Layer.md#neighbor-discipline-1) | The sender is captured on every event, but nothing consumes it for discipline. | **partial** | `PlatformEvent.getSenderId()` (`consensus-model`) |
| Creation-rate violation reporting | A node creating events faster than the network-wide `maximum_event_creation_frequency` is reported to the Sheriff. | [§ Creating Events](../../proposals/consensus-layer/Consensus-Layer.md#creating-events), [§ Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module) | Only local self-rate limiting exists; peer rates are not observed or reported. | **not-started** | `MaximumRateRule` (`consensus-event-creator-impl`) — gates own creation only |
| Ancient-event spam discipline | A node sending large numbers of ancient events may be disciplined. | [§ Validation](../../proposals/consensus-layer/Consensus-Layer.md#validation) | Ancient events are silently dropped at intake; no discipline path. | **not-started** | `EventWindow.isAncient()` (`consensus-model`) checks in intake validators — drop-only |
| Post-consensus branch proof | Pre-consensus branch detection acts fast, but *proving* a Dirty Rotten Brancher happens in the Hashgraph module; only proven misbehaviour drives system-wide action. | [§ Branch Detection](../../proposals/consensus-layer/Consensus-Layer.md#branch-detection) | Pre-consensus detection only, reporting to logs and metrics. | **not-started** | `DefaultBranchDetector`, `DefaultBranchReporter` (`swirlds-platform-core`) — pre-consensus only |

## Cross-references

- Topic: none — the Sheriff is proposal-only; there is no current code and hence no architecture topic yet
- Proposal: [`Consensus-Layer.md` § Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module), [§ Neighbor Discipline (Gossip)](../../proposals/consensus-layer/Consensus-Layer.md#neighbor-discipline), [§ Neighbor Discipline (Event Intake)](../../proposals/consensus-layer/Consensus-Layer.md#neighbor-discipline-1), [§ onBadNode](../../proposals/consensus-layer/Consensus-Layer.md#onbadnode), [§ badNode](../../proposals/consensus-layer/Consensus-Layer.md#badnode)
- Related delta maps: [gossip.md](gossip.md), [event-creator.md](event-creator.md), [event-intake.md](event-intake.md), [reasons-not-to-gossip.md](reasons-not-to-gossip.md)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
