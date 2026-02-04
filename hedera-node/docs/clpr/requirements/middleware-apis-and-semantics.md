---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# Middleware requirements: APIs and semantics

## Scope

Middleware APIs, message composition, and connector interaction rules.

## Assumptions and constraints

- A connector pair is owned and funded by a single financial entity, but each
  ledger hosts its own connector account and contract code.
- Source-side approval is a protocol-level commitment on behalf of the
  connector-pair owner; there is no cross-ledger escrow.

## Requirements

- REQ-MW-001: The middleware shall expose App, Connector, Msg Queue, and message delivery APIs.
- REQ-MW-002: The middleware shall construct ClprMessage and ClprMessageResponse as defined by the API contract.
- REQ-MW-003: The middleware shall pass the complete CLPR message context (app payload plus middleware metadata) to the source connector before enqueue.
- REQ-MW-004: The middleware shall accept a connector message that includes an explicit approval/denial flag and optional connector payload.
- REQ-MW-005: If the connector denies, the middleware shall not enqueue the message and shall return an error status to the application.
- REQ-MW-006: If the connector approves, the middleware shall include the connector payload in the outbound ClprMessage and enqueue it.
- REQ-MW-007: The middleware shall deliver destination-side connector notifications for inbound requests and for responses.
- REQ-MW-008: Destination-side connectors may append a connector response payload, but must not block application execution.
- REQ-MW-009: The middleware shall return a ClprSendMessageStatus to the source
  application call, indicating acceptance or failure; reason and connector side
  (source or destination) are provided when available.
- REQ-MW-010: The middleware shall support forwarding native value from app to connector (e.g., payable entrypoints in the EVM reference), and any failure along the path shall revert and unwind value transfer.
- REQ-MW-011: The middleware shall include connector-to-connector metadata in both request and response envelopes.
- REQ-MW-012: The middleware shall enforce size limits for connector metadata payloads.
- REQ-MW-013: The middleware shall surface destination connector minimum-charge metadata (when available) to allow source-side prefiltering of messages whose max fee is below the minimum.
- REQ-MW-013a: The middleware shall compute the effective maximum charge for a
  message as the minimum of the application-provided max and the connector
  max_charge from ClprConnectorMessage.
- REQ-MW-014: The application interface shall provide
  `send(ClprApplicationMessage) -> ClprSendMessageStatus`,
  `handle(ClprApplicationMessage) -> ClprApplicationResponse`, and
  `handle(ClprApplicationResponse, ClprAppMsgId)` entrypoints.
- REQ-MW-015: The connector interface shall provide
  `authorize(ClprMessageDraft) -> ClprConnectorMessage`,
  `handle(ClprMessage, ClprMessageResponse, Billing) -> ClprConnectorResponse`,
  `handle(ClprConnectorResponse)`, and
  `handle(ClprApplicationResponse, ClprAppMsgId)` entrypoints. Billing details
  are ledger-specific.
- REQ-MW-016: The destination connector handle call shall receive sufficient context to evaluate the inbound request and its response, including the ClprMessage, the ClprMessageResponse, and execution cost/billing information.
- REQ-MW-017: The receiving endpoint node shall pay upfront for bundle execution
  and be reimbursed by connectors on a per-message basis when funds are
  available; reimbursement is performed synchronously during message handling.
- REQ-MW-018: If the destination connector is absent, the middleware shall generate a failure ClprMessageResponse indicating an absent connector and shall not invoke the application handler.
- REQ-MW-019: If the destination connector lacks sufficient funds, the
  middleware shall generate a failure ClprMessageResponse indicating
  connector_out_of_funds, shall not invoke the application handler, and shall
  not trigger connector reimbursement.
- REQ-MW-019a: If the destination application handler executes and fails, the
  middleware shall set ClprMiddlewareResponse.status=application_failure and
  treat the message as handled for connector reimbursement purposes.
- REQ-MW-019b: On application_failure, the destination middleware shall capture
  any revert/exception data returned by the application handler (when the
  execution environment provides it) and populate ClprApplicationResponse.data
  with a middleware-defined failure payload; the encoding is deferred and may
  evolve as the prototype matures. If no revert data is available, it shall use
  an empty payload.
- REQ-MW-019c: Application-level error responses that are produced by normal
  application execution shall be returned with
  ClprMiddlewareResponse.status=success; the application encodes error details
  in its ClprApplicationResponse payload.
- REQ-MW-019d: The destination middleware shall include destination connector
  minimum_charge, maximum_charge, and balance_report metadata in
  ClprMiddlewareResponse even when status=application_failure.
- REQ-MW-020: In absent/underfunded connector cases, the receiving node shall not be reimbursed for the execution cost incurred to process the failed message.
- REQ-MW-021: The middleware shall always include a ClprConnectorResponse in ClprMessageResponse; it may be empty if the connector has nothing to add.
- REQ-MW-022: The middleware shall expose a message delivery entrypoint
  `handle(ClprMessage, ClprMsgId) -> ClprMessageResponse` for inbound messages
  from the messaging layer.
- REQ-MW-022a: The middleware shall copy ClprMsgId into
  ClprMessageResponse.original_message_id when handling inbound messages.
