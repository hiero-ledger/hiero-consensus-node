---
id: INV-005
title: Every event in an honest hashgraph is creator-signed and parent-resolvable
class: integrity
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §IV-A / p. 3
provenance: paper-extraction-2026-05-20
curated_by: Michael Heinrichs (@netopyr)
---

# INV-005 — Every event in an honest hashgraph is creator-signed and parent-resolvable

## Statement
Every event held by an honest node carries a signature that
verifies against its claimed creator's public key, and every
parent hash referenced by that event resolves to an event already
present in the same honest node's hashgraph.

## Basis
The paper specifies (Baird & Luykx 2020, §IV-A / p. 3): "Nodes
only accept events that have a valid signature and contain valid
hashes referring to events they already have." This is a
protocol-level acceptance rule on correct nodes; any correct
implementation upholds it. The resulting property — that honest
hashgraphs contain only creator-authenticated, parent-resolvable
events — is what makes the consistent-hashgraphs property
(INV-004) sound: the hash-chain rigidity argument depends on
parent references being verifiable against locally held events.

## Change risk
Any change that admits an event before its signature is verified,
or before its parent hashes are matched against locally held
events, breaks this invariant. Forged or orphan events in an
honest hashgraph corrupt ancestor relations (and thus INV-004) and
therefore corrupt every conclusion downstream of ancestor
relations: seeing, strongly-seeing (INV-006), fame (INV-008), and
consensus order (INV-002).

## Notes
- This entry sits on the invariant / protocol-rule boundary. The
  paper states event acceptance as a node-behaviour requirement;
  this entry frames the resulting property of honest hashgraphs as
  the invariant. The framing is the invariant-shaped reading. If
  the catalog convention later prefers to keep such properties as
  preconditions of INV-004, fold this into INV-004's Basis.
- `topics` is `[hashgraph]`. Once `architecture/topics/event-intake.md`
  is a load-bearing reference for event acceptance, consider also
  tagging `event-intake`. [TBD: confirm secondary topic.]
- `status` is [TBD: confirm enforced in current implementation].
