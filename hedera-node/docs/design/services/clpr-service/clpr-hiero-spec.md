# CLPR Hiero Platform Spec

This document describes Hiero-specific choices that are valid platform specializations
of the canonical CLPR spec. The canonical spec lives in the `clpr-spec` repo
(`clpr-service.md` and `clpr-service-spec.md`). Where those docs say "consult your
platform docs," this document is the authoritative answer for Hiero.

Nothing here contradicts the canonical spec — every item is either explicitly left
to platform discretion or is an addition that the canonical spec does not address.

---

## E-1. CLPR Service address: `0x000000000000000000000000000000000000016e`

The Hiero CLPR Service is deployed at EVM address `0x000000000000000000000000000000000000016e`
(decimal `366`). This is a Hiero system-contract address following the standard Hedera
numbering scheme.

**Why this matters:** the service address is bound into the connector registration
signature: `ECDSA(keccak256(connector_id ‖ service_address))` (spec §6.3 callout).
Any Hiero connector contract must use `0x000000000000000000000000000000000000016e`
as the `service_address` value when computing the binding.

The address is a constant `CLPR_EVM_ADDRESS` in `ClprSystemContract`. It is also
set in `LEDGER_CONFIGURATION.service_address` at genesis (via `V0650ClprSchema`).

**Canonical spec note:** this address is Hiero-specific and must not appear in the
canonical spec. Peer ledgers on non-Hiero platforms will have their own service
address, and a connector's binding must use the service address of the *destination*
ledger.

---

## E-2. Signature schemes on `completeChannel`

Hiero supports two signature schemes for connector registration
(`clpr_complete_channel.proto`, `ClprSignatureScheme` enum):

- **`ECDSA_SECP256K1`** — matches the secp256k1 keys commonly used by EVM-derived
  chains and by Hiero EVM accounts.
- **`ED25519`** — matches the Ed25519 keys used by many native Hedera accounts.

Both are verified through `CryptographyProvider.verifySync`.

### Key and signature length conventions

|     Scheme      |             Public key encoding             | Public key length | Signature length |
|-----------------|---------------------------------------------|-------------------|------------------|
| ECDSA secp256k1 | Uncompressed `x ‖ y` — **no** `0x04` prefix | 64 bytes          | 64 bytes         |
| Ed25519         | Raw key bytes                               | 32 bytes          | 64 bytes         |

For ECDSA, the 64-byte signature contains `r ‖ s` without a recovery byte. This is
sufficient for signature verification. It is NOT sufficient for address recovery (which
would require the recovery byte); signature verification uses the supplied public key
directly rather than recovering a key from the signature.

**Canonical spec note:** spec §6.2 lists no `signatureScheme` parameter to
`completeChannel`. Per the drift review (S-11), the canonical spec should document
"ledger-specific; consult your platform docs" for the signature scheme. Hiero's choice
to support both schemes is additive; a peer connecting to Hiero should consult this
document to determine acceptable key types.

