---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# Application requirements: interop contract

## Scope

Application-facing expectations for message payloads and replies.

## Assumptions and constraints

## Data structures (MVP)

### ClprAppMsgId

- uint64 per-application, middleware-assigned sequence identifier for a send attempt.
- Not a messaging-layer message id.

### ClprSendMessageStatus

- app_msg_id (ClprAppMsgId)
- status (oneof)
  - accepted
  - rejected
- failure_reason (optional, oneof)
  - connector_absent
  - connector_out_of_funds
- failure_side (optional, oneof)
  - source
  - destination

Notes
- `accepted` means the message was successfully enqueued and will yield a future
ClprApplicationResponse.
- In the MVP, failure_reason may be omitted for rejections other than
connector_absent and connector_out_of_funds; additional reasons may be added
later.

## Requirements

- REQ-APP-001: Applications shall not depend on CLPR message ids.
- REQ-APP-002: Applications may include their own correlation nonce in payload data.
- REQ-APP-003: Applications shall receive a ClprSendMessageStatus from the
  middleware indicating whether a message was accepted or rejected and why when
  available.
- REQ-APP-004: Applications may supply a maximum charge amount (with unit) for
  remote execution; the effective limit is the lower of the application and
  connector limits.
- REQ-APP-005: Applications shall implement `handle(ClprApplicationMessage) -> ClprApplicationResponse` to process inbound requests.
- REQ-APP-006: Applications shall implement
  `handle(ClprApplicationResponse, ClprAppMsgId)` to process inbound responses.
- REQ-APP-007: Application payload data shall be treated as opaque bytes by the middleware; any application-connector agreements about payload semantics are outside middleware concerns.
- REQ-APP-008: The connector selected by the application determines the remote ledger and remote connector; the application does not separately specify a destination ledger in the CLPR protocol.
- REQ-APP-008a: Application-level errors produced by normal execution shall be
  encoded by the application in its ClprApplicationResponse payload; they do
  not imply an application_failure status.
- REQ-APP-009: If the paired destination connector is known to be out of funds,
  the middleware shall reject the send and the application shall receive a
  connector_out_of_funds status.
- REQ-APP-010: The MVP does not include cancel or status-query APIs; applications
  rely on the response delivery path to observe outcomes.
- REQ-APP-011: The application API shall not expose messaging-layer identifiers
  (e.g., message ids) in the MVP; it may expose ClprAppMsgId.
- REQ-APP-012: ClprSendMessageStatus shall include a middleware-assigned
  ClprAppMsgId for each send attempt.
- REQ-APP-013: The response delivery path does not include an explicit
  middleware status; applications determine success or failure by interpreting
  the ClprApplicationResponse payload they receive.

## Fit criteria

- See requirements/traceability.md.

## Open questions

## Out of scope

## Related
