---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# Requirements traceability (MVP)

This file links requirement groups to the minimum iteration tests that validate
them. It is intentionally coarse-grained and refined over time.

## Test IDs

- IT0-ECHO: Middleware pass-through with echo contract (single ledger).
- IT1-CONN-AUTH: Connector API invocation with mocked approval.
- MVP-HAPPY: Registered connectors with sufficient funds.
- MVP-OOF: Out-of-funds handling and penalties.

## Mapping

- Application API basics: IT0-ECHO, IT1-CONN-AUTH
- Middleware envelope construction: IT0-ECHO
- Connector authorization flow: IT1-CONN-AUTH
- Connector registration and pairing: MVP-HAPPY
- Balance reports and min/max charges: MVP-HAPPY
- Out-of-funds rejection and penalties: MVP-OOF
- Queue API usage (enqueue/handle): IT0-ECHO and IT1-CONN-AUTH