See also: [C-6 in DRIFT-REVIEW-2026-05.md](DRIFT-REVIEW-2026-05.md#c-6) for the
outstanding endpoint-signature verification work that will build on these conventions.

---

## E-3. Local endpoints derived from consensus roster

On Hiero, the set of local CLPR endpoints is exactly the set of active consensus
nodes. There is no separate HAPI transaction to register or deregister a CLPR endpoint.

**How it works:**
- `ClprSubmitBundleHandler` identifies the submitting node via `endpoint_node_id`
resolved through `ReadableNodeStore.get(endpointNodeId)`.
- `ClprChannelManager` reads the live roster to enumerate local nodes that
should be syncing.
- When a node is removed from the consensus roster (through the standard address-book
mechanism), it stops participating in CLPR sync automatically.

**Why this is right for Hiero:** Hiero's strong identity model ties every node to a
well-known account and stake. There is no separate CLPR key-management lifecycle to
maintain. Spec §6.5 explicitly accommodates this: "On platforms where endpoints are
derived automatically from the consensus roster, `registerEndpoint` /
`deregisterEndpoint` operations are not needed."

The spec §6.5 rule "MUST NOT deregister if the endpoint has in-flight sync
submissions" is satisfied implicitly: a node leaving the roster cannot retroactively
cancel a bundle already accepted by consensus.

**Canonical spec note:** the canonical spec must NOT mandate `registerEndpoint` /
`deregisterEndpoint` as required ops on every platform. They are an optional mechanism
for platforms that need explicit endpoint lifecycle management. EVM-based CLPR
implementations may need them; Hiero does not.

**Peer endpoints:** unlike local endpoints, the set of *peer* endpoints is per-channel
and is populated from `endpoints` in the verified peer config (capped at 10 entries
per channel). This is consistent with spec §1.5 / S-6.

---

## E-4. Bundle submission via empty-`SignatureMap` node transaction

Outbound sync bundles reach consensus via the `ClprBundleSubmitter` mechanism. When
a node receives a peer's sync response:

1. `ClprSyncWorkflowImpl` receives the `ClprSyncPayload` from the peer gRPC call.
2. `ClprBundleSubmitter` wraps the bundle payload in a `ClprSubmitBundleTransactionBody`.
3. The transaction is submitted with an **empty `SignatureMap`** — the platform's
   event-level consensus signature on the containing event authenticates the submitting
   node, exactly as it does for TSS and Hints submissions.
4. `ClprSubmitBundle` is therefore listed in `networkAdmin.nodeTransactionsAllowList`.

**Why this is right for Hiero:** Hiero's consensus model provides strong node identity
through event signing. The payer account is the submitting node's account; the
platform guarantees that only the node whose account is the payer can submit the
transaction without an explicit `SignatureMap` entry through this allow-list mechanism.

**Important limitation:** this mechanism authenticates the *Hiero node that submitted
the bundle* but does NOT authenticate the *peer endpoint that constructed the bundle*.
Until C-6 (endpoint signature production and verification) is implemented, Hiero cannot
cryptographically prove to non-Hiero peers that an outbound bundle originated from a
specific Hiero endpoint, and cannot cryptographically verify that an inbound bundle was
constructed by a specific peer endpoint.

See [C-6 in DRIFT-REVIEW-2026-05.md](DRIFT-REVIEW-2026-05.md#c-6) for the plan.

---

## E-5. `PrivilegesVerifier`-based admin gating

Admin-gated CLPR operations — `closeChannel`, `redactMessage`, and
`updateLedgerConfiguration` — require the transaction payer to be:

- **Account `0.0.2`** — the treasury account (Hiero "superuser")
- **Account `0.0.50`** — the system admin account

This is enforced by `PrivilegesVerifier.checkClprAdmin` inside `AbstractClprHandler`.
The framework rejects unauthorized payers with `AUTHORIZATION_FAILED` before the
handler's `doHandle` method is invoked.

**There is no separate CLPR admin key in state or config.** Hiero does not define a
per-service admin key for CLPR. Admin authority is inherited from the platform's
standard privileged-account model.

This matches the standard Hedera platform admin model used by other system services
(e.g., file update, token pause). It means CLPR admin ops require the same governance
process as any other Hedera system operation — no additional key management or
CLPR-specific governance.

**Canonical spec note:** the canonical spec should not require an admin key field on
the CLPR Service state or in `ClprLedgerConfiguration`. Platforms choose their own
admin gating model; Hiero uses `PrivilegesVerifier`. A different platform might use a
multi-sig scheme or a governance contract — the spec should leave this unspecified.

---

## E-6. Per-connector queue quota (`connectorQueueQuotaPct`)

Hiero enforces a per-connector queue quota: a single connector may occupy at most
`connectorQueueQuotaPct` percent of a channel's `max_queue_depth` at any time.

Default: **50%**. A single connector cannot hold more than half the total queue.

**How it works:** `ClprServiceApiImpl.countConnectorMessages` scans the queue to count
messages currently owned by the connector attempting to send. If the count would
exceed `(maxQueueDepth * connectorQueueQuotaPct / 100)`, the `sendMessage` call
reverts with `CLPR_QUEUE_FULL`.

**Why this exists:** it is an anti-griefing measure. Without the quota, a single
high-volume connector could monopolize the entire queue for a channel, blocking all
other connectors on that channel from sending messages.

**Performance note:** the current implementation scans O(queue depth) entries on every
`sendMessage`. Consider maintaining an aggregated `in_flight_count` counter on the
`ClprConnector` record as the queue grows large.

**Long-term direction (Richard's note):** the correct long-term model is for connectors
to pay for their queue slots. A connector's maximum occupancy should be proportional
to the storage it has paid for — either at registration time (locked stake determines
slot count) or through a rent model. This aligns with Hiero's broader paid-storage
model. The flat percentage quota is a placeholder until that paid-storage model is
implemented. **Future work:** tie slot quota to locked stake or a separate storage
payment at `completeConnector` time; consider consistency with the EVM implementation's
approach to the same problem.

**Canonical spec note:** the canonical spec does not define per-connector queue quotas.
This is a Hiero addition. The EVM implementation may or may not have an equivalent
mechanism; they should be made consistent once the paid-storage direction is settled.

---

## E-7. Geometric slashing escalation

Hiero uses geometric escalation for connector misbehavior penalties. The formula,
implemented in `ClprSlashingUtils.slashConnector`:

```
penalty = basePenalty * (multiplier ^ slashCount)
banned  = (slashCount + 1) >= slashBanThreshold
```

`penalty` is capped at `connector.lockedStake` (a connector cannot be penalized past
its entire stake). If `banned`, the connector's record remains in state but all further
operations on it are blocked until an admin intervenes.

### Default values (from `ClprConfig`)

|       Parameter       |        Config key        |            Default             |
|-----------------------|--------------------------|--------------------------------|
| Base penalty          | `clpr.slashBasePenalty`  | 10,000,000 tinybars (0.1 HBAR) |
| Escalation multiplier | `clpr.slashMultiplier`   | 2                              |
| Ban threshold         | `clpr.slashBanThreshold` | 5                              |

With defaults, the escalation sequence is:

| Offence # |                      Penalty                       |
|-----------|----------------------------------------------------|
| 1st       | 10 M tinybars                                      |
| 2nd       | 20 M tinybars                                      |
| 3rd       | 40 M tinybars                                      |
| 4th       | 80 M tinybars                                      |
| 5th (ban) | remaining locked stake forfeited; connector banned |

A connector starting with the minimum locked stake of 100 M tinybars would have its
entire stake exhausted by the 4th offence and would be banned on the 5th.

**Endpoint payout:** on a slash event, the submitting endpoint receives
`min(endpointPenaltyTinybars, penalty * endpointMarginPercent / 100)` from the slashed
stake. This is always paid to the *submitting* endpoint, regardless of which side
(source or destination connector) was at fault.

**Cross-implementation note:** the EVM CLPR implementation also uses geometric
escalation with the same formula. The formula and defaults should be promoted to the
canonical spec §4.6 to ensure all CLPR implementations share the same escalation curve
and connectors have predictable incentive structures across ledgers.

---

## E-8. `ClprBundleContent` PBJ wire format for verifier returns

Hiero verifier contracts must return a **PBJ-encoded `ClprBundleContent`** proto as
the ABI return value of `verifyBundle(bytes bundlePayload) returns (bytes)`.

`ClprBundleContent` carries:
- Queue metadata: the `received_message_id` range and running hash for this bundle.
- `repeated ClprMessagePayload messages`: the decoded per-message payloads in order.
Each `ClprMessagePayload` is a one-of covering `ClprMessage` (data), `ClprMessageReply`
(response), and `ClprControlMessage` (config update). A slot with no one-of set
represents a redacted message.

`EvmClprVerifier` decodes the return bytes using PBJ's `ClprBundleContent.PROTOBUF`
codec. Any verifier that returns bytes not parseable as a valid `ClprBundleContent`
will cause `ClprVerifier.verifyBundle` to throw, resulting in
`CLPR_BUNDLE_VERIFICATION_FAILED`.

**This is the de-facto Hiero verifier ABI.** The EVM project has independently arrived
at the same `ClprBundleContent` shape (per drift review S-10). Until S-10 is resolved
in the canonical spec, treat this as the Hiero platform binding. Verifier contracts
written for Hiero must return this format.

See [`verifier.md`](verifier.md) for the full verifier dispatch flow.

---

## E-9. Lazy `ConfigUpdate` prepended at send

Hiero implements lazy config propagation per spec §1.3 / §4.3 step 1a.

**Mechanism (in `ClprServiceApiImpl.sendMessage`, lines 124–141):**

1. Read the channel's `lastConfigTimestamp`.
2. Read the singleton `ClprLedgerConfiguration.timestamp`.
3. If the channel's timestamp is older than the singleton's, **prepend a
   `ClprControlMessage.configUpdate` to the queue** at `nextMessageId` before the
   caller's data message.
4. To guarantee the prepend fits, `sendMessage` reserves an extra slot: the
   `max_queue_depth` check treats current depth + 2 as the threshold (one for the
   config update, one for the data message) when a config prepend is needed.

**Mirrored on inbound:** `ClprSubmitBundleHandler` (lines 280–293) applies any
`ConfigUpdate` control messages before processing data messages in the same bundle,
updating `peerConfigTimestamp` mid-iteration. See drift review C?-9 for discussion of
ordering semantics within a bundle.

**Why lazy rather than eager:** sending a config update on every ledger configuration
change would require enqueuing one control message per active channel — O(channels)
writes in `updateLedgerConfiguration`. Lazy propagation defers that cost to the next
actual send, which may never happen on an idle channel.

---

## E-10. Application execution gas and endpoint compensation

Immediately before the first verifier or application EVM dispatch, the submitting
transaction's original payer reserves the network gas fee. Early validation failures do
not create an escrow. Bundles reserve before processing DATA or response payloads,
including before reversible connector compensation or slashing payouts. This prevents
those transfers from backing the parent's gas payment, even when a callback is later
skipped. A bundle reserves `contracts.maxGasPerTransaction` gas because
its shared allowance covers both verification and callbacks; completion reserves only
its verifier allowance, with its billable reservation capped at 300,000 gas units.
The configured transaction maximum therefore determines a bundle payer's worst-case
required balance, so CLPR execution profiles must choose it deliberately. The signed
`transactionFee` must cover already charged base/byte fees plus the gas reservation.
Before bundle validation, the handler also reserves signed-fee headroom for the full
possible endpoint misbehavior penalty. This reserves capacity only: the penalty is
collected only when applicable, and the gas escrow must leave that headroom available.
The gas-unit price uses the ordinary ContractCall price, congestion multiplier, and active
exchange rate; globally free fees or a free ContractCall service make this gas free. Unused gas fees are refunded once the entire parent transaction succeeds. If a
later failure rolls back application state, payment for gas already used is replayed
against that original payer before other failure penalties. Reversible transfers from
connectors never provide the backing for this network payment.

For each DATA callback, the receiving connector reimburses the payer for actual billed
application gas, plus the existing flat endpoint compensation:

```
networkGasFee = gasUsed * gasPriceTinybars
endpointCompensation = messageExecutionCost * (1 + endpointMarginPercent / 100)
connectorDebit = networkGasFee + endpointCompensation
```

The connector's worst-case gas reimbursement and compensation are transferred before
the callback. Unused gas reimbursement is returned afterward. Response callbacks use
the same gas payment and reimbursement path without the DATA compensation. An absent
or underfunded originating connector causes best-effort response delivery to be skipped.
Reverted callbacks still pay for gas consumed. Gas is reported before rollback clears stream-builder results. A pre-execution failure
without an EVM outcome bills zero gas; a reverted execution pays its reported gas.

The sum of verifier and application gas grants cannot exceed the transaction cap.
Callbacks share the remaining allowance: each DATA or response payload receives at
most `min(maxGasPerMessage, remainingGas / remainingCallbackPayloads)`. Skipped payloads
release their share for later callbacks. Once granted, gas capacity is consumed in full
regardless of billed EVM gas refunds, so repeated storage refunds cannot reopen capacity.
Application children use consensus throttling with the connector as child payer while
retaining the CLPR service's synthetic EVM sender. When the verifier adapter does not
report gas into the shared accounting ledger, its full configured gas allowance is
conservatively reserved before allocating application grants.

A multi-message bundle can therefore give each callback less than the per-message
ceiling. Applications must stay within the callback's allocated grant; exhausting it
produces the existing application error response. Per-message limits remain ceilings.

Verifier fees are billed once through the shared escrow, capped at 300,000 gas units
across the operation. Native measured-fee settlement recognizes that ownership and adds
applicable stored-byte fees without charging verifier gas again. Without an escrow-owned
verifier, compatibility settlement applies the same billing cap. This reconciles the
[CLPR fee proposal](https://github.com/hiero-ledger/hiero-consensus-node/pull/28137) with
callback accounting; callback gas remains billed for its reported execution.

See also the economics section in [config-and-slashing.md](config-and-slashing.md).

## E-11. CLPR singleton maintenance in the block stream

Some CLPR singleton changes happen outside of any transaction handler: genesis and
post-upgrade setup initialize missing `LEDGER_CONFIGURATION`, `ENDPOINT_MANIFEST`, and
`ENDPOINT_MANIFEST_CONSTRUCTION` singletons; post-upgrade setup reconciles the endpoint
manifest with a changed roster; and every round (including rounds with no user
transactions) the endpoint manifest reconciler may advance or close a construction.

Each such change is externalized as a system-initiated `ClprMaintenanceTransactionBody`
(`HederaFunctionality.ClprMaintenance`), whose only field is the `ClprMaintenanceReason`.
The transaction:

- is emitted only when at least one singleton value changes;
- uses a reserved consensus time before the following event or user transaction, and a
  non-zero transaction id nonce like other system dispatches;
- is paid by the system admin account, with status `SUCCESS`, and charges no fees;
- carries the changed singleton values only in its state changes, which are authoritative;
- appears in the block stream, but not the record stream.

A client MUST NOT submit this transaction; like other internal-only transactions, its API
permission defaults to `0-0`.
Block stream consumers that replay state MUST apply its state changes and MUST NOT run any
handler for it; in particular, it is unrelated to the user-facing
`ClprUpdateLedgerConfiguration` and `ClprEndpointPublication` transactions.