- REQ-MW-023: The source middleware shall translate ClprMiddlewareResponse failures into a ClprApplicationResponse delivered to the source application.
- REQ-MW-024: If middleware rejects a message before connector authorization due to application-level validation errors, it shall return the error to the application and shall not notify the connector.
- REQ-MW-025: If the selected source connector is missing or not in good standing on the source ledger, the middleware shall reject the message before enqueue and return an error to the application.
- REQ-MW-026: Once a message is enqueued and delivered to the destination ledger, the destination connector shall be notified of the handling outcome via ClprMessageResponse, even when the application was not executed due to middleware failure.
- REQ-MW-027: When delivering a connector response back to the source connector, the middleware shall provide the full context (ClprMessage, ClprMessageResponse, and ClprConnectorResponse) so the source connector can correlate the entire call flow.
- REQ-MW-028: The middleware shall derive the source connector id from
  ClprApplicationMessage and the destination connector id from connector
  registration state, and shall validate both before enqueue.
- REQ-MW-028a: The middleware shall include destination_connector_id in
  ClprMessageDraft and ClprMessage based on connector registration state so the
  source connector can verify pairing.
- REQ-MW-029: The middleware shall maintain connector registration state including remote ledger id and expected remote connector hash to validate pairings for each message.
- REQ-MW-030: If the source middleware has current remote-connector funding
  status indicating the paired destination connector is out of funds, it shall
  reject the send before enqueue, return a connector_out_of_funds
  ClprSendMessageStatus to the application, and notify the source connector of
  the rejection.
- REQ-MW-031: Upon receiving a ClprMessageResponse indicating
  connector_out_of_funds, the source middleware shall mandatorily penalize the
  source connector per protocol policy.
- REQ-MW-032: The middleware shall include a ClprMiddlewareMessage in ClprMessage
  (source to destination) and in ClprMiddlewareResponse (destination to source)
  to carry middleware-to-middleware metadata such as connector balance reports.
- REQ-MW-032a: The ClprMiddlewareMessage shall include a balance_report in both
  directions; additional metadata is optional.
- REQ-MW-033: The source middleware shall not block sends on unknown or stale
  funding data; it shall allow enqueue unless it has evidence the paired
  destination connector is below the required threshold.
- REQ-MW-034: On pre-enqueue rejection due to connector_out_of_funds, the source
  middleware shall notify the source connector of the rejection; the
  notification mechanism is implementation-defined in the MVP.
- REQ-MW-034a: The destination middleware shall include destination connector
  minimum_charge and maximum_charge in ClprMiddlewareResponse; zero and a
  protocol-defined max value may represent no minimum and no maximum.
- REQ-MW-035: Evidence that a destination connector is below the required
  threshold shall be derived from destination-provided available_balance and
  safety_threshold combined with source-tracked outstanding_commitments.
- REQ-MW-036: The destination middleware shall not include outstanding_commitment
  amounts for messages it has not yet received, since it cannot observe them.
- REQ-MW-037: outstanding_commitments shall be the sum of maximum possible
  charges for messages enqueued locally and awaiting remote handling
  confirmation.
- REQ-MW-038: The source middleware shall reduce outstanding_commitments when it
  receives evidence that an enqueued message was handled on the destination
  ledger.
- REQ-MW-039: min/max charges and balance_report amounts shall be reported in the
  connector's ledger-specific unit; the same unit shall be used across all
  amounts in the balance_report and min/max fields.
- REQ-MW-040: Middleware and connector API interactions shall be synchronous and
  deterministic; the messaging layer is the only asynchronous component.
- REQ-MW-041: The middleware-to-messaging API shall provide
  `enqueue(ClprMessage)` and `enqueue(ClprMessageResponse)` entrypoints for
  outbound transport.
- REQ-MW-042: The messaging-to-middleware API shall provide
  `handle(ClprMessage, ClprMsgId)` and `handle(ClprMessageResponse)` entrypoints
  for inbound delivery.
- REQ-MW-043: Middleware shall guarantee message validity before enqueue; the
  messaging layer may assume validated inputs.
- REQ-MW-044: If enqueue fails, the messaging layer shall return a typed failure
  reason to middleware; the full catalog of failure reasons is defined later.
- REQ-MW-045: The application API shall not expose messaging-layer identifiers
  (e.g., message ids) in the MVP; it may expose a middleware-assigned
  ClprAppMsgId.
- REQ-MW-046: The connector authorize hook shall not modify application payload
  data; it may only approve/deny and attach connector metadata.
- REQ-MW-047: The middleware shall deliver application and connector responses
  exactly once per response received from the messaging layer.
- REQ-MW-048: Middleware shall not generate or verify state proofs; it shall only
  accept messages already verified by the messaging layer.
- REQ-MW-049: When the source middleware learns that a destination connector is
  missing or deregistered (e.g., via connector_absent responses or registry
  updates), it shall mark the connector unavailable and reject further sends
  for that connector until availability is restored.
- REQ-MW-050: Connector disable/delete takes effect immediately for new sends;
  messages already enqueued before the disable/delete may still be transmitted
  and handled.
- REQ-MW-051: Middleware shall not buffer messages for disabled connectors; it
  rejects new sends until the connector is re-enabled.
- REQ-MW-052: The middleware shall assign a per-application uint64 ClprAppMsgId
  for each send attempt, including pre-enqueue rejections; the identifier shall
  be monotonically increasing per application.
- REQ-MW-053: ClprSendMessageStatus shall include the ClprAppMsgId and indicate
  acceptance or failure; when available, it shall include the failure reason
  and whether the source or destination connector caused the failure.
- REQ-MW-054: When delivering ClprApplicationResponse to the application or the
  source connector, the middleware shall include the original ClprAppMsgId as a
  side parameter.
- REQ-MW-055: ClprSendMessageStatus.accepted shall indicate the message was
  successfully enqueued for transport and will yield a future
  ClprApplicationResponse.

## Fit criteria

- See requirements/traceability.md.

## Open questions

## Out of scope

## Related
