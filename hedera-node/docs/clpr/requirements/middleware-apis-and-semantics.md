---
scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-02
---

# Middleware requirements: APIs and semantics

## Scope

Middleware APIs, message composition, and connector interaction rules.

## Assumptions and constraints

## Requirements

- REQ-MW-001: The middleware shall expose App, Connector, Msg Queue, and message delivery APIs.
- REQ-MW-002: The middleware shall construct ClprMessage and ClprMessageResponse as defined by the API contract.
- REQ-MW-003: The middleware shall pass the complete CLPR message context (app payload plus middleware metadata) to the source connector before enqueue.
- REQ-MW-004: The middleware shall accept a connector message that includes an explicit approval/denial flag and optional connector payload.
- REQ-MW-005: If the connector denies, the middleware shall not enqueue the message and shall return an error status to the application.
- REQ-MW-006: If the connector approves, the middleware shall include the connector payload in the outbound ClprMessage and enqueue it.
- REQ-MW-007: The middleware shall deliver destination-side connector notifications for inbound requests and for responses.
- REQ-MW-008: Destination-side connectors may append a connector response payload, but must not block application execution.
- REQ-MW-009: The middleware shall return a status (sent/failed with reason) to the source application call.
- REQ-MW-010: The middleware shall support forwarding native value from app to connector (e.g., payable entrypoints in the EVM reference), and any failure along the path shall revert and unwind value transfer.
- REQ-MW-011: The middleware shall include connector-to-connector metadata in both request and response envelopes.
- REQ-MW-012: The middleware shall enforce size limits for connector metadata payloads.
- REQ-MW-013: The middleware shall surface destination connector minimum-charge metadata (when available) to allow source-side prefiltering of messages whose max fee is below the minimum.
- REQ-MW-014: The application interface shall provide `send(ClprApplicationMessage) -> status`, `handle(ClprApplicationMessage) -> ClprApplicationResponse`, and `handle(ClprApplicationResponse)` entrypoints.
- REQ-MW-015: The connector interface shall provide `authorize(ClprMessageDraft) -> ClprConnectorMessage`, `handle(ClprMessage, ClprMessageResponse, Billing) -> ClprConnectorResponse`, and `handle(ClprConnectorResponse)` entrypoints. Billing details are ledger-specific.
- REQ-MW-016: The destination connector handle call shall receive sufficient context to evaluate the inbound request and its response, including the ClprMessage, the ClprMessageResponse, and execution cost/billing information.
- REQ-MW-017: The receiving endpoint node shall pay upfront for bundle execution and be reimbursed by connectors on a per-message basis when funds are available.
- REQ-MW-018: If the destination connector is absent, the middleware shall generate a failure ClprMessageResponse indicating an absent connector and shall not invoke the application handler.
- REQ-MW-019: If the destination connector lacks sufficient funds, the middleware shall generate a failure ClprMessageResponse indicating insufficient funds and shall not invoke the application handler.
- REQ-MW-020: In absent/underfunded connector cases, the receiving node shall not be reimbursed for the execution cost incurred to process the failed message.
- REQ-MW-021: The middleware shall always include a ClprConnectorResponse in ClprMessageResponse; it may be empty if the connector has nothing to add.
- REQ-MW-022: The middleware shall expose a message delivery entrypoint `handle(ClprMessage) -> ClprMessageResponse` for inbound messages from the messaging layer.
- REQ-MW-023: The source middleware shall translate ClprMiddlewareResponse failures into a ClprApplicationResponse delivered to the source application.
- REQ-MW-024: If middleware rejects a message before connector authorization due to application-level validation errors, it shall return the error to the application and shall not notify the connector.
- REQ-MW-025: If the selected source connector is missing or not in good standing on the source ledger, the middleware shall reject the message before enqueue and return an error to the application.
- REQ-MW-026: Once a message is enqueued and delivered to the destination ledger, the destination connector shall be notified of the handling outcome via ClprMessageResponse, even when the application was not executed due to middleware failure.
- REQ-MW-027: When delivering a connector response back to the source connector, the middleware shall provide the full context (ClprMessage, ClprMessageResponse, and ClprConnectorResponse) so the source connector can correlate the entire call flow.
- REQ-MW-028: The middleware shall derive the source connector id from ClprApplicationMessage and the destination connector id from ClprConnectorMessage, and shall validate both against connector registration state before enqueue.
- REQ-MW-029: The middleware shall maintain connector registration state including remote ledger id and expected remote connector hash to validate pairings for each message.

## Open questions

## Out of scope

## Related
