---
scope: clpr
audience: engineering
status: active
last_updated: 2026-02-02
---

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
