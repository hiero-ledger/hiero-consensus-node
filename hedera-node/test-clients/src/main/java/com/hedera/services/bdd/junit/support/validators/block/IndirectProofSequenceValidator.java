// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.BLOCK_CONTENTS_PATH_INDEX;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.EXPECTED_MERKLE_PATH_COUNT;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.FINAL_MERKLE_PATH_INDEX;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.FINAL_NEXT_PATH_INDEX;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.SIGNED_BLOCK_SIBLING_COUNT;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.UNSIGNED_BLOCK_SIBLING_COUNT;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockstream.MerkleLeaf;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
import com.swirlds.state.SiblingHash;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;

class IndirectProofSequenceValidator {
    private static final Logger log = LogManager.getLogger(IndirectProofSequenceValidator.class);

    /**
     * The number of partial merkle paths to store for each unsigned block. There's no need to store the
     * final parent merkle path, as it contains no data.
     */
    private static final int NUM_PARTIAL_PATHS_TO_STORE = EXPECTED_MERKLE_PATH_COUNT - 1;

    private final Map<Long, StateProof> expectedIndirectProofs = new HashMap<>();
    private final Map<Long, StateProof> actualIndirectProofs = new HashMap<>();

    private final Map<Long, Bytes> expectedBlockRootHashes = new HashMap<>();
    private final Map<Long, Bytes> expectedPreviousBlockRootHashes = new HashMap<>();

    /**
     * The signed block proof corresponding to the latest signed block number. This is needed to
     * verify the indirect proofs and must be the last proof in the sequence.
     */
    private BlockProof signedProof;
    /**
     * The consensus timestamp of the signed block corresponding to the latest signed block number.
     */
    private Timestamp signedBlockTimestamp;

    /**
     * This field represents all the unfinished blocks (potentially including the signed block
     * following a sequence of unsigned/indirect blocks)
     */
    private final Map<Long, Pair<PartialMerklePath, PartialMerklePath>> partialPathsByBlock = new HashMap<>();

    private long firstUnsignedBlockNum = -1;
    private long signedBlockNum;
    private boolean endOfSequenceReached = false;

    boolean containsIndirectProofs() {
        return !partialPathsByBlock.isEmpty();
    }

