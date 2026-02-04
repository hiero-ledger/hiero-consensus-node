---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# CLPR glossary

This glossary defines terms used across CLPR documents. Definitions are scoped
to the current target behavior.

- Application: On-ledger contract or off-ledger service that uses the middleware
  to send and handle CLPR messages.
- Connector: On-ledger contract/service owned by a financial entity that
  authorizes outbound messages and pays for execution and response handling.
- Connector pair: The two connectors (source and destination) owned by the same
  financial entity and paired 1:1 across two ledgers.
- Connection: A logical link between two ledgers with an ordered outbound queue
  per direction.
- Endpoint: Node or relay that transports bundles and proofs between ledgers.
- Messaging layer: Responsible for queues, bundles, state proofs, and delivery
  of verified messages to middleware.
- Middleware: Deterministic layer that enforces CLPR semantics, coordinates
  connectors and applications, and constructs responses.
- ClprMessage: The canonical request envelope transported between ledgers.
- ClprMessageResponse: The canonical response envelope transported between
  ledgers.
- Bundle: An ordered batch of messages transmitted together.
- ClprAppMsgId (uint64): Middleware-assigned per-application sequence identifier
  for a send attempt. Monotonically increasing per application. Not a
  messaging-layer message id.
- ClprMsgId (uint64): Messaging-layer message identifier assigned on enqueue and
  provided to destination middleware on inbound delivery. Scope is per
  connection (outbound queue) between two ledgers.
- ClprSendMessageStatus: The immediate result returned by middleware for a
  send attempt (accepted or rejected, with reason and ClprAppMsgId).
- Balance report: Middleware-to-middleware metadata containing connector id,
  available balance, safety threshold, and outstanding commitments.
- Outstanding commitments: Sum of maximum possible charges for messages
  enqueued locally and awaiting remote handling confirmation.
- Minimum/maximum charge: Destination connector policy limits returned in
  ClprMiddlewareResponse.
