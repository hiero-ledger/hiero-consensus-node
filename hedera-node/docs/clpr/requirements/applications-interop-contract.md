---
scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-02
---

# Application requirements: interop contract

## Scope

Application-facing expectations for message payloads and replies.

## Assumptions and constraints

## Requirements

- REQ-APP-001: Applications shall not depend on CLPR message ids.
- REQ-APP-002: Applications may include their own correlation nonce in payload data.
- REQ-APP-003: Applications shall receive a status from the middleware indicating whether a message was accepted or rejected and why.
- REQ-APP-004: Applications may supply a maximum fee/gas-price limit for remote execution; the effective limit is the lower of the application and connector limits.
- REQ-APP-005: Applications shall implement `handle(ClprApplicationMessage) -> ClprApplicationResponse` to process inbound requests.
- REQ-APP-006: Applications shall implement `handle(ClprApplicationResponse)` to process inbound responses.
- REQ-APP-007: Application payload data shall be treated as opaque bytes by the middleware; any application-connector agreements about payload semantics are outside middleware concerns.
- REQ-APP-008: The connector selected by the application determines the remote ledger and remote connector; the application does not separately specify a destination ledger in the CLPR protocol.

## Open questions

## Out of scope

## Related