    /**
     * Tracks the block proof for a given block number. This method should be invoked for each unsigned block
     * <b>and</b> the signed block that ends the sequence.
     * <p>
     * This method must be called in sequential order for each block, up to and including the signed
     * block. Once the signed block is encountered, this method will call
     * {@link #endOfSequence(Timestamp, BlockProof)} to finalize the sequence. Once the end of the sequence
     * is signaled, no further indirect proofs can be registered.
     *
     * @param blockNumber       the block number for which the block proof is being tracked
     * @param proof             the actual block proof from the block (i.e. the test subject)
     * @param previousBlockHash the previous block's root hash
     * @param blockTimestamp    this block's consensus timestamp
     * @param siblingHashes     the sibling hashes for this block's proof (if any). This list should be
     *                          empty for a signed block; for an unsigned block, it should enumerate _all_
     *                          siblings in [currentBlock, signedBlockNum] (inclusive). Note that the
     *                          signed block's timestamp <b>must not</b> be included as a sibling hash;
     *                          it's handled separately
     * @throws IllegalStateException if the end of the sequence has already been reached, or if
     * the blocks are not tracked in sequential order
     */
    void registerProof(
            final long blockNumber,
            final @NonNull BlockProof proof,
            final @NonNull Bytes blockRootHash,
            final @NonNull Bytes previousBlockHash,
            final @NonNull Timestamp blockTimestamp,
            final List<MerkleSiblingHash> siblingHashes) {
        if (endOfSequenceReached) {
            throw new IllegalStateException(
                    "Cannot track indirect proof for block #%s: end of sequence previously reached"
                            .formatted(blockNumber));
        }
        if (firstUnsignedBlockNum < 0) {
            firstUnsignedBlockNum = blockNumber;
        }

        log.info("Registering proof for block {}", blockNumber);
        if (proof.hasSignedBlockProof()) {
            // Signal the end of the sequence
            endOfSequence(blockTimestamp, proof);
        } else {
            final var lastActualProofBlockNum =
                    actualIndirectProofs.keySet().stream().max(Long::compareTo).orElse(-1L);
            assertTrue(
                    proof.hasBlockStateProof(),
                    "Indirect proof for block %s is missing a block state proof".formatted(blockNumber));
            if (!expectedIndirectProofs.isEmpty() && lastActualProofBlockNum != blockNumber - 1) {
                throw new IllegalStateException(
                        ("Indirect proofs must be tracked in sequential order; last actual proof was "
                                        + "for block %s, current actual proof given is for block %s")
                                .formatted(lastActualProofBlockNum, blockNumber));
            }

            // Construct partial merkle paths for the expected indirect proof
            // Block's Merkle Path 1: timestamp path (left child of the block's (sub)root)
            final var timestampBytes = Timestamp.PROTOBUF.toBytes(blockTimestamp);
            final var mp1 = new PartialMerklePath(
                    MerkleLeaf.newBuilder()
                            .blockConsensusTimestamp(timestampBytes)
                            .build(),
                    null,
                    null);

            // Block's Merkle Path 2: block contents path
            // Technically we could roll the timestamp into the sibling hashes here, but for clarity we keep them
            // separate
            final var mp2 = new PartialMerklePath(null, previousBlockHash, siblingHashes);

            // Block's Merkle Path 3: parent (i.e. combined hash of left child and right child)
            // This is the combined result and should have no data

            this.partialPathsByBlock.put(blockNumber, Pair.of(mp1, mp2));

            // We can't verify the indirect proof until we have a signed block proof, so store the
            // indirect proof for later verification
            actualIndirectProofs.put(blockNumber, proof.blockStateProof());
        }

        expectedBlockRootHashes.put(blockNumber, blockRootHash);
        expectedPreviousBlockRootHashes.put(blockNumber, previousBlockHash);

        log.info("Registered proof for block {}", blockNumber);
    }

    /**
     * Verifies the contents of all proofs (unsigned and signed) in the sequence
     */
    void verify() {
        if (!endOfSequenceReached) {
            throw new IllegalStateException(
                    "Cannot verify indirect proof sequence: end of sequence not reached, signed proof missing");
        }

        log.info(
                "Beginning verification of indirect state proofs for blocks {} to {}",
                firstUnsignedBlockNum,
                signedBlockNum);

        verifyIndirectProofs();
        verifyHashSequence();

        log.info("Successfully verified sequence of proofs for blocks {} to {}", firstUnsignedBlockNum, signedBlockNum);
    }

    /**
     * This method signals the end of the indirect proof sequence, thereby preventing tracking for any further
     * block proofs. Triggers construction of all expected indirect proofs based on the partial proofs already
     * collected.
     *
     * @param signedProof the TSS-signed block proof that ends the indirect proof sequence
     */
    private void endOfSequence(@NonNull final Timestamp signedTimestamp, @NonNull final BlockProof signedProof) {
        this.endOfSequenceReached = true;
        this.signedBlockTimestamp = signedTimestamp;
        this.signedProof = requireNonNull(signedProof);
        this.signedBlockNum = signedProof.block();

        buildExpectedIndirectProofs();
    }

