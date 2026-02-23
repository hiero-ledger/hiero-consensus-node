# ClprShipOfTheseusSuite TSS Failure — Debug State

This document captures the current state of debugging the TSS signature verification
failure in `ClprShipOfTheseusSuite`. It is intended for AI consumption in future sessions.

## The Symptom

The integration test `ClprShipOfTheseusSuite` logs:

```
ClprEndpointClient - Found invalid state proof for local ledger
```

This occurs at `ClprEndpointClient.java:~130` when `stateProofManager.verifyProof()`
returns `false`.

## Context: What Changed

The commit `c1e324e2eb` ("CLPR: current (non-functional) state of TSS") on branch
`clpr-with-tss` (branched from `21739-extend-state-proofs`) contains all the changes.
The parent commit is `530a00b2dd`.

### The core change: Phase 1 mock → real TSS verification

**Before this commit**, state proof verification used a Phase 1 mock:
```java
// ClprEndpointClient (OLD):
final boolean isValid = StateProofVerifier.verify(requireNonNull(localProof));

// StateProofVerifier.verify (mock) verifyTssSignature method:
return Arrays.equals(rootHash, signatureBytes.toByteArray());
```
The "signature" was the **block hash** (same bytes as the root hash), so the mock
verification trivially passed: `rootHash == blockHash == signatureBytes`.

**After this commit**, verification uses real TSS:
```java
// ClprEndpointClient (NEW):
final boolean isValid = stateProofManager.verifyProof(requireNonNull(localProof), localLedgerId.ledgerId());

// ClprStateProofManager.verifyProof:
final var verifier = tssVerifierFactory.forLedger(ledgerId);
return StateProofVerifier.verify(proof, verifier);

// StateProofVerifier.verify with TssSignatureVerifier:
// 1. Recompute rootHash from Merkle paths
// 2. Call tssVerifier.verify(rootHash, signatureBytes)

// Production TssSignatureVerifier (BlockStreamModule):
(blockHash, sig) -> TSS.verifyTSS(ledgerId.toByteArray(), sig.toByteArray(), blockHash.toByteArray())
```

### Supporting changes in the commit

1. **`BlockProvenStateAccessor.latestSnapshot()`** — Changed from `meta.blockHash()` to
   `meta.tssSignature()`. Previously the snapshot's `tssSignature` field was filled with
   the block hash (to make the Phase 1 mock work). Now it uses the actual TSS signature.

2. **`BlockStreamManagerImpl.registerBlockMetadata()`** — Two fixes:
   - Changed `blockSignature` → `effectiveSignature` (the composite TSS signature:
     `verificationKey + blockSignature + wrapsProof`)
   - Changed `this.blockTimestamp` → `currentPendingBlock.blockTimestamp()` (was using
     the current open block's timestamp instead of the signed block's timestamp)

3. **`BlockStreamModule`** — Added `provideTssSignatureVerifierFactory()` that wires
   `TSS.verifyTSS(ledgerId, signature, blockHash)` as the production verifier.

4. **`ClprModule`** — `ClprStateProofManager` now receives `TssSignatureVerifierFactory`
   via Dagger injection.

5. **`ClprStateProofManager`** — New `verifyProof()` method; removed dev-mode gates from
   `getLocalLedgerId()`, `getLedgerConfiguration()`, `validateStateProof()`.

6. **`ClprEndpointClient`** — Switched from `StateProofVerifier.verify(proof)` to
   `stateProofManager.verifyProof(proof, ledgerId)`. Added diagnostic logging for config
   key mismatches.

7. **`HandleWorkflow`** — After history service genesis proof completion, the callback now:
   - Writes `ClprLocalLedgerMetadata` with the authoritative genesis `ledgerId`
   - Re-keys CLPR config from the provisional `rosterHash` key to the authoritative
     `ledgerId` key (because genesis bootstrap stores config under `rosterHash` before
     the address book hash is known)
   - Commits both changes atomically

8. **`SystemTransactions`** — Added `maybeDispatchClprBootstrap()` call after ledger ID
   publication so the CLPR bootstrap re-dispatches under the authoritative ledgerId.

9. **`ClprSetLedgerConfigurationHandler`** — In production mode, `handle()` no longer
   bootstraps `metadataLedgerId` from the incoming config. Only dev mode does that
   fallback (since the HandleWorkflow bridge never fires without WRAPS genesis).

