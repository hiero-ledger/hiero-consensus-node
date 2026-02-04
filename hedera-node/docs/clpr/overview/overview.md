---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-01-28
------------------------

# CLPR overview

## Purpose

CLPR (Cross-Ledger Protocol) enables reliable, asynchronous message passing
between independent ledger networks without introducing an intermediary
consensus layer or federated bridge. It relies on each ledger's native
finality guarantees and verifiable state proofs, plus economic incentives,
to ensure correctness and liveness.

CLPR treats messages as arbitrary byte payloads. This makes it a general
transport primitive for cross-ledger smart contract invocation, token
movement, oracle data propagation, or application-specific messaging.

## Principles

- No intermediary consensus layer. CLPR is ledger-to-ledger.
- Deterministic outcomes. Every accepted message produces a response.
- Separation of concerns: messaging, middleware, connectors, applications.
- Portability across ledger types and programming environments.
- Economic incentives align costs with the responsible connector.

## Key concepts

- Message: bytes plus metadata representing a unit of communication.
- Bundle: ordered batch of messages transmitted together.
- Source/Destination ledger: where a message is created/accepted vs processed.
- Response: special message generated on the destination ledger.
- Connection: logical link with a single ordered outbound queue per direction.
- Connector: entity that authorizes messages, pays for execution, and is
  accountable for failures.
- Endpoint: node or relay that transmits and receives messages and proofs.

## Execution model (high level)

1. A source application submits a message via the middleware.
2. A source connector approves or rejects the message.
3. If approved, the message is enqueued and assigned a message id.
4. Endpoints transport bundles and state proofs to the destination ledger.
5. The destination ledger executes the message and generates a response.
6. The response is returned to the source ledger and delivered to the app.
7. Economic settlement and penalties are applied to the connectors.

## Layer summary

- Messaging layer: connections, queues, bundles, state proofs, endpoints.
- Middleware layer: message semantics, routing, and connector hooks.
- Connector layer: authorization and payment commitments.
- Application layer: domain-specific logic built on middleware APIs.

## Compatibility goals

CLPR defines stable message semantics and APIs that can be implemented in
multiple languages and environments (Java, Solidity, Rust, Go, WASM, etc.).
Ledger-specific state proofs are encapsulated as verifiable proof types so
additional ledgers can be supported without changing middleware semantics.

## What this doc excludes

Detailed message formats and evolving API specifics belong in implementation/.
