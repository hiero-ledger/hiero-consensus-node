# CLPR Verifier (Hiero)

> Prereq: `clpr-service-spec.md` §3.1 (`IClprVerifier` interface) and §4.2
> (Bundle Verification Algorithm). This doc covers how verifier resolution and dispatch
> work in Hiero — i.e. how `ClprSubmitBundleHandler` actually calls a verifier contract.

A verifier is a per-source-ledger EVM contract or native system contract adapter. For a
generic deployed verifier, Hiero delegates proof verification through `EvmClprVerifier`.
For first-party proof systems, such as the CLPR Hiero TSS verifier, Besu QBFT verifier,
and Sei CometBFT verifier, the EVM-facing method dispatches into native system-contract
logic that understands that source ledger's proof scheme.

All classes live under
`hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/verifier/`.

## Types

### `ClprVerifier` (interface)

```java
ClprLedgerConfiguration verifyConfig(Bytes proofBytes, Context ctx);
ClprBundleContent verifyBundle(Bytes bundlePayload, Context ctx);
```

Both throw on verification failure. `Context` carries the `ContractID` of the verifier and
the gas budget. `ClprBundleContent` is the proto returned (queue metadata + decoded
messages).

### `ClprVerifierFactory`

Resolves a verifier from a `ContractID`:

1. Look up in a small built-in registry (today: stub — TODO CLPR-5.3 in code). Reserved
   for first-party verifiers like the Hiero-source verifier itself, where bypassing the
   EVM round-trip is desirable.
2. Otherwise return `EvmClprVerifier(contractId)`.

### `EvmClprVerifier`

Dispatches each `verifyXxx` call to the deployed EVM contract via a
`ContractCallTransactionBody` step-dispatch — the standard "internal contract call"
mechanism used elsewhere in `hedera-app`. The selector + ABI passed to the contract is
the single-bytes form (`verifyConfig(bytes)` / `verifyBundle(bytes)`); the contract is
expected to internally call the two-bytes CLPR system precompile (see "System precompile"
below) with the pinned peer `ledgerId` it was deployed for.