    private void buildExpectedIndirectProofs() {
        final long actualMinBlockNum =
                partialPathsByBlock.keySet().stream().min(Long::compareTo).orElseThrow();
        final long actualMaxBlockNum =
                partialPathsByBlock.keySet().stream().max(Long::compareTo).orElseThrow();
        final long actualNumIndirectBlocks = actualMaxBlockNum - actualMinBlockNum + 1;

        log.info(
                "Verifying state proofs for unsigned blocks from {} to (signed) block {}",
                firstUnsignedBlockNum,
                signedBlockNum);
        final var combinedPathsSize = partialPathsByBlock.keySet().stream()
                .sorted()
                .map(partialPathsByBlock::get)
                .mapToInt(ignore -> NUM_PARTIAL_PATHS_TO_STORE)
                .sum();
        assertEquals(
                actualNumIndirectBlocks * NUM_PARTIAL_PATHS_TO_STORE,
                combinedPathsSize,
                "Each unsigned block should have %s partial merkle paths. Registered unsigned blocks: %s"
                        .formatted(NUM_PARTIAL_PATHS_TO_STORE, partialPathsByBlock));
        for (long i = firstUnsignedBlockNum; i < signedBlockNum; i++) {
            final var registered = partialPathsByBlock.get(i);
            assertNotNull(
                    registered,
                    ("Unsigned block should be registered for every block between [%s, %s] –– block"
                                    + " %s not registered")
                            .formatted(firstUnsignedBlockNum, signedBlockNum - 1, i));
        }

        // First, construct full Merkle paths for each unsigned block
        final Map<Long, MerklePath[]> fullMerklePathsByBlockNum = constructExpectedMerklePaths();

        // Now that all the full Merkle paths have been constructed for each indirect block,
        // construct the full expected state proof for each unsigned block, beginning with the earliest
        for (long bn = firstUnsignedBlockNum; bn < signedBlockNum; bn++) {
            final var merklePaths = fullMerklePathsByBlockNum.get(bn);
            if (merklePaths == null) {
                throw new IllegalStateException("No full merkle paths found for block " + bn);
            }
            final var stateProof = StateProof.newBuilder()
                    .paths(fullMerklePathsByBlockNum.get(bn))
                    .signedBlockProof(signedProof.signedBlockProof())
                    .build();
            expectedIndirectProofs.put(bn, stateProof);
        }
        final long expectedNumIndirectBlocks = expectedIndirectProofs.size();
        assertEquals(expectedNumIndirectBlocks, actualNumIndirectBlocks);
    }

    /**
     * Verifies all collected state proofs
     */
    private void verifyIndirectProofs() {
        if (!containsIndirectProofs()) {
            throw new IllegalStateException("No indirect proofs to verify in indirect proof sequence");
        }
        if (!endOfSequenceReached || signedProof == null) {
            throw new IllegalStateException("Cannot verify indirect proofs: signed proof not provided");
        }

        // Verify the number of paths and required total paths size in each reconstructed state proof
        for (long i = firstUnsignedBlockNum; i < signedBlockNum; i++) {
            final var numRemainingBlocks = signedBlockNum - i;
            final var totalExpectedSiblings = expectedSiblingsFrom(numRemainingBlocks);

            final var numPathsForBlock = expectedIndirectProofs.get(i).paths().size();
            assertEquals(
                    EXPECTED_MERKLE_PATH_COUNT,
                    numPathsForBlock,
                    "Mismatch in number of reconstructed merkle paths: %s expected vs. %s actual"
                            .formatted(EXPECTED_MERKLE_PATH_COUNT, numPathsForBlock));

            final var totalActualSiblings = expectedIndirectProofs
                    .get(i)
                    .paths()
                    .get(BLOCK_CONTENTS_PATH_INDEX)
                    .siblings()
                    .size();
            assertEquals(
                    totalExpectedSiblings,
                    totalActualSiblings,
                    "Mismatch in number of reconstructed siblings for block %s: %s expected vs. %s actual"
                            .formatted(i, totalExpectedSiblings, totalActualSiblings));
        }

        // Compare each reconstructed state proof with the actual state proof for each unsigned block
        for (long i = firstUnsignedBlockNum; i < signedBlockNum; i++) {
            log.info("Verifying state proof for block {}", i);
            assertEquals(expectedIndirectProofs.get(i), actualIndirectProofs.get(i));
            log.info("Actual and expected state proofs for block {} are identical", i);
        }
    }

