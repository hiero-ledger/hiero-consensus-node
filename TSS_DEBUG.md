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

## ROOT CAUSE IDENTIFIED: Schnorr proof (no address book loaded)

**Previous diagnosis was WRONG.** The Merkle proof chain is correct — blockHash DOES
equal rootHash. The actual problem is the chain-of-trust proof type inside the TSS
effective signature.

### TSS Effective Signature Composition

The effective signature is: `VK (1480 bytes) + hinTS aggregate (1632 bytes) + chain-of-trust proof`

| Chain proof type | Size | Total sig size | External state needed? |
|-----------------|------|----------------|----------------------|
| SNARK (WRAPS)   | 704 bytes | 3816 bytes | None — self-contained |
| Schnorr aggregate | 192 bytes | 3304 bytes | Yes — `TSS.setAddressBook()` must be called first |

### What Happens

1. The SNARK prover hasn't finished by the time the CLPR endpoint client first runs
2. `finishProofWithSignature()` in `BlockStreamManagerImpl` falls back to a Schnorr
   aggregate proof (192 bytes), producing a 3304-byte effective signature
3. `TSS.verifyTSS()` dispatches to the Schnorr verification path
4. The Schnorr path requires `TSS.setAddressBook()` to have been called with public keys,
   weights, and node IDs
5. `TSS.setAddressBook()` is **never called in the application code** — only in the
   `StateChangesValidator` test utility
6. Verification returns `false`

### The Fix (implemented)

Added a SNARK-readiness check in `ClprStateProofManager.getLedgerConfiguration()`:

```java
private static final int MINIMUM_SNARK_EFFECTIVE_SIGNATURE_SIZE = 1480 + 1632 + 704; // 3816

// In getLedgerConfiguration(), after validating tssSigBytes is non-empty:
if (tssSigBytes.length() < MINIMUM_SNARK_EFFECTIVE_SIGNATURE_SIZE) {
    log.info("Deferring state proof construction: effective signature is {} bytes "
            + "(minimum {} for SNARK proof); SNARK prover may still be running",
            tssSigBytes.length(), MINIMUM_SNARK_EFFECTIVE_SIGNATURE_SIZE);
    return null;
}
```

The endpoint client already handles `null` gracefully by skipping the cycle and retrying
on the next interval. Once the SNARK prover finishes (typically within a few blocks), the
effective signature will be >= 3816 bytes and proofs will be built normally.

## What We Ruled Out

1. **Merkle tree structure mismatch** — `combine()` and `PartialPathBuilder` are in perfect
   alignment. Same 8 leaves, same order.

2. **Hashing inconsistency between BlockImplUtils and HashUtils** — Byte-level comparison
   confirmed identical output for all three hash operations (timestamp leaf, internal node,
   single-child).

3. **Merkle proof chain gap** — Step-by-step hash walk confirmed the full chain from KV
   leaf through VirtualMapState root through block root is correct. The intermediate hash
   at sib[9] (VirtualMapState root) equals `startingStateHash`, and the final ROOT hash
   equals `blockHash` from `combine()`.

4. **Missing state proof fields** — `hasSignedBlockProof=true`, `sigLen=3304`, `paths=1`.
   The proof is structurally complete.

5. **LedgerId mismatch** — The CLPR bridge fired on all 4 nodes. No "found no local
   configuration" errors.

6. **blockHash ≠ rootHash** — WRONG. They ARE equal. Confirmed by logging both values
   from the same node/block.

## The Block Tree Structure (from combine())

```
blockRoot (depth 1) = hash(depth2Node1, depth2Node2)
├── depth2Node1 = hashTimestampLeaf(timestamp)           [LEFT]
└── depth2Node2 = hashSingleChild(depth3Node1)           [RIGHT, single-child]
    └── depth3Node1 = hash(depth4Node1, depth4Node2)
        ├── depth4Node1 = hash(depth5Node1, depth5Node2)
        │   ├── depth5Node1 = hash(prevBlockHash, prevBlockRootsHash)
        │   └── depth5Node2 = hash(startingStateHash, consensusHeaderHash)
        └── depth4Node2 = hash(depth5Node3, depth5Node4)
            ├── depth5Node3 = hash(inputsHash, outputsHash)
            └── depth5Node4 = hash(stateChangesHash, traceDataHash)
```

