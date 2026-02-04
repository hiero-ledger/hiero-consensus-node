---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# Connector layer (target)

## Responsibilities

- Authorize outbound messages for a connection.
- Hold balances in native tokens on each ledger.
- Pay for destination execution and reimburse destination endpoints.
- Return connector-level responses to the source connector.
- Maintain stake subject to slashing for provable misbehavior.
- Maintain identity and remote-peer mappings for cross-ledger correlation.
- A connector pair is owned by a single financial entity, with separate
  on-ledger accounts per ledger.

## Economic behavior

- The destination endpoint initially pays execution costs.
- If the destination connector is funded, it reimburses the endpoint during
  message handling and pays an additional margin to incentivize participation.
- Settlement between the application and its connector is out of band and not
  part of CLPR middleware flow.
- If the destination connector is missing or underfunded, the message
  deterministically fails, no reimbursement occurs, and a response is generated.
- Connector funding checks may consider outstanding in-flight commitments
  against available balance.
- When a connector_out_of_funds response arrives at the source ledger, the
  source connector is mandatorily penalized for failure to prevent cost
  externalization.

## Connector messages

- Connectors may append a ClprConnectorMessage or ClprConnectorResponse
  to convey connector-specific data.
- Source connector approval expresses the pair's financial commitment;
  the destination connector honors it when funded.
- The source connector provides a per-message max_charge used to cap fees.
- On pre-enqueue rejection (e.g., remote out of funds), the source connector
  receives the same ClprApplicationResponse returned to the application.
- Connectors can use their own correlation nonce rather than relying
  on CLPR message ids.

## Connector identity (target direction)

- Connector identity is derived from a hash of a signature over the
  connector configuration (including local ledger id), with public key +
  signature proof of ownership.
- Source and destination connector ids may differ; each connector stores
  the expected remote connector hash provided at registration.
- Connector registration includes pairing data (remote ledger id,
  expected remote connector hash) and the paying account for execution.
- Signatures cover the full connector configuration to prevent
  substitution or tampering.
- MVP pairing is one-to-one: a connector is paired to exactly one remote
  connector on one remote ledger.
- MVP management supports create, disable, and delete; update/modify is deferred.
