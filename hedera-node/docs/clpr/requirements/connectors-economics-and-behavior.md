---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# Connector requirements: economics and behavior

## Scope

Connector authorization, payment commitments, and response behavior.

## Assumptions and constraints

- A connector pair is owned and funded by a single financial entity, but each
  ledger hosts its own connector account and contract code.
- Source-side approval is a protocol-level commitment on behalf of the
  connector-pair owner; there is no cross-ledger escrow.
- Settlement between the source application and the connector is out of band
  with respect to CLPR middleware call flow and is not specified by the
  protocol.

## Requirements

- REQ-CONN-001: A source connector shall approve or reject outgoing messages.
- REQ-CONN-002: A destination connector shall be notified of inbound messages and responses.
- REQ-CONN-003: The source connector approval shall represent a protocol-level
  financial commitment, expressed via ClprConnectorMessage, by the connector-pair
  owner to reimburse the destination node for remote execution and to pay for
  source-ledger response handling.
- REQ-CONN-004: The source connector shall be able to inspect the full CLPR message before approval.
- REQ-CONN-004a: The source connector shall not modify the application payload;
  it may only approve/deny and attach connector metadata.
- REQ-CONN-005: The source connector shall be able to define pricing and require payment before approval (e.g., per-byte, percentage, or other policy).
- REQ-CONN-005a: The source connector shall include a per-message max_charge in
  ClprConnectorMessage; a protocol-defined max value may represent no limit.
- REQ-CONN-006: The destination connector shall not approve/deny execution, but may emit a connector response payload after observing the request and/or response.
- REQ-CONN-007: Connector-to-connector association shall be cryptographically provable (e.g., by admin key signature or equivalent linkage).
- REQ-CONN-008: The effective max fee/gas-price limit for a message shall be the lower of the application-provided limit and the connector-provided limit.
- REQ-CONN-009: If the max fee/gas-price limit is below the destination’s known minimum processing charge, the message shall be rejected before send.
- REQ-CONN-010: Connector notifications shall include charge information and
  balance deltas when known.
- REQ-CONN-011: The destination connector handle entrypoint shall receive the ClprMessage, the ClprMessageResponse, and billing information sufficient to understand execution cost (billing details are ledger-specific).
- REQ-CONN-012: The destination connector shall produce a ClprConnectorResponse for delivery to the source connector.
- REQ-CONN-013: Destination-side connectors shall reimburse the receiving node
  per message when the destination middleware handles the message, including
  cases where the application returns application_failure. Reimbursement
  includes an additional margin to incentivize nodes and is performed
  synchronously during message handling.
- REQ-CONN-014: Connector responses are required but may include an empty payload.
- REQ-CONN-015: A connector shall be considered out of funds when outstanding in-flight commitments meet or exceed its available balance minus a safety threshold; in this state, new messages targeting that connector shall not be enqueued until balance increases.
- REQ-CONN-016: A connector identity shall be derived as a hash of a signature over the connector’s full configuration (including local ledger id), where the signature is produced by the connector’s private key; the creation transaction shall include the public key and signature to prove ownership.
- REQ-CONN-017: Connector identities may differ across ledgers for the two ends of a connection; each connector shall store the expected remote connector hash it will accept messages from (provided during creation and treated as opaque).
- REQ-CONN-018: The connector identity hash shall not be truncated below the network’s post-quantum safety threshold (e.g., full SHA-384 length when used).
- REQ-CONN-019: If a message arrives with a source connector id (from
  ClprApplicationMessage) and destination connector id (from ClprMessage) that
  do not match local connector configuration, the middleware shall reject it and
  generate a failure response that results in slashing/penalty of the sender.
- REQ-CONN-020: Connectors shall be deletable or de-registrable by their admin to recover from front-running or misconfiguration, after which new identifiers can be registered.
- REQ-CONN-021: Bundle overhead (including optional configuration updates) shall be covered by per-message charges applied to each connector in the bundle; connectors are not charged additional per-bundle fees for config updates.
- REQ-CONN-022: The connector creation transaction shall capture pairing details, including remote ledger id, expected remote connector hash, and the local paying account used for execution costs.
- REQ-CONN-023: The protocol shall define a ClprCreateConnector transaction and handler that records connector identity, admin authority, pairing details, payment account, and the public key + signatures used to derive connector ids.
- REQ-CONN-024: The connector owner shall compute the local connector id as hash(signature over the local configuration) using the same private key; the expected remote connector id is provided directly and may be computed with the remote ledger’s hash function.
- REQ-CONN-025: A connector shall be paired to exactly one remote connector on exactly one remote ledger.
- REQ-CONN-026: The signed configuration used to derive the local connector id shall cover all pairing fields (local ledger id, remote ledger id, expected remote connector hash, and payment account) to prevent substitution.
- REQ-CONN-027: If the destination connector is underfunded at handling time,
  the outcome shall be a connector_out_of_funds failure; no reimbursement occurs
  and the source connector is mandatorily penalized on the source ledger per
  protocol policy.
- REQ-CONN-028: The MVP shall support connector create, disable, and delete
  operations; update/modify operations are deferred.
- REQ-CONN-029: Deletion or disablement shall take effect immediately for new
  messages; already enqueued messages may still be delivered and processed.

## Fit criteria

- See requirements/traceability.md.

## Open questions

## Candidate schema: ClprCreateConnector (pseudocode)

```text
ClprCreateConnector {
  // Identity + ownership proof
  public_key: bytes
  signature_over_local_config: bytes   // signature(private_key, local_config_bytes)

  // Local configuration (signed by signature_over_local_config)
  local_ledger_id: LedgerId
  local_admin_id: AdminId              // account or contract that can manage this connector
  local_payment_account: AccountId     // pays for execution on this ledger

  // Remote pairing (provided, not derived locally)
  remote_ledger_id: LedgerId
  expected_remote_connector_id: ConnectorId // hash computed on remote ledger
}

// Derived id (not supplied directly)
local_connector_id = Hash(signature_over_local_config)

// Validation notes
// - signature_over_local_config must verify with public_key and local_config_bytes
// - local_config_bytes includes local_ledger_id, local_admin_id, local_payment_account,
//   and remote_ledger_id + expected_remote_connector_id (so pairing is bound)
// - expected_remote_connector_id is treated as opaque; it may be computed with a
//   different hash function on the remote ledger
```

## Out of scope

## Related
