---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# Conformance and parity

This document defines how the smart contract prototype and the optimized Java
implementation are expected to align.

## Source of truth

- Requirements in `requirements/` are normative for behavior and interfaces.
- The prototype is a reference implementation for semantics, not performance.

## Parity expectations

- Both implementations must pass the same iteration tests:
  - IT0-ECHO, IT1-CONN-AUTH, MVP-HAPPY, MVP-OOF.
- Message formats and middleware semantics must match across implementations.
- Differences in internal data structures and performance are allowed as long as
  external behavior is identical.

## Change control

- Any behavior change that affects interfaces or economics requires an ADR.
- Changes must update requirements and the iteration tests that validate them.
