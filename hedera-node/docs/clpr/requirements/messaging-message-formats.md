---
scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-02
---

# Messaging requirements: message formats

## Scope

Canonical field layouts for CLPR message envelopes and middleware-layer payloads.

## Assumptions and constraints

- Field names and types are language-agnostic; concrete serialization (protobuf, ABI, etc.) is implementation-defined.

## Message formats

### ClprMessage

- sender_application_id (ClprApplicationId)
- application_message (ClprApplicationMessage)
  - Created by the source application.
- connector_message (ClprConnectorMessage)
  - Created by the source connector.
  - Indicates whether the connector agrees to pay for the interledger execution.

### ClprMessageDraft

- sender_application_id (ClprApplicationId)
- application_message (ClprApplicationMessage)
  - Draft form presented to the source connector before approval.
  - The middleware completes the final ClprMessage by adding connector_message.

### ClprMessageResponse

- original_message_id (unsigned long)
  - Message id of the original ClprMessage.
  - Set by the CLPR middleware upon enqueue.
- application_response (ClprApplicationResponse)
- connector_response (ClprConnectorResponse)
- middleware_response (ClprMiddlewareResponse)

### ClprConnectorMessage

- approve (boolean)
  - Agreement or denial to be on the hook for the message.
- destination_connector_id (ClprConnectorId)
  - Connector identifier on the destination ledger to be billed.
- data (bytes)
  - Optional connector-to-connector payload.

### ClprConnectorResponse

- data (bytes)
  - Optional connector-to-connector payload (may be empty).

### ClprApplicationMessage

- recipient_id (ClprApplicationId)
- connector_id (ClprConnectorId)
  - Local (source) connector identifier selected by the application.
- data (bytes)
  - Application payload for the destination application.

### ClprApplicationResponse

- data (bytes)
  - Application response payload.

### ClprMiddlewareResponse

- status (oneof)
  - success
  - connector_absent
  - connector_out_of_funds
  - application_failure

## Requirements

- REQ-MSG-FMT-001: ClprMessage shall include sender_application_id, application_message, and connector_message.
- REQ-MSG-FMT-002: ClprMessageDraft shall include sender_application_id and application_message and shall be used for connector authorization prior to enqueue.
- REQ-MSG-FMT-003: ClprApplicationMessage shall include connector_id identifying the connector to be on the hook for payment.
- REQ-MSG-FMT-004: ClprMessageResponse shall include original_message_id set by middleware upon enqueue.
- REQ-MSG-FMT-005: ClprConnectorMessage shall include an approval flag, destination_connector_id, and an optional data payload.
- REQ-MSG-FMT-007: ClprConnectorResponse shall be present in ClprMessageResponse and may contain an empty data payload.
- REQ-MSG-FMT-008: ClprMiddlewareResponse shall encode status as a oneof of success, connector_absent, connector_out_of_funds, or application_failure.
- REQ-MSG-FMT-009: ClprMiddlewareResponse shall be consumed by middleware logic and translated into an application-level response; it is not delivered to applications verbatim.
- REQ-MSG-FMT-010: ClprMessage shall not redundantly carry connector ids when they are already provided by ClprApplicationMessage (source) and ClprConnectorMessage (destination).

## Open questions

## Out of scope

## Related