Gas budget split:
- `clpr.verifierGasLimit` — gas allowed for the `verifyBundle` / `verifyConfig` call
itself.
- `ClprThrottles.maxGasPerMessage` — the per-Channel throttle (from
`LEDGER_CONFIGURATION`, spec §1.1 / §6.0) that `ClprSubmitBundleHandler` allocates for
*each* application-message dispatch (downstream of verification). Don't confuse the
two. (This replaced the former `clpr.appDispatchGasLimit` node-config field, #129.)

Return values are decoded from the EVM call's output bytes back into
`ClprLedgerConfiguration` / `ClprBundleContent` protos.

### CLPR system precompile (`0x16e`)

Heavy lifting — TSS aggregate signature verification, Merkle path walking, state-value
decoding — is exposed to verifier contracts as a native system precompile at address
`0x16e`. Methods:

```solidity
function verifyConfig(bytes ledgerId, bytes proofBytes) external returns (bytes);
function verifyBundle(bytes ledgerId, bytes stateProofBytes) external returns (bytes);
```

Both compute the block-root hash from the first `state_item_leaf` path in the supplied
`StateProof`, call `TSS.verifyTSS(ledgerId, signature, blockRootHash)` once via
`NativeTssVerifier`, then re-verify every remaining path against that root before
decoding the inner state values and returning a PBJ-encoded
`ClprLedgerConfiguration` / `ClprBundleContent`. The TSS signature is self-authenticating
against `ledgerId` (it carries the hinTS verification key plus a WRAPS recursive proof
binding the key to the ledgerId) — no per-ledger key registry is needed on the verifying
side.

Implementations: `VerifyBundleCall`, `VerifyConfigCall`, and `ClprProofExtraction` under
`hedera-node/hedera-smart-contract-service-impl/.../systemcontracts/clpr/verify/`.

## Sei / CometBFT source proofs

Hiero exposes a native Sei verifier system contract at `0x170`. It is the CometBFT /
IAVL analog of the Besu QBFT verifier at `0x16f` and supports:

```solidity
function verifyConfig(bytes configPayload) external returns (bytes);
function verifyBundle(bytes bundlePayload, bytes trustAnchor) external returns (bytes);
```

`verifyConfig` returns PBJ-encoded `ClprLedgerConfiguration` bytes with a verifier-derived
initial `SeiTrustAnchor`. It does not prove the configuration; it decodes the
self-describing config payload and seeds the initial trust anchor. `verifyBundle` returns
PBJ-encoded `ClprBundleContent` bytes. The current implementation delegates bundle proof
checks to `SeiCometBftProofVerifier`, then the system-contract wrapper validates the
returned content metadata against the Merkle-proven queue metadata and reconciles any
trust-anchor rotation.

### Payloads

The wire types are defined in
`hapi/.../proto/services/state/clpr/clpr_sei_ledger_configuration_payload.proto`.

`ClprSeiLedgerConfigurationPayload` contains:

- `initial_validator_set` — the CometBFT validator set used to seed the initial
  `SeiTrustAnchor`.
- `initial_validator_set_height` — the latest height that the initial validator
  set can verify.
- `ledger_configuration` — the CLPR configuration read from the Sei CLPR service
  contract.

`ClprSeiBundlePayload` contains:

- `state_proof` — one signed CometBFT header plus ICS-23 proofs for the five queue
  metadata storage slots.
- `bundle_content` — verbatim PBJ-encoded `ClprBundleContent` bytes.
- `next_validator_set` — optional rotation evidence. It is required when
  `header.next_validators_hash` differs from the current trust-anchor validator-set
  hash.
- `prior_validator_set_updates` — an ordered chain of signed-header and next-set
  pairs used to advance an older trust anchor before verifying `state_proof`.

`SeiTrustAnchor` is the verifier-owned channel state stored in
`Channel.trust_anchor`. It carries the Sei `chain_id`, the latest height the set
can verify, the trusted validator set, and the CLPR service contract EVM address. The
verifier derives the initial trust anchor from the config payload during `verifyConfig`
and may emit a successor anchor during `verifyBundle`. Its opaque 40-byte identifier is
the validator-set hash followed by the height as an eight-byte big-endian integer:

```text
validator_set_hash || height
```

### State-Proof Rule

For a Sei source ledger, a state proof is based on a single CometBFT signed header
plus ICS-23 state proofs. It does **not** need a chain of `N` later block headers as a
confirmation-depth rule. CometBFT finality comes from a commit signed by more than two
thirds of the trusted validator voting power for that block.

The important qualifier is "trusted". A verifier must not accept a latest header just
because the payload carries a validator set and enough signatures from that same set.
The header is acceptable only when the verifier's current trust anchor authenticates it:

1. Hash the current `SeiTrustAnchor.validator_set`.
2. Require it to equal `signed_header.header.validators_hash`.
3. Recompute the header hash; this is the `BlockID.hash` used in precommit sign bytes.
4. Use the compact commit's `signers_bits` to map each signature to the corresponding
   trusted validator, then verify enough commit signatures from the current trusted set.
5. Verify the ICS-23 multistore and IAVL storage proofs against the header `app_hash`.

The `SeiStateProof` uses the CometBFT app-hash lag: storage values at state height `H`
are proven against the signed header at height `H + 1`, because that header's
`app_hash` commits the application state after executing height `H`.

### Header Hash, Validator Hash, and Precommit Bytes

`SeiHashing` contains the CometBFT-specific hashing and signature byte construction used
by the verifier. These bytes are separate from ICS-23/IAVL hashing, which lives in
`SeiIcs23` and `SeiMerkle`.

The verifier authenticates the signed header in three related steps:

1. `validatorSetHash(validator_set)` computes the CometBFT validator-set hash. The
   trusted set is encoded as `SimpleValidator{pub_key{ed25519}, voting_power}` leaves
   in canonical validator order, then hashed with the Tendermint simple Merkle tree. The
   result must equal `signed_header.header.validators_hash`.
2. `headerHash(header)` computes the CometBFT block hash. It is the Tendermint simple
   Merkle root over the 14 canonical header fields, including `chain_id`, `height`,
   `time`, `last_block_id`, `validators_hash`, `next_validators_hash`, and `app_hash`.
   This recomputed hash becomes `BlockID.hash`.
3. Each commit signature is verified against `precommitSignBytes(...)`, not against the
   raw header bytes. A precommit is the canonical vote a validator signed during
   CometBFT consensus.

The canonical precommit vote bytes are:

```text
protoio.MarshalDelimited(
  CanonicalVote{
    type: PRECOMMIT,
    height: signed_header.header.height,
    round: compact_commit.round,
    block_id: {
      hash: headerHash(signed_header.header),
      part_set_header: {
        total: compact_commit.part_set_total,
        hash: compact_commit.part_set_hash
      }
    },
    timestamp: commit_signature.timestamp,
    chain_id: signed_header.header.chain_id
  }
)
```

Important consequences:

- The commit signature is over the canonical precommit vote bytes, not over the raw
  block header.
- `BlockID.hash` is reconstructed from the header, so it does not need to be carried in
  the payload.
- `part_set_total` and `part_set_hash` are still required because they are part of the
  signed `BlockID` and are not reconstructable from the header alone.
- Every validator can sign a different byte string for the same block because the
  `timestamp` comes from that validator's own `CommitSig`.
- The validator address is derived as the first 20 bytes of `SHA256(ed25519_pub_key)`;
  the payload does not need to carry `validator_address` per signature because
  `signers_bits` maps signatures to trusted validator-set entries.

The storage proofs are two-layer ICS-23 membership proofs:

1. A Tendermint multistore proof checks `store_key == "evm"` and proves the Sei EVM
   module store root against the signed header `app_hash`.
2. One IAVL proof per storage slot proves
   `0x03 || serviceAddress || slot` to a 32-byte value against that EVM store root.

The `iavl_proof` field is not a concatenation of custom proof fragments. It is the raw
protobuf serialization of an ICS-23 `CommitmentProof`. The verifier parses that byte
array, requires the `exist` variant, and evaluates its `ExistenceProof`:

```text
ExistenceProof {
  key,
  value,
  leaf,
  path[]  // repeated InnerOp from leaf toward the IAVL root
}
```

For the leaf, the verifier checks the proof key/value match the storage entry and
computes the ICS-23 leaf hash. For IAVL this uses SHA-256 with the proof's configured
leaf prefix, the varint-length-delimited key, and the SHA-256 prehash of the value. Then
the verifier walks each `InnerOp` in `path`:

```text
node = leaf_hash
for op in path:
  node = SHA256(op.prefix || node || op.suffix)
```

The final `node` must equal the EVM module store root proven by the multistore proof.
A real proof may have many `path` entries; for example, 29 entries means 29 IAVL inner
hashing steps from the storage leaf up to the EVM store root, not 29 separate proofs.

For `verifyConfig`, exactly one storage proof is expected: the CLPR service-address
configuration slot. For `verifyBundle`, exactly five storage proofs are expected in the
same queue-metadata order used by the QBFT verifier: last-message running hash, packed
channel status/next-message slot, packed received-message slot, sent running hash,
and received running hash.

### Compact Commit

The Sei payload intentionally carries a compact commit, not the raw CometBFT commit:

- `height` is reconstructed from `signed_header.header.height`.
- `BlockID.hash` is reconstructed by hashing the signed header.
- `round`, `part_set_total`, and `part_set_hash` remain in the payload because they are
  part of each validator's canonical precommit sign bytes and are not reconstructable
  from the block header.
- `validator_address` is not carried per signature. Instead, `signers_bits` maps each
  compact signature to the trusted validator-set index.

`signers_bits` is interpreted most-significant-bit first within each byte. Bit `0`
selects `validator_set.validators[0]`, bit `1` selects `validator_set.validators[1]`,
and so on. The `signatures` list is ordered by the selected validator indices. For
example, `0010_0011` means:

```text
signatures[0] -> validator[2]
signatures[1] -> validator[6]
signatures[2] -> validator[7]
```

The verifier rejects:

- a `signers_bits` length that does not match the trusted validator-set size,
- bits set beyond the end of the validator set,
- fewer or more signatures than selected bits,
- any invalid Ed25519 signature for the derived validator public key,
- signed voting power that does not exceed two thirds of total trusted power.

The quorum rule is voting-power based, not signature-count based:

```text
valid iff signed_power * 3 > total_trusted_power * 2
```

The relay may trim extra signatures, but the verifier is responsible for enforcing that
the included signatures are exactly the validators selected by `signers_bits` and that
their voting power crosses the threshold.

### Validator-Set Updates

Additional signed headers are only needed when the verifier's trust anchor must advance
across validator-set changes. The verifier processes a bundle in three stages:

1. Verify and apply every `prior_validator_set_updates` entry against the running trust
   anchor. Each entry must change the validator set and advances the anchor height to the
   signed-header height plus one.
2. If `state_proof` is present, verify its signed header and storage proofs against the
   validator set produced by stage 1.
3. If the state-proof header's `next_validators_hash` differs from its
   `validators_hash`, require and authenticate `next_validator_set`, then advance the
   anchor again to the state-proof header height plus one.

If the current trust anchor is old and the latest header is signed by a newer validator
set, the endpoint provides the transition evidence in `prior_validator_set_updates`, for
example:

```text
trusted set A
  -> header signed by A commits next set B
  -> header signed by B commits next set C
  -> latest state proof signed by C
```

So the rule is:

```text
one signed header is sufficient for state proof finality
iff header.validators_hash matches the current trust anchor.
extra headers are for validator-set trust-anchor updates, not for finality depth.
```

When bundle-size limits prevent the endpoint from including enough transitions to reach
the validator set that signs the latest state proof, it sends a trust-update-only bundle.
Such a bundle contains one or more `prior_validator_set_updates` entries and omits
`state_proof`, `bundle_content`, and `next_validator_set`. It succeeds only by returning
the final advanced trust anchor, so the next bundle can continue from that point.

Future Sei verifiers may implement CometBFT light-client skipping or bisection proofs
to reduce sequential trust-anchor updates when validator-set overlap is sufficient. Until
that is explicitly implemented and verified, a bundle must provide enough signed
transition evidence for the verifier to move from its current anchor to the validator set
that signs the state proof. See the
[CometBFT light-client specification](https://cosmos-docs.mintlify.app/cometbft/latest/spec/light-client/Light-Client-Specification)
for the general validator-set update model.

### Sei System-Contract Return Checks

For a normal bundle, `SeiCometBftProofVerifier.verifyBundle` returns the verified block
hash, the relayed `ClprBundleContent` bytes, Merkle-proven queue metadata, and optionally
the final verifier-computed successor trust anchor. Before the EVM system contract
returns success, it parses the relayed `ClprBundleContent` and enforces:

- `content.metadata.next_message_id` equals the proven queue metadata.
- `content.metadata.received_message_id` equals the proven queue metadata.
- `content.metadata.state` equals the proven channel status slot.
- `content.metadata.sent_running_hash` and `received_running_hash` equal the proven
  storage values.
- If no rotation was proven, `content.new_trust_anchor` must be empty.
- If rotation was proven, the returned `new_trust_anchor` is the verifier-computed
  successor. A relay may omit it or echo the same bytes, but it cannot substitute a
  different successor.

For a trust-update-only bundle, the system contract instead synthesizes a
`ClprBundleContent` containing only the final `new_trust_anchor` and
`new_trust_anchor_id`. This lets the CLPR service persist progress without messages or
queue metadata.

This keeps source-ledger proof verification and CLPR bundle-content validation together:
the relay provides the bundle content, but the native Sei verifier decides whether the
metadata and trust-anchor fields are consistent with the authenticated Sei state.

### `ClprLedgerVerifier.sol`

A bundled Solidity verifier
(`hedera-node/test-clients/src/main/resources/contract/contracts/ClprLedgerVerifier/`)
that pins a specific peer `ledgerId` at construction and forwards `verifyConfig(bytes)` /
`verifyBundle(bytes)` calls to the system precompile at `0x16e` with that pinned ledger
id as the first argument. This is the canonical "Hiero-source" verifier and is what
`contracts deploy-clpr-verifier` (yahcli) deploys for the end-to-end demo.

## Where verifier addresses come from

A verifier `ContractID` lives in two places:

1. **Per-channel** (spec §5.1.5 — verifier immutability): the channel record
   `ClprChannel.verifier_contract` is set at `completeChannel` time and never
   changes for the life of the channel.
2. **Per-config**: the `ClprLedgerConfiguration` itself carries a config-verifier address
   that is used to authenticate `ClprConfigUpdate` control messages (lazy propagation).

`ClprSubmitBundleHandler` reads (1) when verifying inbound message bundles, and (2)
implicitly when applying any prepended `ConfigUpdate`.

## Verification flow inside `ClprSubmitBundleHandler` (spec §4.2)

1. Lookup channel.
2. Resolve verifier via `ClprVerifierFactory.resolve(channel.verifier_contract)`.
3. Call `verifyBundle(payload, ctx)` — receives `ClprBundleContent`.
4. Hiero-side checks (these run *after* the verifier returns and are NOT delegated to the
   contract):
   - Monotonic + contiguous message IDs against `channel.last_received_message_id`.
   - Recompute received running hash with `ClprHashUtils` and compare.
   - Response ordering invariant (spec §4.5).
5. Apply control messages then dispatch data messages. Each app dispatch is a child
   contract call with `clpr.appDispatchGasLimit`.

A `verifyBundle` failure → `CLPR_BUNDLE_VERIFICATION_FAILED`. A running-hash or ordering
failure → channel moves to `PAUSED` (spec §4.5).

## Gotchas

- **Reentrancy:** the verifier contract and target application contracts can call back
  into Hiero. Handlers run inside the regular consensus call frame; the same reentrancy
  semantics as any other contract call apply (spec §8.5). No CLPR-specific reentrancy
  guard exists.
- **Reorg risk:** the verifier may need to require finality on the source side
  (spec §8.3); that policy is encoded in the verifier contract, not in Hiero.
- **Upgradeability:** spec §8.8 — proxy verifiers are dangerous; the channel
  immutability rule (§5.1.5) does not stop a verifier owner from rotating implementations
  underneath. There is no Hiero-side check for this.
- **No native-bypass yet:** the built-in registry in `ClprVerifierFactory` is
  deliberately empty pending CLPR-5.3. All verifications go through `EvmClprVerifier`
  today, including Hiero-source bundles which thus pay an EVM round-trip.

## Verifier ABI: returned PBJ wire format

Verifier contracts called by `EvmClprVerifier` must return their result as
**PBJ-encoded `ClprBundleContent` bytes**. This is the Hiero-specific ABI for the
`verifyBundle(bytes bundlePayload) returns (bytes)` function.

`ClprBundleContent` is defined in
`hapi/.../proto/services/state/clpr/clpr_bundle_content.proto` and carries:

- **Queue metadata** — the message-ID range and running hash covering this bundle.
- **`repeated ClprMessagePayload messages`** — one entry per slot in the bundle, in
  order. Each entry is a proto one-of:
  - `ClprMessage` — a data payload destined for an application contract.
  - `ClprMessageReply` — a response to a previously sent data message.
  - `ClprControlMessage` — a control payload (e.g., `ConfigUpdate`).
  - *(no one-of set)* — a redacted slot. `ClprSubmitBundleHandler` treats an empty
    one-of as a redacted message and must emit a `REDACTED` reply for that slot
    (see C-1 in [DRIFT-REVIEW-2026-05.md](DRIFT-REVIEW-2026-05.md)).

`EvmClprVerifier` decodes the return bytes using `ClprBundleContent.PROTOBUF`. Any
return value that fails PBJ decoding causes `verifyBundle` to throw, which the handler
maps to `CLPR_BUNDLE_VERIFICATION_FAILED`.

Verifier contracts that are Solidity-native must ABI-encode a `bytes` return value
containing the raw PBJ (not ABI-encoded proto); `EvmClprVerifier` strips the outer
ABI `bytes` wrapper and feeds the inner bytes directly to PBJ.

**Note:** this format is the de-facto Hiero standard. See E-8 in
[`clpr-hiero-spec.md`](clpr-hiero-spec.md) and S-10 in the drift review for the
ongoing effort to promote `ClprBundleContent` to the canonical spec.

## Adding a built-in verifier

(When you want to skip EVM round-trip for a known proof system.)

1. Implement `ClprVerifier` (e.g. `HieroStateProofVerifier`) under `verifier/`.
2. Register it by `ContractID` (or sentinel) inside `ClprVerifierFactory`.
3. Make sure `ClprChannel.verifier_contract` lookups can dispense it without
   construction cost (the factory is called per submit-bundle handle).
4. Add unit tests parallelling `EvmClprVerifier` tests.