10. **`ClprShipOfTheseusSuite`** — Enabled TSS config for both networks:
    ```
    tss.hintsEnabled=true
    tss.historyEnabled=true
    tss.wrapsEnabled=true
    tss.initialCrsParties=16
    ```

11. **Tests** — Updated `BlockProvenStateAccessorTest` assertions from `BLOCK_HASH_A/B` to
    `TSS_SIGNATURE_A/B`. Added `MOCK_TSS_FACTORY` to `ClprTestBase` (replicates Phase 1
    behavior: `blockHash.equals(sig)`). Updated all `ClprStateProofManager` constructor
    calls and handler test mocks.

## What We Know

### The verification chain

For a state proof to verify, ALL of these must be correct:

1. **Merkle path computation** — `StateProofVerifier.computeRootHash(paths)` must
   reconstruct the correct block root hash from the sibling hashes in the proof.

2. **TSS signature** — `TSS.verifyTSS(ledgerId, effectiveSignature, rootHash)` must
   return true. The `effectiveSignature` is a composite:
   `verificationKey || blockSignature || wrapsProof`.

3. **Ledger ID** — The `ledgerId` passed to `TSS.verifyTSS` must be the correct genesis
   address book hash for the network.

### What was working before

The user confirmed: **"Prior to integrating the TSS signature, the block hashes were
matching exactly."** This means:
- The Merkle paths WERE correctly computing the block root hash
- The block root hash equaled the block hash (used as the mock "signature")
- Phase 1 mock verification passed: `rootHash == signatureBytes`

### What the user emphasized

The user then clarified: **"The Merkle paths _were_ correct."** (emphasis on WERE) —
suggesting the Merkle paths themselves may have recently broken, not just the TSS
signature verification.

## Open Questions

### Q1: Is the failure in the Merkle paths or in TSS.verifyTSS?

We don't know yet whether:
- (a) The Merkle path computation produces an incorrect root hash, OR
- (b) The root hash is correct but `TSS.verifyTSS` fails because the `effectiveSignature`
  or `ledgerId` is wrong

The diagnostic logging added in `StateProofVerifier.verifyTssSignature()` should reveal
this when the test is next run. It logs:
```
TSS verification failed: rootHash={hex} (len={}), signature len={}
```

### Q2: Has the block Merkle tree structure changed upstream?

The block tree in `BlockStreamManagerImpl.combine()` has 8 leaves:
```
prevBlockHash, prevBlockRootsHash, startingStateHash, consensusHeaderHash,
inputsHash, outputsHash, stateChangesHash, traceDataHash
```

If an upstream merge added or changed leaves (e.g., `stateChangesHash` or
`traceDataHash`), the `PartialPathBuilder` may not have been updated to match.

`PartialPathBuilder.startingStateToBlockRoot()` builds 3 siblings:
- Sibling 0: `consensusHeaderRootHash` (right of startingStateHash at depth 5)
- Sibling 1: `hash(prevBlockHash, prevBlockRootsHash)` (left at depth 4)
- Sibling 2: `siblingHashes[2]` = depth4Node2 = `hash(hash(inputs,outputs), hash(stateChanges,traceData))` (right at depth 3)

This matches the current `combine()` tree structure. But if `combine()` changed, these
assumptions break.

### Q3: Is the `effectiveSignature` correctly composed?

The `effectiveSignature` at `BlockStreamManagerImpl:770` is:
```java
// With WRAPS:
effectiveSignature = verificationKey
    .append(blockSignature)
    .append(chainOfTrustProof.aggregatedNodeSignaturesOrThrow().aggregatedSignature());
// Without WRAPS (hints only):
effectiveSignature = verificationKey.append(blockSignature);
// Without hints:
effectiveSignature = blockSignature;  // SHA-384 hash of blockHash
```

Does `TSS.verifyTSS` know how to decompose this composite format? The library is opaque
(native Rust code in `hedera-cryptography-tss`), so we can't inspect its logic from this
codebase.

### Q4: Is the `ledgerId` correct?

The ledgerId is written by the HandleWorkflow bridge after the history service completes
its genesis proof. It's the genesis address book hash (`WRAPS.hashAddressBook(genesisAddressBook)`).
The same ledgerId must be used when calling `TSS.verifyTSS`. If the bridge hasn't fired
yet when the endpoint client tries to verify, the ledgerId could be wrong or missing.

