---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# CLPR iteration plan (to MVP)

This plan defines the incremental milestones and the minimum tests required at
each step. The goal is to keep iterations small, demonstrable, and test-backed.

## Iteration 0: Middleware pass-through (single ledger)

Scope
- Middleware uses the CLPR Application API end-to-end.
- The middleware packs ClprApplicationMessage into ClprMessage.
- A mock messaging layer echoes ClprMessage back to middleware.
- Middleware unpacks and routes to the destination application, then reverses
the flow for ClprApplicationResponse.
- No connector logic or registration.

Minimum tests
- IT0-ECHO: Pass-through echo
- A user submits data to a source application.
- The source application invokes a local "echo" contract via middleware.
- The response returns through middleware to the source application.
- The source application verifies the response equals the input.
- Implemented as smart contracts; no HAPI dependency.

Notes
- The echo contract is reused for early multi-ledger tests but is expected to
be replaced as more complex behavior is implemented.

## Iteration 1: Mocked connector approval

Scope
- Connector API is wired; connector always approves.
- No economic enforcement.

Minimum tests
- IT1-CONN-AUTH: Connector invocation
- Middleware calls connector authorize hook on send.
- Mock connector returns approval.
- The call completes with the same payload behavior as Iteration 0.

## MVP: Connector registration + funds checks

Scope
- Connector create/disable/delete.
- Balance reports, min/max charge reporting, and out-of-funds handling.
- 1:1 connector pairing.

Minimum tests
- MVP-HAPPY: Happy path
- Connectors are registered and funded.
- Message flows end-to-end with reimbursement and response delivery.
- MVP-OOF: Out-of-funds
- Destination connector balance falls below threshold.
- Destination middleware rejects, no application execution occurs.
- Source middleware penalizes the source connector and rejects further sends.
