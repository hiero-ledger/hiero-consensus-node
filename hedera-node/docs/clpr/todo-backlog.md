---

scope: clpr
audience: engineering
status: active
last_updated: 2026-02-04
------------------------

# CLPR todo backlog

Use this file for work items that are not yet formal GitHub issues. Keep entries short and actionable.

## 2026-01-28

Title: Expand overview with final target behavior details
Context: Overview and architecture currently include prototype alignment notes but not full algorithm-level specs.
Next action: Add detailed target behavior for messaging bundle verification, middleware APIs, connector economics, and application interop.

## 2026-01-28

Title: Refactor prototype docs to match new documentation structure
Context: Prototype docs were moved as-is into implementation/ and lack metadata preambles and new path conventions.
Next action: Add metadata preambles, update titles/links, and split into APIs/testing as needed.

## 2026-02-02

Title: Evaluate multi-ledger connector pairing
Context: Current design pairs a connector with exactly one remote ledger/connector; allowing multi-ledger pairing would require routing logic in connectors or applications.
Next action: Discuss trade-offs with architecture team and decide whether to keep single pairing or add multi-ledger capability.

## 2026-02-04

Title: Define iteration plan to MVP
Context: Early iterations include pass-through middleware and mocked connectors before full connector registration.
Next action: Review and refine implementation/iteration-plan.md.

## 2026-02-04

Title: Expand failure taxonomy
Context: MVP currently documents only connector_absent and connector_out_of_funds for application-facing status; additional rejection reasons will be added after early iterations validate behavior.
Next action: Define additional failure reasons and map them to ClprSendMessageStatus and ClprMiddlewareResponse when evidence from implementation warrants.

## 2026-02-04

Title: Define failure payload envelope
Context: application_failure and other middleware-originated failures may need a CLPR-defined payload wrapper, but the encoding is deferred until prototype experience accumulates.
Next action: Decide whether to standardize a failure payload envelope and document it in message formats.

## 2026-02-04

Title: Validate ClprMsgId scope vs bundle inference
Context: ClprMsgId is messaging-layer assigned per connection (outbound queue), while bundle proofs rely on message-id inference from last proven entry.
Next action: Confirm per-connection sequencing and inference rules during messaging implementation.
