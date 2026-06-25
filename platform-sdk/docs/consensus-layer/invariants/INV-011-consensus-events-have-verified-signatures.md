---
type: invariant
id: INV-011
title: Every event used in consensus has a verified creator signature
class: integrity
topics: [event-intake, hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-event-intake-concurrent/src/main/java/org/hiero/consensus/event/intake/concurrent/ConcurrentEventIntakeProcessor.java — `isSignatureValid` verifies against the creator's key and rejects failures
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-011 — Every consensus event has a verified creator signature

## Statement

Before any event participates in consensus, its creator's digital signature over the immutable event fields has been verified against the creator's public key for the event's birth round. An event whose signature does not verify does not enter consensus.

## Basis

The hashgraph consensus algorithm requires that, before an event is added to the hashgraph for consensus, its signature is verified as correct against the public key recorded for the event's creator in the roster for the event's birth round.

Verifying authenticity at admission means the consensus hashgraph contains only events genuinely attested by their claimed creators. The Byzantine-fault-tolerance argument depends on this: a node can be held to exactly the events it signed, and an adversary cannot manufacture events in another node's name. Verification is against the key recorded for the creator in the roster for the event's own birth round, so key changes across rounds are respected.

## Change risk

- **Admitting events to consensus without verifying the signature.**
- **Verifying against the wrong key** — not the creator's key for the event's birth round.
- **Allowing the signed contents to differ from the fields the algorithm consumes**, so a valid signature covers something other than what is used.

An unverified or mis-verified event lets an attacker forge ancestry and votes, defeating the fault-tolerance bound.

## Notes

This concerns authenticity of each event. The matching of claimed parent metadata to the real parents is RUL-004.