### Q5: Could the test be running verification before TSS is fully bootstrapped?

The TSS ceremony (CRS + hints + preprocessing + WRAPS) takes time. The test may be
attempting state proof verification before the TSS protocol is fully ready. The endpoint
client runs on a timer (`clpr.connectionFrequency=1000ms`), but the TSS setup could
take longer than that.

## Diagnostic Logging Added

These log statements are present in the committed code and will produce output on the
next test run:

1. **`ClprStateProofManager.verifyProof()`** — Logs on false return:
   ```
   State proof TSS verification returned false for ledger {}; proof has {} paths, signedBlockProof present={}
   ```
   Logs on exception:
   ```
   State proof verification threw exception for ledger {}
   ```

2. **`StateProofVerifier.verifyTssSignature()`** — Logs on TSS failure:
   ```
   TSS verification failed: rootHash={hex} (len={}), signature len={}
   ```

3. **`ClprEndpointClient`** — Logs all config keys when local config is missing:
   ```
   CLPR endpoint maintenance found no local configuration for ledger {}; ledgerId bytes={}
   CLPR endpoint maintenance: all config keys={}
   ```

4. **`ClprSetLedgerConfigurationHandler`** — Logs handle decisions:
   ```
   CLPR handler: configLedgerId={} metadataLedgerId={} ...
   ```

5. **`HandleWorkflow`** — Logs bridge commit:
   ```
   CLPR bridge: committed metadata+config ledgerId={} rosterHash={}
   ```

## Strategies Not Yet Tried

1. **Run the test and inspect the diagnostic logs** — The logging is in place but the
   test hasn't been run since the logging was added.

2. **Verify Merkle path correctness independently** — Use `StateProofVerifier.verifyRootHashForTest()`
   to check if the computed root hash matches the expected block hash. If it doesn't,
   the Merkle paths are broken regardless of TSS.

3. **Check if `combine()` changed upstream** — Run `git log` on `BlockStreamManagerImpl.java`
   and `PartialPathBuilder.java` to see if the block tree structure changed.

4. **Bypass TSS verification temporarily** — Replace the production verifier with
   `MOCK_TSS_FACTORY` (blockHash.equals(sig)) in the test to isolate whether the
   failure is in TSS or Merkle paths.

5. **Log the actual `effectiveSignature` composition** — Add logging in
   `BlockStreamManagerImpl` around line 770 to see what `verificationKey`,
   `blockSignature`, and `chainOfTrustProof` look like, and whether
   `chainOfTrustProof` is null.

6. **Check TSS readiness timing** — Log `TssBlockHashSigner.isReady()` to verify the
   TSS protocol has fully bootstrapped before the first block is signed.

## File Reference

Key files in the current state (all relative to repo root):

| File | Role |
|------|------|
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/blocks/impl/BlockStreamManagerImpl.java` | Composes effectiveSignature, calls registerBlockMetadata |
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/blocks/impl/PartialPathBuilder.java` | Builds Merkle path from state root to block root |
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/state/BlockProvenStateAccessor.java` | Caches snapshots, returns tssSignature |
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/blocks/BlockStreamModule.java` | Wires TSS.verifyTSS as production verifier |
| `hedera-node/hapi-utils/src/main/java/com/hedera/node/app/hapi/utils/blocks/StateProofVerifier.java` | Recomputes root hash from paths, calls TssSignatureVerifier |
| `hedera-node/hiero-clpr-interledger-service-impl/src/main/java/org/hiero/interledger/clpr/impl/ClprEndpointClient.java` | Where the error is logged |
| `hedera-node/hiero-clpr-interledger-service-impl/src/main/java/org/hiero/interledger/clpr/impl/ClprStateProofManager.java` | verifyProof(), getLocalLedgerId() |
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflow.java` | Bridge callback writing authoritative ledgerId |
| `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/interledger/ClprShipOfTheseusSuite.java` | The failing integration test |

## Unit Test Status

All unit tests pass (11/11 in `BlockProvenStateAccessorTest`, plus all CLPR handler and
manager tests). The failure is only in the integration test `ClprShipOfTheseusSuite`
which runs two real 4-node networks with full TSS enabled.
