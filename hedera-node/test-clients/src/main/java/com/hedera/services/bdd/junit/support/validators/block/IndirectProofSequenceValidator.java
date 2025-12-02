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
     * Tracks an indirect state proof for a given block number.
     * <p>
     * This method must be called in sequential order for each block, up to but not including the
     * block. Once a signed block is encountered, call {@link #endOfSequence(Timestamp, BlockProof)} to
     * finalize the sequence. Once the end of the sequence is signaled, no further indirect proofs
     * can be registered.
     *
     * @param blockNumber       the block number for which the indirect proof is being tracked
     * @param proof             the block's (partial) indirect block proof
     * @param previousBlockHash the previous block's root hash
     * @param blockTimestamp    this block's consensus timestamp
     * @param siblingHashes     the sibling hashes for this block's indirect proof. Note that the block's
     *                          timestamp <b>must not</b> be included as a sibling
     * @throws IllegalStateException if the end of the sequence has already been reached, or if
     * the blocks are not tracked in sequential order
     */
    void registerStateProof(
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

        log.info("Registering unsigned block {}", blockNumber);

        // Construct partial merkle paths for the expected indirect proof
        // Block's Merkle Path 1: timestamp path (left child of the block's (sub)root)
        final var timestampBytes = Timestamp.PROTOBUF.toBytes(blockTimestamp);
        final var mp1 = new PartialMerklePath(
                MerkleLeaf.newBuilder().blockConsensusTimestamp(timestampBytes).build(), null, null);

        // Block's Merkle Path 2: block contents path
        // Technically we could roll the timestamp into the sibling hashes here, but for clarity we keep them separate
        final var mp2 = new PartialMerklePath(null, previousBlockHash, siblingHashes);

        // Block's Merkle Path 3: parent (i.e. combined hash of left child and right child)
        // This is the combined result and should have no data

        this.partialPathsByBlock.put(blockNumber, Pair.of(mp1, mp2));

        // We can't verify the indirect proof until we have a signed block proof, so store the
        // indirect proof for later verification
        actualIndirectProofs.put(blockNumber, proof.blockStateProof());
        expectedBlockRootHashes.put(blockNumber, blockRootHash);
        log.info("Registered unsigned block {}", blockNumber);
    }

    /**
     * Verifies the contents of the (built) indirect state proofs.
     */
    void verify() {
        if (!endOfSequenceReached) {
            throw new IllegalStateException("Cannot verify indirect proof sequence: end of sequence not reached");
        }

        log.info(
                "Beginning verification of indirect state proofs for blocks {} to {}",
                firstUnsignedBlockNum,
                signedBlockNum);

        verifyIndirectProofs();
        verifyHashSequence();
    }

    /**
     * This method signals the end of the indirect proof sequence, thereby preventing tracking for any further indirect proofs. Triggers construction of all expected indirect proofs based on the partial proofs already collected.
     *
     * @param signedProof the TSS-signed block proof that ends the indirect proof sequence
     */
    void endOfSequence(@NonNull final Timestamp signedTimestamp, @NonNull final BlockProof signedProof) {
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
                "Verifying expected indirect state proofs for blocks {} to {} (signed block {})",
                firstUnsignedBlockNum,
                signedBlockNum - 1,
                signedBlockNum);
        final var combinedPathsSize = partialPathsByBlock.keySet().stream()
                .sorted()
                .map(partialPathsByBlock::get)
                .mapToInt(ignore -> NUM_PARTIAL_PATHS_TO_STORE)
                .sum();
        assertEquals(
                actualNumIndirectBlocks * NUM_PARTIAL_PATHS_TO_STORE,
                combinedPathsSize,
                "Each unsigned block should have 2 partial merkle paths. Registered unsigned blocks: "
                        + partialPathsByBlock);
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
     * Verify all indirect block proofs up to the latest signed block.
     */
    private void verifyIndirectProofs() {
        if (!containsIndirectProofs()) {
            throw new IllegalStateException("No indirect proofs to verify in indirect proof sequence");
        }
        if (!endOfSequenceReached || signedProof == null) {
            throw new IllegalStateException("Cannot verify indirect proofs: signed proof not provided");
        }

        // Verify the number of paths and required total paths size in each reconstructed indirect proof
        for (long i = firstUnsignedBlockNum; i < signedBlockNum; i++) {
            final var numPathsForBlock = expectedIndirectProofs.get(i).paths().size();
            assertEquals(
                    EXPECTED_MERKLE_PATH_COUNT,
                    numPathsForBlock,
                    ("Mismatch in number of reconstructed indirect proof paths: %s expected vs. %s actual")
                            .formatted(EXPECTED_MERKLE_PATH_COUNT, numPathsForBlock));

            final var numRemainingBlocks = signedBlockNum - i;
            final var totalExpectedSiblings =
                    (UNSIGNED_BLOCK_SIBLING_COUNT * (numRemainingBlocks - 1)) + SIGNED_BLOCK_SIBLING_COUNT;
            final var totalActualSiblings = expectedIndirectProofs
                    .get(i)
                    .paths()
                    .get(BLOCK_CONTENTS_PATH_INDEX)
                    .siblings()
                    .size();
            assertEquals(
                    totalExpectedSiblings,
                    totalActualSiblings,
                    "Mismatch in number of reconstructed indirect proof siblings for block %s: %s expected vs. %s actual"
                            .formatted(i, totalExpectedSiblings, totalActualSiblings));
        }

        // Compare each reconstructed state proof with the actual state proof for each unsigned block
        for (long i = firstUnsignedBlockNum; i < signedBlockNum; i++) {
            log.info("Verifying indirect proof for block {}", i);
            assertEquals(expectedIndirectProofs.get(i), actualIndirectProofs.get(i));
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
        // For each indirect block, construct the full merkle paths from the partial merkle paths

        // Construct all merkle paths for each pending block between [currentPendingBlock.number(),
        // latestSignedBlockNumber - 1]

        // Merkle Path 1: the block timestamp path (same for all proofs)
        final var tsBytes = Timestamp.PROTOBUF.toBytes(signedBlockTimestamp);
        final var tsLeaf =
                MerkleLeaf.newBuilder().blockConsensusTimestamp(tsBytes).build();
        final var mp1 = MerklePath.newBuilder()
                .leaf(tsLeaf)
                .nextPathIndex(FINAL_NEXT_PATH_INDEX)
                .build();

        // Merkle Path 2: enumerate all sibling hashes for remaining UNSIGNED blocks
        MerklePath.Builder thisBlocksMp2 = MerklePath.newBuilder();

        // Create a set of siblings for all unsigned blocks remaining, plus another set for the signed block
        final var numBlocksRemaining = signedBlockNum - firstUnsignedBlockNum;
        final var numUnsignedBlocksRemaining = numBlocksRemaining - 1;
        final var totalSiblings =
                (int) ((numBlocksRemaining - 1) * UNSIGNED_BLOCK_SIBLING_COUNT) + SIGNED_BLOCK_SIBLING_COUNT;
        final SiblingNode[] allSiblingHashes = new SiblingNode[totalSiblings];
        var currentBlockNum = firstUnsignedBlockNum;
        for (int i = 0; i < numUnsignedBlocksRemaining; i++) {
            final var currentBlockPaths = partialPathsByBlock.get(currentBlockNum);

            // Convert sibling hashes
            final var blockSiblings = currentBlockPaths.right().siblingHashes.stream()
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
                ;
            }

            // Convert this pending block's timestamp into a sibling hash
            final var pbTsBytes = currentBlockPaths.left().leaf().blockConsensusTimestampOrThrow();
            // Add to the sibling hashes array
            final var pendingBlockTimestampSiblingIndex = firstSiblingIndex + UNSIGNED_BLOCK_SIBLING_COUNT - 1;
            // Timestamp is always a left sibling
            allSiblingHashes[pendingBlockTimestampSiblingIndex] =
                    SiblingNode.newBuilder().isLeft(true).hash(pbTsBytes).build();

            currentBlockNum++;
        }

        // Merkle Path 2 Continued: add sibling hashes for the signed block
        // Note: the timestamp for this (signed) block was provided in Merkle Path 1 above
        final var signedBlockPartialPaths = partialPathsByBlock.get(signedBlockNum);
        final var signedBlockSiblings = signedBlockPartialPaths.right().siblingHashes();
        final var signedBlockFirstSiblingIndex = (int) (numBlocksRemaining * UNSIGNED_BLOCK_SIBLING_COUNT);
        for (int i = 0; i < signedBlockSiblings.size(); i++) {
            final var blockSibling = signedBlockSiblings.get(i);
            allSiblingHashes[signedBlockFirstSiblingIndex + i] = SiblingNode.newBuilder()
                    .isLeft(blockSibling.isFirst())
                    .hash(blockSibling.siblingHash())
                    .build();
        }
        thisBlocksMp2.siblings(Arrays.stream(allSiblingHashes).toList());

        // Merkle Path 3: the root path (same for all proofs), and has no data specific to any block
        final var mp3 =
                MerklePath.newBuilder().nextPathIndex(FINAL_NEXT_PATH_INDEX).build();

        // We now have all sibling hashes needed for the earliest unsigned block's proof. Set the value in the map
        fullMerklePathsByBlockNum.put(firstUnsignedBlockNum, new MerklePath[] {mp1, thisBlocksMp2.build(), mp3});

        // Populate each remaining unsigned block's proof with the subset of needed sibling hashes
        for (long bn = firstUnsignedBlockNum + 1; bn < signedBlockNum; bn++) {
            final var newMp2 = MerklePath.newBuilder();

            final var currUnsignedBlockSiblingsSize =
                    (int) ((signedBlockNum - bn - 1) * UNSIGNED_BLOCK_SIBLING_COUNT) + SIGNED_BLOCK_SIBLING_COUNT;
            final var currUnsignedBlockSiblings = new SiblingNode[currUnsignedBlockSiblingsSize];
            final var currUnsignedBlockStartingIndex =
                    (int) ((bn - firstUnsignedBlockNum) * UNSIGNED_BLOCK_SIBLING_COUNT);
            for (int i = currUnsignedBlockStartingIndex; i < allSiblingHashes.length; i++) {
                // Copy the subset of sibling hashes from the full set of hashes (note: COPIES the current hash)
                currUnsignedBlockSiblings[i] = allSiblingHashes[currUnsignedBlockStartingIndex + i]
                        .copyBuilder()
                        .build();
            }

            newMp2.siblings(currUnsignedBlockSiblings).nextPathIndex(FINAL_MERKLE_PATH_INDEX);

            // Populate the map with the full merkle paths for this unsigned block number
            fullMerklePathsByBlockNum.put(bn, new MerklePath[] {mp1, newMp2.build(), mp3});
        }

        return fullMerklePathsByBlockNum;
    }

    /**
     * Verifies the chain of hashes from the earliest block number up to the signed block number
     * are correct
     */
    private void verifyHashSequence() {
        final var sortedBlockNums =
                expectedBlockRootHashes.keySet().stream().sorted().toList();
        assertFalse(sortedBlockNums.isEmpty(), "No block root hashes to verify in indirect proof sequence");

        // Verify the earliest block number and the number of computed hashes match the actual
        // proofs stored
        final var actualMinBlockNum = actualIndirectProofs.keySet().stream()
                .min(Long::compareTo)
                .orElseThrow(() -> new IllegalStateException("No actual indirect proofs stored"));
        assertEquals(
                firstUnsignedBlockNum,
                actualMinBlockNum,
                ("Mismatch in minimum block number beginning indirect proof sequence: %s expected " + "vs. %s actual")
                        .formatted(firstUnsignedBlockNum, actualMinBlockNum));

        final var expectedNumUnsignedBlocks = signedBlockNum - firstUnsignedBlockNum;
        assertEquals(
                expectedNumUnsignedBlocks,
                sortedBlockNums.size(),
                ("Mismatch in number of block root hashes and the expected indirect proofs in "
                                + "sequence: %s hashes vs. %s expected proofs")
                        .formatted(sortedBlockNums.size(), expectedNumUnsignedBlocks));
        assertEquals(
                sortedBlockNums.size(),
                actualIndirectProofs.size(),
                ("Mismatch in number of given block root hashes and actual indirect proofs stored: "
                                + "%s hashes vs. %s proofs")
                        .formatted(sortedBlockNums.size(), actualIndirectProofs.size()));

        // Verify the sequence of hashes by recomputing each block hash from the previous block
        // hash and the indirect proof
        for (long blockNum = firstUnsignedBlockNum; blockNum < signedBlockNum; blockNum++) {
            final var expectedPreviousBlockHash = expectedBlockRootHashes.get(blockNum);
            final var expectedBlockHash = expectedBlockRootHashes.get(blockNum);
            assertNotNull(expectedBlockHash, "Missing expected block hash for block " + blockNum);

            final var actualBlockStateProof = actualIndirectProofs.get(blockNum);
            assertNotNull(actualBlockStateProof, "Missing indirect state proof for block " + blockNum);

            final int numIndirectBlocksLeftInSequence = (int) (signedBlockNum - blockNum);
            final var actualBlockHash = blockHashFromProof(
                    expectedPreviousBlockHash, actualBlockStateProof, numIndirectBlocksLeftInSequence);
            assertEquals(
                    expectedBlockHash,
                    actualBlockHash,
                    ("Mismatch in indirect proof chain: block #%s hash does not match expected hash" + " %s")
                            .formatted(blockNum, expectedBlockHash));
        }

        log.info(
                "Successfully verified sequence of indirect state proofs for blocks {} to {}",
                firstUnsignedBlockNum,
                signedBlockNum - 1);
    }

    private Bytes blockHashFromProof(
            final Bytes previousBlockHash, final StateProof stateProof, final int numIndirectBlocksTillSigned) {
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
                FINAL_NEXT_PATH_INDEX, mp1.nextPathIndex(), "Mismatch in next path index of timestamp merkle path");

        final var mp3 = paths.getLast();
        assertFalse(mp3.hasHash(), "Expected no hash in parent root path");
        assertFalse(mp3.hasLeaf(), "Expected no leaf in parent root path");

        // Verify merkle path 2 and compute the block contents hash
        final var mp2 = paths.get(BLOCK_CONTENTS_PATH_INDEX);
        assertFalse(mp2.hasLeaf(), "Expected no leaf in block contents Merkle path");
        assertEquals(
                FINAL_NEXT_PATH_INDEX,
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
        final var timestampBytes = paths.get(0).leafOrThrow().blockConsensusTimestampOrThrow();
        final var signedTimestampBytes = Timestamp.PROTOBUF.toBytes(signedBlockTimestamp);
        assertEquals(signedTimestampBytes, timestampBytes, "Mismatch in signed block timestamp bytes");
        hash = BlockImplUtils.combine(signedTimestampBytes, hash);

        // This hash must now equal the root hash of the given block
        return hash;
    }

    // A quick note: any field in this record can be null depending on which of the three types of merkle path it
    // represents. For example, the timestamp path will have a leaf but no hash, the block contents path will have a
    // hash but no leaf, and the parent path will have neither.
    private record PartialMerklePath(
            @Nullable MerkleLeaf leaf, @Nullable Bytes hash, @Nullable List<MerkleSiblingHash> siblingHashes) {}
}
