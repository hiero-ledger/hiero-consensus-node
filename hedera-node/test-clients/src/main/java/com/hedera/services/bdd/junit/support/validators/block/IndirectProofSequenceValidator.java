// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashLeaf;
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
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
import com.swirlds.state.binary.SiblingHash;
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
    private final Map<Long, MerkleSiblingHash[]> expectedSiblingsByBlock = new HashMap<>();

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

    /**
     * Runs a test case verifying an indirect proof sequence.
     * <p>
     * <b>FOR DEVELOPMENT PURPOSES ONLY</b>
     *
     * @param ignore not used
     */
    public static void main(String[] ignore) {
        TestCase.run();
    }

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
     * @param expectedBlockHash the block's expected root hash (i.e. the check on the proof value that's calculated)
     * @param expectedPreviousBlockHash the expected previous block's root hash
     * @param expectedBlockTimestamp    this block's expected consensus timestamp
     * @throws IllegalStateException if the end of the sequence has already been reached, or if
     * the blocks are not tracked in sequential order
     */
    void registerProof(
            final long blockNumber,
            final @NonNull BlockProof proof,
            final @NonNull Bytes expectedBlockHash,
            final @NonNull Bytes expectedPreviousBlockHash,
            final @NonNull Timestamp expectedBlockTimestamp,
            final @NonNull MerkleSiblingHash[] expectedSiblings) {
        if (endOfSequenceReached) {
            throw new IllegalStateException(
                    "Cannot track indirect proof for block #%s: end of sequence previously reached"
                            .formatted(blockNumber));
        }
        if (firstUnsignedBlockNum < 0) {
            firstUnsignedBlockNum = blockNumber;
        }

        log.info("Registering proof for block {}", blockNumber);

        // Construct partial merkle paths for this block
        // Block's Merkle Path 1: timestamp path (left child of the SIGNED block's (sub)root)
        final var timestampBytes = Timestamp.PROTOBUF.toBytes(expectedBlockTimestamp);
        final var mp1 = new PartialMerklePath(
                MerklePath.newBuilder().timestampLeaf(timestampBytes).build(), null, null);

        // Block's Merkle Path 2: block contents path
        // Technically we could roll the timestamp into the sibling hashes here, but for clarity we keep them
        // separate
        final var mp2 = new PartialMerklePath(null, expectedPreviousBlockHash, Arrays.asList(expectedSiblings));

        // Block's Merkle Path 3: parent (i.e. combined hash of left child and right child)
        // This is the combined result and should have no data

        this.partialPathsByBlock.put(blockNumber, Pair.of(mp1, mp2));

        if (proof.hasSignedBlockProof()) {
            // Signal the end of the sequence
            endOfSequence(expectedBlockTimestamp, proof);
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

            // We can't verify the indirect proof until we have a signed block proof, so store the
            // indirect proof for later verification
            actualIndirectProofs.put(blockNumber, proof.blockStateProof());
        }

        expectedBlockRootHashes.put(blockNumber, expectedBlockHash);
        expectedPreviousBlockRootHashes.put(blockNumber, expectedPreviousBlockHash);
        expectedSiblingsByBlock.put(blockNumber, expectedSiblings);

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

        log.info("Beginning verification of proof sequence for blocks {} to {}", firstUnsignedBlockNum, signedBlockNum);

        verifyIndirectProofs();
        verifyHashSequence();

        log.info("Successfully verified sequence of proofs for blocks {} to {}", firstUnsignedBlockNum, signedBlockNum);
    }

    /**
     * This method signals the end of the indirect proof sequence, thereby preventing tracking for any further
     * block proofs. Triggers construction of all expected indirect proofs based on the partial proofs already
     * collected.
     *
     * @param signedTimestamp the designated consensus timestamp of the signed block
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
        // This total includes the signed block
        final long actualNumBlocks = actualMaxBlockNum - actualMinBlockNum + 1;

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
                actualNumBlocks * NUM_PARTIAL_PATHS_TO_STORE,
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
        final long expectedNumBlocks = expectedIndirectProofs.size() + 1; // add 1 for signed block
        assertEquals(expectedNumBlocks, actualNumBlocks);
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
     * Given a signed block number, constructs full merkle paths for each registered indirect proof.
     *
     * @return a mapping of block number to its corresponding full merkle paths
     */
    private Map<Long, MerklePath[]> constructExpectedMerklePaths() {
        if (!endOfSequenceReached) {
            throw new IllegalStateException("Cannot construct full merkle paths: signed proof not yet provided");
        }

        final Map<Long, MerklePath[]> fullMerklePathsByBlockNum = new HashMap<>();
        // Construct the full merkle paths for each pending block between [currentPendingBlock.number(),
        // latestSignedBlockNumber]

        // Merkle Path 1: the block timestamp path (should be the same for all proofs in this sequence)
        final var signedTsBytes = Timestamp.PROTOBUF.toBytes(signedBlockTimestamp);
        final var mp1 = MerklePath.newBuilder()
                .timestampLeaf(signedTsBytes)
                .nextPathIndex(FINAL_MERKLE_PATH_INDEX)
                .build();

        // Merkle Path 2: enumerate all sibling hashes for remaining unsigned + signed blocks
        final var earliestBlockStartingHash = expectedPreviousBlockRootHashes.get(firstUnsignedBlockNum);
        MerklePath.Builder earliestBlockMp2 = MerklePath.newBuilder().hash(earliestBlockStartingHash);

        // Create a set of siblings for all _unsigned_ blocks remaining, plus another set for the signed block
        // (excluding its timestamp)
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

            // Combine with the current block's timestamp
            final var unsignedTsBytes = currentBlockPaths.left().leaf().timestampLeafOrThrow();
            final var hashedUnsignedTsBytes = BlockImplUtils.hashLeaf(unsignedTsBytes);
            allSiblingHashes[firstSiblingIndex + blockSiblings.size()] = SiblingNode.newBuilder()
                    .isLeft(true)
                    .hash(hashedUnsignedTsBytes)
                    .build();

            currentBlockNum++;
        }

        // Convert and add the sibling hashes for the signed block (excluding the signed block's timestamp)
        final var currentBlockPaths = partialPathsByBlock.get(signedBlockNum);
        final var blockSiblings = currentBlockPaths.right().siblingHashes().stream()
                .map(s -> new SiblingHash(!s.isFirst(), new Hash(s.siblingHash())))
                .toList();
        // Copy the signed block's siblings into the sibling hashes array
        final var firstSiblingIndex = allSiblingHashes.length - UNSIGNED_BLOCK_SIBLING_COUNT + 1;
        for (int j = 0; j < blockSiblings.size(); j++) {
            final var blockSibling = blockSiblings.get(j);
            allSiblingHashes[firstSiblingIndex + j] = SiblingNode.newBuilder()
                    .isLeft(!blockSibling.isRight())
                    .hash(blockSibling.hash().getBytes())
                    .build();
        }

        // Set the complete collection of siblings for the earliest unsigned block
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

            final var currUnsignedBlockSiblingsSize = expectedSiblingsFrom(signedBlockNum - currUnsignedBlock);
            final var currUnsignedBlockSiblings = new SiblingNode[currUnsignedBlockSiblingsSize];
            final var currUnsignedBlockStartingIndex =
                    (int) ((currUnsignedBlock - firstUnsignedBlockNum) * UNSIGNED_BLOCK_SIBLING_COUNT);
            for (int i = currUnsignedBlockStartingIndex; i < allSiblingHashes.length; i++) {
                // Copy the subset of sibling hashes from the full set of hashes (note: COPIES the current hash)
                currUnsignedBlockSiblings[i - currUnsignedBlockStartingIndex] =
                        allSiblingHashes[i].copyBuilder().build();
            }

            final var currentUnsignedBlockStartingHash = expectedPreviousBlockRootHashes.get(currUnsignedBlock);
            newMp2.hash(currentUnsignedBlockStartingHash)
                    .siblings(currUnsignedBlockSiblings)
                    .nextPathIndex(FINAL_MERKLE_PATH_INDEX);

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
        assertTrue(
                finalExpectedHash.length() > 0,
                "Final expected block hash is empty for signed block " + signedBlockNum);
        for (long blockNum = firstUnsignedBlockNum; blockNum < signedBlockNum; blockNum++) {
            final var expectedPreviousBlockHash = expectedPreviousBlockRootHashes.get(blockNum);
            assertTrue(
                    expectedPreviousBlockHash.length() > 0,
                    "Expected previous block hash is empty for block " + blockNum);

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
        assertTrue(
                mp1.hasTimestampLeaf(),
                "Expected leaf in Merkle timestamp path at index %s".formatted(signedBlockTimestamp));
        assertTrue(mp1.siblings().isEmpty(), "Expected no siblings in final Merkle timestamp path");
        assertEquals(
                FINAL_MERKLE_PATH_INDEX, mp1.nextPathIndex(), "Mismatch in next path index of timestamp merkle path");

        final var mp3 = paths.getLast();
        assertFalse(mp3.hasHash(), "Expected no previousBlockHash in parent root path");
        assertFalse(mp3.hasTimestampLeaf(), "Expected no leaf in parent root path");

        // Verify merkle path 2 and compute the block contents hash
        final var mp2 = paths.get(BLOCK_CONTENTS_PATH_INDEX);
        assertFalse(mp2.hasTimestampLeaf(), "Expected no leaf in block contents Merkle path");
        assertEquals(
                FINAL_MERKLE_PATH_INDEX,
                mp2.nextPathIndex(),
                "Mismatch in next path index of block contents merkle path");

        // Combine all the sibling hashes to compute the block contents hash
        final var allSiblings = mp2.siblings();
        var hash = mp2.hashOrThrow();
        var sibPerBlockCounter = 0;
        for (final SiblingNode sibling : allSiblings) {
            sibPerBlockCounter++;
            if (sibPerBlockCounter == UNSIGNED_BLOCK_SIBLING_COUNT) {
                // Since this node has no siblings (this is expected), hash the current node as a single-node child
                // prior to combining with the current block's timestamp
                hash = BlockImplUtils.hashInternalNodeSingleChild(hash);
            }

            if (sibling.isLeft()) {
                // Final combination to reach the current (intermediate) block's root hash
                hash = BlockImplUtils.hashInternalNode(sibling.hash(), hash);

                // Reset sibling counter
                sibPerBlockCounter = 0;
            } else {
                hash = BlockImplUtils.hashInternalNode(hash, sibling.hash());
            }
        }
        // Perform final single-node child hash
        hash = BlockImplUtils.hashInternalNodeSingleChild(hash);
        // Combine the signed block's timestamp with the computed block contents hash to get the final block hash
        final var signedTimestamp = paths.getFirst().timestampLeafOrThrow();
        final var signedTimestampBytes = Timestamp.PROTOBUF.toBytes(signedBlockTimestamp);
        assertEquals(signedTimestampBytes, signedTimestamp, "Mismatch in signed block's timestamp bytes");
        final var hashedTsBytes = BlockImplUtils.hashLeaf(signedTimestampBytes);
        hash = BlockImplUtils.hashInternalNode(hashedTsBytes, hash);

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
        return unsignedSiblingCount + SIGNED_BLOCK_SIBLING_COUNT;
    }

    // A quick note: any field in this record can be null depending on which of the three types of merkle path it
    // represents. For example, the timestamp path will have a leaf but no hash, the block contents path will have a
    // hash but no leaf, and the parent path will have neither.
    private record PartialMerklePath(
            @Nullable MerklePath leaf,
            @Nullable Bytes previousBlockHash,
            @Nullable List<MerkleSiblingHash> siblingHashes) {}

    private static class TestCase {
        private static final Bytes INITIAL_PREV_BLOCK_HASH = Bytes.fromHex(
                "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");
        private static final Bytes BLOCK_8_HASH = Bytes.fromHex(
                "b76b8abdc86af21d5098f3680790b5951b3244a7fc2a407c5d0e314c3b1b90a4d371185c6bcc153ee5e104f7fd1a4b97");
        private static final Bytes BLOCK_9_HASH = Bytes.fromHex(
                "481962490ece4be2da9a467a43d5efd4871c10ddd78a4e40cdc357573107d05a3e23bdce2cfbbd75846a1b1dcbc86a69");
        private static final Bytes BLOCK_10_HASH = Bytes.fromHex(
                "98c6572c628b7441b1108e5027519e207771bf445b5ddf63657170e7b7d0148ff74f3c560597b5a27a6e564218c45438");

        private static final Bytes BLOCK_10_SIGNATURE = Bytes.fromBase64(
                "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");

        /**
         * Runs the indirect proof sequence test case
         */
        static void run() {
            final var validator = new IndirectProofSequenceValidator();

            validator.registerProof(
                    8L, BLOCK_8.proof, BLOCK_8_HASH, INITIAL_PREV_BLOCK_HASH, BLOCK_8.timestamp, BLOCK_8.siblings);

            validator.registerProof(9L, BLOCK_9.proof, BLOCK_9_HASH, BLOCK_8_HASH, BLOCK_9.timestamp, BLOCK_9.siblings);

            validator.registerProof(
                    10L, BLOCK_10.proof, BLOCK_10_HASH, BLOCK_9_HASH, BLOCK_10.timestamp, BLOCK_10.siblings);

            validator.verify();
        }

        // --- Inputs for test case ---
        // Block 8 Starting hashes:
        // previous block hash:
        // 000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
        // prev blocks hash:
        // 111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111
        // start of state hash:
        // 222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222
        // consensus headers hash:
        // 333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333
        // input items hash:
        // 444444444444444444444444444444444444444444444444444444444444444444444444444444444444444444444444
        // output items hash:
        // 555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555
        // state changes hash:
        // 666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666
        // trace items hash:
        // AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
        // Block 9 Starting hashes:
        // previous block hash (block 8's result):
        // b76b8abdc86af21d5098f3680790b5951b3244a7fc2a407c5d0e314c3b1b90a4d371185c6bcc153ee5e104f7fd1a4b97
        // prev blocks hash:
        // 101010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
        // start of state hash:
        // BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB
        // consensus headers hash:
        // CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC
        // input items hash:
        // DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD
        // output items hash:
        // EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
        // state changes hash:
        // 100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
        // trace items hash:
        // 200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
        // Block 10 Starting hashes:
        // previous block hash (from block 9):
        // 481962490ece4be2da9a467a43d5efd4871c10ddd78a4e40cdc357573107d05a3e23bdce2cfbbd75846a1b1dcbc86a69
        // prev blocks hash:
        // 202020000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
        // start of state hash:
        // 300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
        // consensus headers hash:
        // 400000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
        // input items hash:
        // 500000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
        // output items hash:
        // 600000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
        // state changes hash:
        // A00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
        // trace items hash:
        // B00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000

        private record TestBlock(BlockProof proof, Timestamp timestamp, MerkleSiblingHash[] siblings) {
            Bytes timestampBytes() {
                return Timestamp.PROTOBUF.toBytes(timestamp);
            }
        }

        private static final TestBlock BLOCK_10;

        static {
            final var signedProof10 = TssSignedBlockProof.newBuilder()
                    .blockSignature(BLOCK_10_SIGNATURE)
                    .build();
            final var blockProof10 = BlockProof.newBuilder()
                    .block(10)
                    .signedBlockProof(signedProof10)
                    .build();
            BLOCK_10 = new TestBlock(
                    blockProof10,
                    Timestamp.newBuilder().seconds(1764666650).nanos(3000000).build(),
                    new MerkleSiblingHash[] {
                        // given subroot 2
                        MerkleSiblingHash.newBuilder()
                                .isFirst(false)
                                .siblingHash(
                                        Bytes.fromHex(
                                                "202020000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"))
                                .build(),
                        // start of state hash:
                        // 300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
                        // consensus headers hash:
                        // 400000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
                        MerkleSiblingHash.newBuilder()
                                .isFirst(false)
                                .siblingHash(
                                        Bytes.fromHex(
                                                "6f10274a6c0789c585affe53562124db4c30d5ce0f801fb53e82aa362408264c307bce42cf82d20f9f727094fa5e55d2"))
                                .build(),
                        // input items hash:
                        // 500000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
                        // output items hash:
                        // 600000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
                        // state changes hash:
                        // A00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
                        // trace items hash:
                        // B00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
                        MerkleSiblingHash.newBuilder()
                                .isFirst(false)
                                .siblingHash(
                                        Bytes.fromHex(
                                                "f9a4263aaefecd1f0c7afdf451bfd2d733925a5ad7b6a022c3285bba9817b5b5f7a63f571faecffae9483225460eec35"))
                                .build()
                    });
        }

        private static final TestBlock BLOCK_9;

        static {
            final var ts9 =
                    Timestamp.newBuilder().seconds(1764666647).nanos(830783000).build();
            final var ts9Bytes = Timestamp.PROTOBUF.toBytes(ts9);
            final var hashedTs9Bytes = hashLeaf(ts9Bytes);
            final var b9Siblings = new MerkleSiblingHash[] {
                // given subroot 2
                MerkleSiblingHash.newBuilder()
                        .isFirst(false)
                        .siblingHash(
                                Bytes.fromHex(
                                        "101010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"))
                        .build(),
                // start of state hash:
                // BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB
                // consensus headers hash:
                // CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC
                MerkleSiblingHash.newBuilder()
                        .isFirst(false)
                        .siblingHash(
                                Bytes.fromHex(
                                        "89fc9381888427a01c46d1f3175c774425cf51124db1b702864b2e8f6e0b2e60970bee612fab1d4ad9d236d39c09d624"))
                        .build(),
                // input items hash:
                // DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD
                // output items hash:
                // EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
                // state changes hash:
                // 100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
                // trace items hash:
                // 200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
                MerkleSiblingHash.newBuilder()
                        .isFirst(false)
                        .siblingHash(
                                Bytes.fromHex(
                                        "4596c82a149dd85f1b15ed1d31dc27695326c372b7a81808192bd14cd98a57bd77c8510b6b3bd47d7bfa4d79b197a2d4"))
                        .build()
            };
            final var proof9 = BlockProof.newBuilder()
                    .block(9)
                    .blockStateProof(StateProof.newBuilder()
                            .paths(List.of(
                                    MerklePath.newBuilder()
                                            .nextPathIndex(2)
                                            .timestampLeaf(BLOCK_10.timestampBytes())
                                            .build(),
                                    MerklePath.newBuilder()
                                            .hash(BLOCK_8_HASH)
                                            .siblings(List.of(
                                                    // m1L6 sibling (signed block minus 1, Level 6)
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(b9Siblings[0].siblingHash())
                                                            .build(),
                                                    // m1L5 sibling
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(b9Siblings[1].siblingHash())
                                                            .build(),
                                                    // m1L4 sibling
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(b9Siblings[2].siblingHash())
                                                            .build(),
                                                    // m1L2 sibling (timestamp)
                                                    SiblingNode.newBuilder()
                                                            .isLeft(true)
                                                            .hash(hashedTs9Bytes)
                                                            .build(),
                                                    // sL6 sibling (signed block, Level 6)
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(BLOCK_10.siblings[0].siblingHash())
                                                            .build(),
                                                    // sL5 sibling
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(BLOCK_10.siblings[1].siblingHash())
                                                            .build(),
                                                    // sL4 sibling
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(BLOCK_10.siblings[2].siblingHash())
                                                            .build()))
                                            .nextPathIndex(2)
                                            .build(),
                                    MerklePath.newBuilder().nextPathIndex(-1).build()))
                            .signedBlockProof(BLOCK_10.proof.signedBlockProof()))
                    .build();

            BLOCK_9 = new TestBlock(proof9, ts9, b9Siblings);
        }

        private static final TestBlock BLOCK_8;

        static {
            final var ts8 =
                    Timestamp.newBuilder().seconds(1764666645).nanos(540871000).build();
            final var ts8Bytes = Timestamp.PROTOBUF.toBytes(ts8);
            final var hashedTs8Bytes = hashLeaf(ts8Bytes);
            final var b8Siblings =
                    new MerkleSiblingHash[] {
                        // given subroot 2
                        MerkleSiblingHash.newBuilder()
                                .isFirst(false)
                                .siblingHash(
                                        Bytes.fromHex(
                                                "111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111"))
                                .build(),
                        // start of state hash:
                        // 222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222
                        // consensus headers hash:
                        // 333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333				//
                        MerkleSiblingHash.newBuilder()
                                .isFirst(false)
                                .siblingHash(
                                        Bytes.fromHex(
                                                "aeacfb6b41ddb2ef373fa3835d0fc9cbff06ffd730bade969797aa3ca7549e065f9b44fe7ce96987ec3e8f17aea0b5c7"))
                                .build(),
                        // input items hash:
                        // 444444444444444444444444444444444444444444444444444444444444444444444444444444444444444444444444
                        // output items hash:
                        // 555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555
                        // state changes hash:
                        // 666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666
                        // trace items hash:
                        // AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
                        MerkleSiblingHash.newBuilder()
                                .isFirst(false)
                                .siblingHash(
                                        Bytes.fromHex(
                                                "2d1149d3e744ac28a809b89959869d7432f29f5998d1000abe92a28501a8369f9af2d9cc8ae7ae7f308d3258ccac955c"))
                                .build()
                    };
            final var hashedTs9Bytes = hashLeaf(BLOCK_9.timestampBytes());
            final var proof8 = BlockProof.newBuilder()
                    .block(8)
                    .blockStateProof(StateProof.newBuilder()
                            .paths(List.of(
                                    MerklePath.newBuilder()
                                            .nextPathIndex(2)
                                            .timestampLeaf(BLOCK_10.timestampBytes())
                                            .build(),
                                    MerklePath.newBuilder()
                                            .hash(INITIAL_PREV_BLOCK_HASH)
                                            .siblings(List.of(
                                                    // m2L6 sibling (signed block minus 2, Level 6)
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(b8Siblings[0].siblingHash())
                                                            .build(),
                                                    // m2L5 sibling
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(b8Siblings[1].siblingHash())
                                                            .build(),
                                                    // m2L4 sibling
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(b8Siblings[2].siblingHash())
                                                            .build(),
                                                    // m2L2 sibling (timestamp)
                                                    SiblingNode.newBuilder()
                                                            .isLeft(true)
                                                            .hash(hashedTs8Bytes)
                                                            .build(),
                                                    // m1L6 sibling (signed block minus 1, Level 6)
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(BLOCK_9.siblings[0].siblingHash())
                                                            .build(),
                                                    // m1L5 sibling
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(BLOCK_9.siblings[1].siblingHash())
                                                            .build(),
                                                    // m1L4 sibling
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(BLOCK_9.siblings[2].siblingHash())
                                                            .build(),
                                                    // m1L2 sibling (timestamp)
                                                    SiblingNode.newBuilder()
                                                            .isLeft(true)
                                                            .hash(hashedTs9Bytes)
                                                            .build(),
                                                    // sL6 sibling (signed block, Level 6)
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(BLOCK_10.siblings[0].siblingHash())
                                                            .build(),
                                                    // sL5 sibling
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(BLOCK_10.siblings[1].siblingHash())
                                                            .build(),
                                                    // sL4 sibling
                                                    SiblingNode.newBuilder()
                                                            .isLeft(false)
                                                            .hash(BLOCK_10.siblings[2].siblingHash())
                                                            .build()))
                                            .nextPathIndex(2)
                                            .build(),
                                    MerklePath.newBuilder().nextPathIndex(-1).build()))
                            .signedBlockProof(BLOCK_10.proof.signedBlockProof()))
                    .build();

            BLOCK_8 = new TestBlock(proof8, ts8, b8Siblings);
        }
    }
}
