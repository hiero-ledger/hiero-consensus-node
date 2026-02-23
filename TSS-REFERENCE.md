# TSS Signature Verification — Technical Reference

## What is TSS?

TSS (Threshold Signing Schema) is the mechanism by which the Hedera network produces block signatures. It combines two cryptographic protocols:

1. **hinTS** — A threshold BLS signature scheme. A committee of nodes each produce partial BLS signatures on a block hash. These are aggregated into a single compact signature verifiable against a succinct verification key (VK). The VK is produced during a preprocessing step that runs whenever the roster changes.

2. **WRAPS (History)** — A Schnorr-based chain-of-trust protocol. Each roster transition produces a proof that the new roster's hinTS VK was authorized by the previous roster. These proofs are chained back to genesis. In production, the chain is compressed into a SNARK (zero-knowledge proof) for compact, self-contained verification.

## The Effective Signature

Every signed block carries an **effective signature** that bundles everything a verifier needs:

```
[ VK: 1480 bytes ] [ hinTS aggregate: 1632 bytes ] [ chain-of-trust proof: 704 or 192 bytes ]
```

- **VK (1480 bytes)**: The hinTS verification key for the signing committee
- **hinTS aggregate (1632 bytes)**: The threshold BLS aggregate signature over the block hash
- **Chain-of-trust proof**: Proves the VK was authorized by the ledger's genesis
  - **704 bytes (SNARK)**: Compressed proof — self-contained, no external state needed
  - **192 bytes (Schnorr aggregate)**: Uncompressed fallback — requires the verifier to already know the address book

The signature is composed by `TSS.composeSignature(vk, hintsAgg, chainProof)` in `BlockStreamManagerImpl.finishProofWithSignature()`.

## Verification

Verification is done via a single call:

```java
TSS.verifyTSS(byte[] ledgerId, byte[] effectiveSignature, byte[] blockHash)
```

- **`ledgerId`**: A 32-byte hash of the genesis address book. This is the trust anchor — the cryptographic identity of the ledger.
- **`effectiveSignature`**: The full signature from the block proof.
- **`blockHash`**: The Merkle root of the block being verified.

### Two Verification Paths

`TSS.verifyTSS` automatically dispatches based on the size of the chain-of-trust proof:

| Chain proof size | Path | External state needed? |
|---|---|---|
| 704 bytes | SNARK | **None** — the proof is self-contained |
| 192 bytes | Schnorr aggregate | **Yes** — requires `TSS.setAddressBook()` to be called first |

In production with WRAPS enabled, the SNARK path is used. The Schnorr aggregate is a transitional state used at genesis (before the SNARK prover finishes) or when WRAPS is disabled.

## Where `ledgerId` Comes From

The `ledgerId` is the hash of the genesis address book, computed by `WRAPS.hashAddressBook(publicKeys, weights, nodeIds)`. It's published to the block stream via a `LEDGER_ID_PUBLICATION` synthetic transaction and stored in state as a singleton (`LEDGER_ID_STATE_ID` in the history service schema).

The `LEDGER_ID_PUBLICATION` transaction's handler is a NOOP — the transaction exists solely for block stream externalization. The actual state update is done by the history service when it completes a proof construction.

## Where `TSS.setAddressBook` is Called

`TSS.setAddressBook(byte[][] publicKeys, long[] weights, long[] nodeIds)` sets global/static state used by the Schnorr verification path. It is called:

- **In `StateChangesValidator`** (test utility): Reads `LEDGER_ID_PUBLICATION` from the block stream and extracts the Schnorr public keys, weights, and node IDs.
- **Never in production application code**: Because production uses the SNARK path, which is self-contained and doesn't need the address book.

## Key Architectural Decisions

### The `TssSignatureVerifier` Interface

`hapi-utils` (where `StateProofVerifier` lives) cannot depend on `hedera-cryptography-wraps` (where `TSS` lives). To bridge this, we introduced a functional interface:

```java
// In hapi-utils
@FunctionalInterface
public interface TssSignatureVerifier {
    boolean verify(Bytes blockHash, Bytes signature);
}
```

And a new overload:

```java
// In StateProofVerifier
public static boolean verify(StateProof stateProof, TssSignatureVerifier tssVerifier)
```

The caller provides the verification logic as a lambda, keeping `hapi-utils` free of crypto dependencies.

### CLPR Cross-Ledger Verification

When verifying a state proof from a remote ledger:

1. The remote's `ClprLedgerConfiguration` (being proved) contains the remote's `ClprLedgerId`
2. The state proof carries the remote's `effectiveSignature` (with embedded SNARK proof)
3. We call `TSS.verifyTSS(remoteLedgerId, effectiveSignature, blockHash)`
4. The SNARK proof internally verifies the chain of trust from the claimed `remoteLedgerId` to the VK that produced the signature