    /**
     * Given a signed block number, constructs full merkle paths for each registered indirect
     * proof.
     *
     * @return a mapping of block number to its corresponding full merkle paths
     */
    private Map<Long, MerklePath[]> constructExpectedMerklePaths() {
        if (!endOfSequenceReached) {
            throw new IllegalStateException("Cannot construct full merkle paths: signed proof not yet provided");
        }

        final Map<Long, MerklePath[]> fullMerklePathsByBlockNum = new HashMap<>();
        // Construct the full merkle paths for each pending block between [currentPendingBlock.number(),
        // latestSignedBlockNumber - 1]

        // Merkle Path 1: the block timestamp path (should be the same for all proofs)
        final var tsBytes = Timestamp.PROTOBUF.toBytes(signedBlockTimestamp);
        final var tsLeaf =
                MerkleLeaf.newBuilder().blockConsensusTimestamp(tsBytes).build();
        final var mp1 = MerklePath.newBuilder()
                .leaf(tsLeaf)
                .nextPathIndex(FINAL_MERKLE_PATH_INDEX)
                .build();

        // Merkle Path 2: enumerate all sibling hashes for remaining UNSIGNED blocks
        MerklePath.Builder earliestBlockMp2 = MerklePath.newBuilder();

        // Create a set of siblings for all unsigned blocks remaining, plus another set for the signed block (excluding
        // its timestamp)
        final var numBlocksRemaining = signedBlockNum - firstUnsignedBlockNum;
        final var totalExpectedSiblings = expectedSiblingsFrom(numBlocksRemaining);
        final SiblingNode[] allSiblingHashes = new SiblingNode[totalExpectedSiblings];
        var currentBlockNum = firstUnsignedBlockNum;
        for (int i = 0; i < numBlocksRemaining; i++) {
            final var currentBlockPaths = partialPathsByBlock.get(currentBlockNum);
            if (i == 0) {
                final var startHash = requireNonNull(currentBlockPaths.right().previousBlockHash());
                earliestBlockMp2.hash(startHash);
            }

            // Convert sibling hashes
            final var blockSiblings = currentBlockPaths.right().siblingHashes().stream()
                    .map(s -> new SiblingHash(!s.isFirst(), new Hash(s.siblingHash())))
                    .toList();
            // Copy into the sibling hashes array
            final var firstSiblingIndex = i * UNSIGNED_BLOCK_SIBLING_COUNT;
            for (int j = 0; j < blockSiblings.size(); j++) {
                final var blockSibling = blockSiblings.get(j);
                allSiblingHashes[firstSiblingIndex + j] = SiblingNode.newBuilder()
                        .isLeft(!blockSibling.isRight())
                        .hash(blockSibling.hash().getBytes())
                        .build();
            }

            currentBlockNum++;
        }
        earliestBlockMp2.siblings(Arrays.stream(allSiblingHashes).toList()).nextPathIndex(FINAL_MERKLE_PATH_INDEX);

        // Merkle Path 3: the root path (same for all proofs), and has no data specific to any block
        final var mp3 =
                MerklePath.newBuilder().nextPathIndex(FINAL_NEXT_PATH_INDEX).build();

        // We now have all sibling hashes needed for the earliest unsigned block's proof. Set the value in the map
        fullMerklePathsByBlockNum.put(firstUnsignedBlockNum, new MerklePath[] {mp1, earliestBlockMp2.build(), mp3});

        // Populate each remaining unsigned block's proof with the subset of needed sibling hashes
        for (long currUnsignedBlock = firstUnsignedBlockNum + 1;
                currUnsignedBlock < signedBlockNum;
                currUnsignedBlock++) {
            final var newMp2 = MerklePath.newBuilder();

            final var currUnsignedBlockSiblingsSize = expectedSiblingsFrom(signedBlockNum - currUnsignedBlock - 1);
            final var currUnsignedBlockSiblings = new SiblingNode[currUnsignedBlockSiblingsSize];
            final var currUnsignedBlockStartingIndex =
                    (int) ((currUnsignedBlock - firstUnsignedBlockNum) * UNSIGNED_BLOCK_SIBLING_COUNT);
            for (int i = currUnsignedBlockStartingIndex; i < allSiblingHashes.length; i++) {
                // Copy the subset of sibling hashes from the full set of hashes (note: COPIES the current hash)
                currUnsignedBlockSiblings[i] = allSiblingHashes[currUnsignedBlockStartingIndex + i]
                        .copyBuilder()
                        .build();
            }

            newMp2.siblings(currUnsignedBlockSiblings).nextPathIndex(FINAL_MERKLE_PATH_INDEX);

            // Populate the map with the full merkle paths for this block number
            fullMerklePathsByBlockNum.put(currUnsignedBlock, new MerklePath[] {mp1, newMp2.build(), mp3});
        }

        return fullMerklePathsByBlockNum;
    }