PartialPathBuilder gives 3 siblings (startingState → depth3Node1).
buildMerkleStateProof adds 2 more (singleChild promotion + timestamp → blockRoot).

## Current Debug Logging in Code

Extensive debug logging exists in `ClprEndpointClient` (including a full `debugWalkProofHashes()`
method that walks every sibling step-by-step) and in `BlockProvenStateAccessor.registerBlockMetadata()`
and `latestSnapshot()`. This logging should be removed once the fix is confirmed working.

## Build Commands

From `/Users/matthess/ai/clpr/hiero-consensus-node-1`:

```bash
# Compile (clean CLPR module to avoid stale classes)
./gradlew :hiero-clpr-interledger-service-impl:clean :hiero-clpr-interledger-service-impl:compileJava :app:compileJava

# Run the integration test
./gradlew :test-clients:hapiTestMultiNetwork --rerun 2>&1 | tee /tmp/clpr-tss-test.log

# Search for diagnostic output
grep "TSS_DEBUG\|Deferring state proof" /tmp/clpr-tss-test.log | head -20
```

## File Reference

| File | Role |
|------|------|
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/blocks/impl/BlockStreamManagerImpl.java` | `combine()` produces block hash; `finishProofWithSignature()` composes effective signature and registers metadata |
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/blocks/impl/BlockImplUtils.java` | Hashing utilities used by `combine()` — `hashTimestampLeaf`, `hashInternalNode`, etc. |
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/blocks/impl/PartialPathBuilder.java` | Builds 3 siblings from startingState to depth3Node1 |
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/state/BlockProvenStateAccessor.java` | Caches snapshots, `registerBlockMetadata()` stores blockHash + tssSignature + path |
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/blocks/BlockStreamModule.java` | Wires `TSS.verifyTSS` as production verifier |
| `hedera-node/hapi-utils/src/main/java/com/hedera/node/app/hapi/utils/blocks/StateProofVerifier.java` | `computeRootHash()` reconstructs root from paths |
| `hedera-node/hapi-utils/src/main/java/com/hedera/node/app/hapi/utils/blocks/HashUtils.java` | Hashing utilities used by `StateProofVerifier` — `computeTimestampLeafHash`, `joinHashes`, etc. |
| `hedera-node/hiero-clpr-interledger-service-impl/src/main/java/org/hiero/interledger/clpr/impl/ClprStateProofManager.java` | `buildMerkleStateProof()` constructs the proof; `verifyProof()` validates it; **SNARK-readiness check added here** |
| `hedera-node/hiero-clpr-interledger-service-impl/src/main/java/org/hiero/interledger/clpr/impl/ClprEndpointClient.java` | Where the error is logged; contains diagnostic hash-walk logging |
| `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/interledger/ClprShipOfTheseusSuite.java` | The failing integration test |

## Context

- Branch: `clpr-with-tss` in `hiero-consensus-node-1`
- TSS commit: `c1e324e2eb` ("CLPR: current (non-functional) state of TSS")
- Parent commit: `530a00b2dd`
- All unit tests pass (72/73 in CLPR module; the 1 failure is a pre-existing NullPointerException in `ClprSetLedgerConfigurationHandlerTest.handleBootstrapsLedgerIdFromConfigInDevMode` unrelated to TSS)

## Next Steps

1. **Run integration test** to confirm the fix works — the endpoint client should log
   "Deferring state proof construction" for the first few blocks, then succeed once the
   SNARK prover finishes
2. **Clean up debug logging** — remove `debugWalkProofHashes()`, TSS_DEBUG warn-level
   logging in `BlockProvenStateAccessor`, and `TSS_DEBUG` logging in `ClprStateProofManager.verifyProof()`
3. **Fix the pre-existing test failure** — `handleBootstrapsLedgerIdFromConfigInDevMode`
   needs a mock `TssConfig` injected
