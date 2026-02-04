---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# Middleware layer (target)

## Responsibilities

- Define the semantics of ClprMessage and ClprMessageResponse.
- Provide stable APIs for applications and connectors.
- Route inbound messages to the appropriate application handler.
- Construct responses and hand them back to the messaging layer.
- Translate middleware-level failures into application responses on the source ledger.
- Execute synchronously and deterministically for all middleware and connector
  API interactions.
- App-to-connector settlement is out of band and not part of middleware call flow.

## APIs

- App API: submit application messages and receive responses.
- Connector API: allow connectors to inspect/augment messages and responses.
- Msg Queue API: enqueue messages and responses for transport.
- Message delivery API: handle inbound ClprMessage with its assigned message id
  and return ClprMessageResponse.

## Connector interaction principles

- The source connector receives a draft CLPR message and may append a
  ClprConnectorMessage containing approval/denial and optional metadata.
- The middleware includes destination_connector_id in ClprMessageDraft and
  ClprMessage based on connector registration; the source connector may verify
  it.
- The source connector has editorial control over the outbound flow.
- The source connector approval represents a protocol-level financial
  commitment by the connector-pair owner, expressed via ClprConnectorMessage.
- If the source connector approves, it commits to:
  - The destination connector reimbursing the receiving node for remote handling.
  - The source connector paying for local response handling.
- The destination connector is notified with the ClprMessage and the
  ClprMessageResponse plus billing information so it can produce a
  ClprConnectorResponse (it does not block application execution).
- If the destination connector is underfunded, the middleware produces a
  connector_out_of_funds failure response, skips application execution, and no
  reimbursement occurs.
- If the destination application execution reverts or throws, the middleware
  marks application_failure and returns a middleware-defined failure payload
  that may wrap revert data when available.
- ClprMiddlewareResponse continues to report charges and balance metadata even
  when status=application_failure.
- On receipt of a connector_out_of_funds response, the source middleware applies
  a mandatory penalty to the source connector per protocol policy.
- If the source middleware knows the paired destination connector is out of
  funds, it rejects the send before enqueue, returns connector_out_of_funds to
  the application, and notifies the source connector of the rejection.
- The source connector receives the full context (ClprMessage,
  ClprMessageResponse, ClprConnectorResponse) when the connector response
  is delivered back.
- The middleware includes a middleware-to-middleware message in both request
  and response flows (e.g., connector balance reports containing connector id,
  available balance, safety threshold, and outstanding commitments).
- The source middleware does not block sends on unknown or stale funding data;
  it only rejects when it has evidence the destination connector is below the
  required threshold.
- The connector may supply a per-message max_charge; middleware enforces the
  minimum of the application max and connector max.
- Middleware responses always include destination minimum and maximum charge
  values; zero and a protocol-defined max value represent no minimum and no
  maximum.
- Amounts include a unit identifier; balance reports and min/max charges use the
  connector ledger's unit.
- The application API does not expose messaging-layer identifiers (e.g.,
  message ids) in the MVP.
- The application API uses a middleware-assigned ClprAppMsgId for correlation
  via ClprSendMessageStatus and response handling.
- Connector authorization cannot modify application payload data.
- Responses are delivered exactly once per response received from the messaging
  layer.
- Middleware never handles bundles or state proofs; it only receives verified
  messages from the messaging layer.
- When the middleware learns a destination connector is missing or deregistered,
  it marks the connector unavailable and rejects further sends until restored.
- Connector disable/delete applies immediately to new sends; messages already
  enqueued may still be delivered. Middleware does not buffer messages while a
  connector is disabled.
- Middleware maintains connector registration state (remote ledger id,
  expected remote connector hash) used to validate message pairings.
- The application-selected connector fixes the remote ledger/connector;
  middleware infers the destination from connector registration state.

## Message id handling

- The messaging layer assigns message ids on enqueue.
- The messaging layer provides the assigned message id to the destination
  middleware on inbound delivery.
- The destination middleware sets ClprMessageResponse.original_message_id to
  establish correspondence.
- The application layer does not depend on CLPR message ids and can
  use its own nonce in payload data.

## Portability

Middleware APIs and semantics are intended to be implementable in
multiple environments (e.g., EVM smart contracts) independent of
messaging-layer proof validation.
