---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# Messaging requirements: state proofs

## Scope

State proof formats, verification rules, and supported proof types for the
messaging layer.

## Assumptions and constraints

- Proof verification is performed by the messaging layer; middleware does not
  generate or validate proofs and only receives verified messages.
- MVP focuses on Hiero-to-Hiero operation with a single proof type.

## Requirements

- REQ-PROOF-001: The system shall accept a proof type identifier and opaque proof payload.
- REQ-PROOF-002: Proof verification shall be deterministic and produce a stable acceptance result.
- REQ-PROOF-003: MVP messaging shall support a single proof type for
  Hiero-to-Hiero operation.
- REQ-PROOF-004: Middleware shall only accept messages that have already been
  verified by the messaging layer.

## Fit criteria

- See requirements/traceability.md.

## Open questions

## Out of scope

Middleware-level proof generation or verification.

## Related
