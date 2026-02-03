---
scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-02
---

# Connector layer (target)

## Responsibilities

- Authorize outbound messages for a connection.
- Hold balances in native tokens on each ledger.
- Pay for destination execution and reimburse destination endpoints.
- Return connector-level responses to the source connector.
- Maintain stake subject to slashing for provable misbehavior.
- Maintain identity and remote-peer mappings for cross-ledger correlation.

## Economic behavior

- The destination endpoint initially pays execution costs.
- If the destination connector is funded, it reimburses the endpoint and
  pays an additional margin to incentivize participation.
- If the destination connector is missing or underfunded, the message
  deterministically fails and a response is generated.
- Connector funding checks may consider outstanding in-flight commitments
  against available balance.
- When the response arrives at the source ledger, the source connector
  is penalized for failure to prevent cost externalization.

## Connector messages

- Connectors may append a ClprConnectorMessage or ClprConnectorResponse
  to convey connector-specific data.
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