**Using the self-asserted `ledgerId` is cryptographically safe**: if an attacker claims a false `ledgerId`, the SNARK proof won't match, and verification fails. The SNARK proof binds the signing keys to a specific genesis — you can't forge a proof for a different genesis.

Whether to *trust* a particular genesis identity is a separate policy question (analogous to trusting a CA root certificate). Production CLPR will restrict accepted ledger IDs to a known set.

## Production Flow: Signing a Block

1. **hinTS preprocessing**: When roster changes, `HintsLibrary.preprocess(crs, hintsKeys, weights, n)` produces VK (1480 bytes) and AK (1712 bytes)
2. **History proof construction**: `WrapsHistoryProver` runs the 3-round WRAPS protocol (R1/R2/R3), aggregates Schnorr signatures, then runs the SNARK prover to compress the chain-of-trust proof
3. **Block signing**: `TssBlockHashSigner` produces a `BlockHashSigner.Attempt` with `(verificationKey, chainOfTrustProof, signatureFuture)`. The future resolves to the hinTS BLS aggregate.
4. **Composition**: `BlockStreamManagerImpl.finishProofWithSignature()` composes `effectiveSignature = VK || hintsAgg || chainProof` and writes it into the block proof

## Production Flow: Verifying a Block

1. **Extract signature**: From `StateProof.signedBlockProof().blockSignature()` (this is the full `effectiveSignature`)
2. **Compute root hash**: `StateProofVerifier.computeRootHash(stateProof.paths())` reconstructs the Merkle root from the proof's paths
3. **Verify TSS**: Call `TSS.verifyTSS(ledgerId, effectiveSignature, rootHash)`
   - Splits signature into VK, hintsAgg, chainProof
   - Verifies BLS aggregate against VK and block hash
   - Verifies chain-of-trust proof (SNARK or Schnorr) anchored at `ledgerId`

## Key Files

| File | Purpose |
|---|---|
| `hedera-app/.../blocks/impl/BlockStreamManagerImpl.java` | Composes effectiveSignature, writes block proofs |
| `hedera-app/.../blocks/impl/TssBlockHashSigner.java` | TSS block signer implementation |
| `hedera-app/.../blocks/BlockHashSigner.java` | Signer interface with `Attempt` record |
| `hedera-app/.../hints/HintsLibrary.java` | hinTS crypto operations interface |
| `hedera-app/.../hints/impl/HintsLibraryImpl.java` | hinTS JNI implementation |
| `hedera-app/.../history/HistoryLibrary.java` | WRAPS/Schnorr crypto operations interface |
| `hedera-app/.../history/impl/HistoryLibraryImpl.java` | WRAPS JNI implementation |
| `hedera-app/.../history/impl/WrapsHistoryProver.java` | WRAPS MPC protocol state machine |
| `hedera-app/.../history/impl/HistoryServiceImpl.java` | History service lifecycle |
| `hedera-app/.../history/handlers/HistoryProofKeyPublicationHandler.java` | Handles Schnorr key publication |
| `hedera-app/.../workflows/handle/record/SystemTransactions.java` | `externalizeLedgerId()` dispatches `LEDGER_ID_PUBLICATION` |
| `hapi-utils/.../blocks/StateProofVerifier.java` | State proof verification (Merkle + TSS) |
| `hapi-utils/.../blocks/TssSignatureVerifier.java` | Functional interface for TSS verification |
| `test-clients/.../validators/block/StateChangesValidator.java` | Test utility that calls `TSS.setAddressBook` + `TSS.verifyTSS` |
| `clpr-impl/.../ClprStateProofManager.java` | CLPR state proof construction and validation |
| `clpr-impl/.../ClprEndpointClient.java` | CLPR cross-ledger protocol client |
| `clpr-impl/.../handlers/ClprSetLedgerConfigurationHandler.java` | Handles remote ledger config submissions |

## Current State (CLPR Branch)

**Done:**
- Fixed `BlockStreamManagerImpl` to pass `effectiveSignature` (not raw `blockSignature`) to CLPR registration
- Created `TssSignatureVerifier` functional interface in `hapi-utils`
- Added `StateProofVerifier.verify(StateProof, TssSignatureVerifier)` overload
- Wrote end-to-end test proving real TSS verification works

**Remaining:**
- Wire CLPR callers (`ClprEndpointClient`, `ClprStateProofManager`, `ClprStateProofUtils`) to use the new `verify(StateProof, TssSignatureVerifier)` overload instead of the Phase 1 mock
- Inject a `TssSignatureVerifier` implementation via Dagger (the CLPR module can't call `TSS` directly — wrong module boundary)
- Determine how `ledgerId` flows into the verifier for both local and remote proof validation
- Validate with `ClprShipOfTheseusSuite`
