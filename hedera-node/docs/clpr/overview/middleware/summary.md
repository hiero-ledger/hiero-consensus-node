---
scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-02
---

# Middleware layer (target)

## Responsibilities

- Define the semantics of ClprMessage and ClprMessageResponse.
- Provide stable APIs for applications and connectors.
- Route inbound messages to the appropriate application handler.
- Construct responses and hand them back to the messaging layer.
- Translate middleware-level failures into application responses on the source ledger.

## APIs

- App API: submit application messages and receive responses.
- Connector API: allow connectors to inspect/augment messages and responses.
- Msg Queue API: enqueue messages and responses for transport.
- Message delivery API: handle inbound ClprMessage and return ClprMessageResponse.

## Connector interaction principles

- The source connector receives a draft CLPR message and may append a
  ClprConnectorMessage containing approval/denial and optional metadata.
- The source connector has editorial control over the outbound flow.
- If the source connector approves, it commits to:
  - The destination connector paying for remote handling.
  - The source connector paying for local response handling.
- The destination connector is notified with the ClprMessage and the
  ClprMessageResponse plus billing information so it can produce a
  ClprConnectorResponse (it does not block application execution).
- The source connector receives the full context (ClprMessage,
  ClprMessageResponse, ClprConnectorResponse) when the connector response
  is delivered back.
- Middleware maintains connector registration state (remote ledger id,
  expected remote connector hash) used to validate message pairings.
- The application-selected connector fixes the remote ledger/connector;
  middleware infers the destination from connector registration state.

## Message id handling

- The messaging layer assigns message ids on enqueue.
- ClprMessageResponse includes the original message id in headers
  or metadata to establish correspondence.
- The application layer does not depend on CLPR message ids and can
  use its own nonce in payload data.

## Portability

Middleware APIs and semantics are intended to be implementable in
multiple environments (e.g., EVM smart contracts) independent of
messaging-layer proof validation.
