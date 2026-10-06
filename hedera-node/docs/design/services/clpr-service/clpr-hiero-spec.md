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

## E-10. `messageExecutionCost` (currently flat; planned gas-based)

### Current behavior

Every successful inbound bundle dispatch charges the receiving connector:

```
charge = messageExecutionCost + margin
```

where:
- `messageExecutionCost` — a flat `clpr.messageExecutionCost` config value (default:
1,000,000 tinybars).
- `margin` — `charge * clpr.endpointMarginPercent / 100`.

The full `charge` amount is paid to the **submitting endpoint** (the Hiero consensus
node that submitted the `ClprSubmitBundle` transaction). There is no central treasury;
the submitter receives the entire payment.

### Planned behavior (Richard's directive)

The flat fee is a placeholder. The correct model is:

> Charge connector `gasUsed * gasPriceTinybars * (1 + margin)`, where `gasUsed` is the
> actual gas consumed by the application-contract dispatch. Pay the entire amount to
> the submitter; there is no separate treasury account.

This aligns the charge with actual compute cost and provides submitters honest
incentives: submitters earn more by including higher-gas messages and are not penalized
for low-gas messages. The margin component (`endpointMarginPercent`) remains the
protocol's incentive premium above break-even.

**Current limitation:** under the flat model, a high-gas message that costs more to
execute than `messageExecutionCost` under-reimburses the submitting endpoint. Until the
gas-based model is implemented, connectors sending gas-heavy messages impose an
uncompensated cost on submitters.

**Implementation note:** measuring `gasUsed` requires reading the child contract call
result from the dispatch. `ClprSubmitBundleHandler` already captures the child call
result to detect `APPLICATION_ERROR`; extending it to read `gasUsed` is straightforward.

See also D-8 in [DRIFT-REVIEW-2026-05.md](DRIFT-REVIEW-2026-05.md) and the
"Success-path economics" subsection in [`config-and-slashing.md`](config-and-slashing.md).