    /**
     * Verifies the chain of hashes from the earliest block number up to the final signed block number
     * are correct
     */
    private void verifyHashSequence() {
        final var sortedBlockNumsWithRootHashes =
                expectedBlockRootHashes.keySet().stream().sorted().toList();
        assertFalse(sortedBlockNumsWithRootHashes.isEmpty(), "No block root hashes to verify in proof sequence");

        // Verify the earliest block number and the number of computed hashes match the actual
        // proofs stored
        final var actualMinBlockNum = actualIndirectProofs.keySet().stream()
                .min(Long::compareTo)
                .orElseThrow(() -> new IllegalStateException("No actual indirect proofs stored"));
        assertEquals(
                firstUnsignedBlockNum,
                actualMinBlockNum,
                "Mismatch in minimum block number beginning indirect proof sequence: %s expected vs. %s actual"
                        .formatted(firstUnsignedBlockNum, actualMinBlockNum));

        final var expectedNumBlocks = signedBlockNum - firstUnsignedBlockNum + 1;
        assertEquals(
                expectedNumBlocks,
                sortedBlockNumsWithRootHashes.size(),
                "Mismatch in number of actual block proofs and number of expected block proofs in "
                        + "sequence: %s actual vs. %s expected proofs"
                                .formatted(sortedBlockNumsWithRootHashes.size(), expectedNumBlocks));
        final var expectedNumIndirectBlocks = expectedNumBlocks - 1;
        assertEquals(
                expectedNumIndirectBlocks,
                actualIndirectProofs.size(),
                "Mismatch in number of given block root hashes and actual state proofs registered: "
                        + "%s calculated block hashes vs. %s actual state proofs"
                                .formatted(expectedNumIndirectBlocks, actualIndirectProofs.size()));

        // Verify the sequence of hashes by recomputing each block hash from the previous block
        // hash and the indirect proof
        final var finalExpectedHash = expectedBlockRootHashes.get(signedBlockNum);
        for (long blockNum = firstUnsignedBlockNum; blockNum < signedBlockNum; blockNum++) {
            final var expectedPreviousBlockHash = expectedPreviousBlockRootHashes.get(blockNum);

            final var actualBlockStateProof = actualIndirectProofs.get(blockNum);
            assertNotNull(actualBlockStateProof, "Missing indirect state proof for block " + blockNum);

            final var actualBlockHash = blockHashFromStateProof(expectedPreviousBlockHash, actualBlockStateProof);
            assertEquals(
                    finalExpectedHash,
                    actualBlockHash,
                    "Mismatch in indirect proof chain: block %s's hash does not match expected hash %s"
                            .formatted(blockNum, finalExpectedHash));
        }
    }

