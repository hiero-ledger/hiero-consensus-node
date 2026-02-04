---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# Messaging requirements: queue and bundles

## Scope

Queue semantics, bundle ordering, and message id inference.

## Assumptions and constraints

## Requirements

- REQ-MSG-001: Messages shall be enqueued in a strictly ordered outbound queue per connection.
- REQ-MSG-002: Bundles shall preserve message order and allow inference of message ids from the last proven entry.
- REQ-MSG-003: A message id shall be assigned only upon successful enqueue and shall not be required in the application payload.
- REQ-MSG-004: A ClprMessageResponse shall include the originating message id for correlation.
- REQ-MSG-005: The message queue API shall provide `enqueue(ClprMessage)` and
  `enqueue(ClprMessageResponse)` for outbound enqueue, and
  `handle(ClprMessage, ClprMsgId)` and `handle(ClprMessageResponse)` for inbound
  delivery. The `ClprMsgId` parameter conveys the messaging-layer id assigned on
  enqueue.
- REQ-MSG-006: Ledger configuration shall define throttles for message size, bundle size, message count, and total bytes; enforcement is ledger-specific.
- REQ-MSG-007: Ledger configuration updates shall not be enqueued in the message queue; they are transported as optional payloads associated with message bundles.
- REQ-MSG-008: A configuration update payload shall only be processed when delivered alongside a non-empty message bundle to ensure a payer exists for the transport and processing costs.
- REQ-MSG-009: The protocol shall provide an out-of-band API to update remote ledger configuration via state proof for recovery when normal message transport is inactive.
- REQ-MSG-010: Connections may exist without connectors; no user messages shall be enqueued or transmitted unless a connector in good standing exists for the connection.
- REQ-MSG-011: When no connector is available to pay for configuration transport, configuration updates may be dropped and must be recoverable through the out-of-band update path.
- REQ-MSG-012: Out-of-band configuration updates shall be paid by the submitting entity using normal ledger transaction fees.
- REQ-MSG-013: Middleware shall guarantee message validity before enqueue; the
  message queue may assume validated inputs.
- REQ-MSG-014: If enqueue fails, the message queue shall return a typed failure
  reason to the middleware; the full catalog of failure reasons is defined
  later.
- REQ-MSG-015: The messaging layer shall deliver each ClprMessage and
  ClprMessageResponse exactly once per connection.
- REQ-MSG-016: Duplicate or out-of-order deliveries shall be rejected before
  middleware delivery.

## Fit criteria

- See requirements/traceability.md.

## Open questions

## Out of scope

- Retry policies, timeouts, and message expiry semantics.

## Related
