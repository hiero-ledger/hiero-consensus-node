---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

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
- destination_connector_id (ClprConnectorId)
  - Connector identifier on the destination ledger, derived from pairing state.
- connector_message (ClprConnectorMessage)
  - Created by the source connector.
  - Indicates whether the connector agrees to pay for the interledger execution.
- middleware_message (ClprMiddlewareMessage)
  - Created by the source middleware.
  - Conveys middleware-to-middleware metadata (e.g., connector balance reports).

Notes
- ClprMessage intentionally omits a message id; the messaging layer provides the
message id out-of-band when delivering to middleware.

### ClprMessageDraft

- sender_application_id (ClprApplicationId)
- application_message (ClprApplicationMessage)
  - Draft form presented to the source connector before approval.
  - The middleware completes the final ClprMessage by adding connector_message.
- destination_connector_id (ClprConnectorId)
  - Derived by middleware from connector pairing state for connector validation.

### ClprMessageResponse

- original_message_id (uint64)
  - Message id of the original ClprMessage.
  - Set by the destination middleware when handling the inbound message, using
    the message id provided by the messaging layer.
- application_response (ClprApplicationResponse)
- connector_response (ClprConnectorResponse)
- middleware_response (ClprMiddlewareResponse)
  - Includes middleware-to-middleware metadata.

### ClprConnectorMessage

- approve (boolean)
  - Agreement or denial to be on the hook for the message.
- max_charge (amount)
  - Connector-provided per-message maximum charge.
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
  - If the destination application reverts or throws, the middleware populates
    this field with a middleware-defined failure payload that may wrap
    revert/exception data; if no revert data is available, it is empty.

### ClprMiddlewareResponse

- status (oneof)
  - success
  - connector_absent
  - connector_out_of_funds
  - application_failure
- minimum_charge (amount)
  - Destination connector minimum charge; always present (may be zero).
- maximum_charge (amount)
  - Destination connector maximum charge; always present (may be protocol-defined
    max value to represent unbounded).
- middleware_message (ClprMiddlewareMessage)
  - Destination middleware to source middleware metadata.

### ClprMiddlewareMessage

- balance_report
  - connector_id (ClprConnectorId)
  - available_balance (amount)
  - safety_threshold (amount)
  - outstanding_commitments (amount)
    - Sum of maximum possible charges for messages enqueued locally and
      awaiting remote handling confirmation.
- data (bytes)
  - Opaque middleware-to-middleware metadata.

### Amount

- value (uint)
- unit (string)
  - Ledger-specific unit identifier for fees/balances; free-form string with
    future standardization to an enumeration.

## Requirements

- REQ-MSG-FMT-001: ClprMessage shall include sender_application_id,
  application_message, destination_connector_id, and connector_message.
- REQ-MSG-FMT-002: ClprMessageDraft shall include sender_application_id,
  application_message, and destination_connector_id and shall be used for
  connector authorization prior to enqueue.
- REQ-MSG-FMT-003: ClprApplicationMessage shall include connector_id identifying the connector to be on the hook for payment.
- REQ-MSG-FMT-004: ClprMessageResponse shall include original_message_id set by
  the destination middleware when handling the inbound message, using the
  message id provided by the messaging layer.
- REQ-MSG-FMT-005: ClprConnectorMessage shall include an approval flag and an
  optional data payload; max_charge is required only when approve=true.
- REQ-MSG-FMT-006: destination_connector_id shall be derived from connector
  registration and included by middleware (not supplied by the connector).
- REQ-MSG-FMT-007: ClprConnectorResponse shall be present in ClprMessageResponse and may contain an empty data payload.
- REQ-MSG-FMT-008: ClprMiddlewareResponse shall encode status as a oneof of success, connector_absent, connector_out_of_funds, or application_failure.
- REQ-MSG-FMT-009: ClprMiddlewareResponse shall be consumed by middleware logic and translated into an application-level response; it is not delivered to applications verbatim.
- REQ-MSG-FMT-010: ClprMessage shall not redundantly carry the source connector
  id outside of ClprApplicationMessage.
- REQ-MSG-FMT-011: A connector_out_of_funds status shall indicate that the
  destination connector could not reimburse the receiving node; application
  execution did not occur.
- REQ-MSG-FMT-012: ClprMessage shall include a ClprMiddlewareMessage for
  middleware-to-middleware metadata.
- REQ-MSG-FMT-013: ClprMessageResponse shall include a ClprMiddlewareResponse
  carrying a ClprMiddlewareMessage for destination-to-source middleware metadata.
- REQ-MSG-FMT-014: ClprMiddlewareMessage shall include a balance_report with
  connector_id, available_balance, safety_threshold, and outstanding_commitments.
- REQ-MSG-FMT-015: In ClprMessage, balance_report shall reflect the source
  connector account at the time of send.
- REQ-MSG-FMT-016: In ClprMessageResponse, balance_report shall reflect the
  destination connector account at the time of handling.
- REQ-MSG-FMT-017: ClprMiddlewareResponse shall include minimum_charge populated
  from destination connector policy, or zero if no minimum is enforced.
- REQ-MSG-FMT-018: ClprMiddlewareResponse shall include maximum_charge populated
  from destination connector policy, or a protocol-defined max value if no
  maximum is enforced.
- REQ-MSG-FMT-019: minimum_charge and maximum_charge shall always be present in
  ClprMiddlewareResponse; zero and a protocol-defined max value MAY be used to
  represent no minimum and unbounded maximum.
- REQ-MSG-FMT-020: Amount fields shall include a unit identifier; balance_report
  amounts and min/max charges shall use the same unit per connector/ledger.
- REQ-MSG-FMT-021: ClprMiddlewareResponse shall populate minimum_charge,
  maximum_charge, and balance_report even when status=application_failure.

## Fit criteria

- See requirements/traceability.md.

## Open questions

## Out of scope

## Related