    private Bytes blockHashFromStateProof(final Bytes previousBlockHash, final StateProof stateProof) {
        final List<MerklePath> paths = stateProof.paths();
        assertFalse(paths.isEmpty(), "Expected non-empty merkle paths in state proof");

        assertEquals(
                EXPECTED_MERKLE_PATH_COUNT,
                paths.size(),
                "Expected %s merkle paths in state proof, but found %s"
                        .formatted(EXPECTED_MERKLE_PATH_COUNT, paths.size()));

        // Verify the first and last paths (timestamp path and final root path), they're easier to check
        final var mp1 = paths.getFirst();
        assertTrue(mp1.hasLeaf(), "Expected leaf in Merkle timestamp path at index %s".formatted(signedBlockTimestamp));
        assertTrue(mp1.siblings().isEmpty(), "Expected no siblings in final Merkle timestamp path");
        assertEquals(
                FINAL_MERKLE_PATH_INDEX, mp1.nextPathIndex(), "Mismatch in next path index of timestamp merkle path");

        final var mp3 = paths.getLast();
        assertFalse(mp3.hasHash(), "Expected no previousBlockHash in parent root path");
        assertFalse(mp3.hasLeaf(), "Expected no leaf in parent root path");

        // Verify merkle path 2 and compute the block contents hash
        final var mp2 = paths.get(BLOCK_CONTENTS_PATH_INDEX);
        assertFalse(mp2.hasLeaf(), "Expected no leaf in block contents Merkle path");
        assertEquals(
                FINAL_MERKLE_PATH_INDEX,
                mp2.nextPathIndex(),
                "Mismatch in next path index of block contents merkle path");

        // Combine all the sibling hashes to compute the block contents hash
        final var allSiblings = mp2.siblings();
        var hash = previousBlockHash;
        for (int i = 0; i < allSiblings.size(); i++) {
            final var sibling = allSiblings.get(i);
            if (sibling.isLeft()) {
                hash = BlockImplUtils.combine(sibling.hash(), hash);
            } else {
                hash = BlockImplUtils.combine(hash, sibling.hash());
            }
        }
        // Combine the timestamp with the computed block contents hash to get the final block hash
        final var timestampBytes = paths.getFirst().leafOrThrow().blockConsensusTimestampOrThrow();
        final var signedTimestampBytes = Timestamp.PROTOBUF.toBytes(signedBlockTimestamp);
        assertEquals(signedTimestampBytes, timestampBytes, "Mismatch in signed block's timestamp bytes");
        final var hashedTsBytes = CommonUtils.noThrowSha384HashOf(signedTimestampBytes);
        hash = BlockImplUtils.combine(hashedTsBytes, hash);

        // This hash must now equal the root hash of the signed block
        return hash;
    }

    private static int expectedSiblingsFrom(final long numUnsignedBlocksRemaining) {
        // For any arbitrary unsigned block, we expect exactly UNSIGNED_BLOCK_SIBLING_COUNT siblings per unsigned block
        // in the sequence
        final var unsignedSiblingCount = (int) (numUnsignedBlocksRemaining * UNSIGNED_BLOCK_SIBLING_COUNT);

        // For the signed block we _do_ include its siblings in the state proof's block contents path, but we _don't_
        // include the timestamp as a sibling hash. The verification algorithm expects the signed block's timestamp to
        // be provided separately.
        return unsignedSiblingCount + +SIGNED_BLOCK_SIBLING_COUNT;
    }

    // A quick note: any field in this record can be null depending on which of the three types of merkle path it
    // represents. For example, the timestamp path will have a leaf but no hash, the block contents path will have a
    // hash but no leaf, and the parent path will have neither.
    private record PartialMerklePath(
            @Nullable MerkleLeaf leaf,
            @Nullable Bytes previousBlockHash,
            @Nullable List<MerkleSiblingHash> siblingHashes) {}
}
