// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

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
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class IndirectProofSequenceValidator {
    private static final Logger log = LogManager.getLogger(IndirectProofSequenceValidator.class);

    private final Map<Long, StateProof> expectedIndirectProofs = new HashMap<>();
    private final Map<Long, StateProof> actualIndirectProofs = new HashMap<>();

    private final Map<Long, Bytes> expectedBlockRootHashes = new HashMap<>();

    /**
     * The signed block proof corresponding to the latest signed block number. This is needed to
     * verify the indirect proofs and must be the last proof in the sequence.
     */
    private BlockProof signedProof;

    /**
     * This field represents all the unfinished blocks (potentially including the signed block
     * following a sequence of unsigned/indirect blocks)
     */
    private final Map<Long, List<PartialMerklePath>> partialPathsByBlock = new HashMap<>();

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
     * signed
     * block. Once a signed block is encountered, call {@link #endOfSequence(BlockProof)} to
     * finalize the sequence. Once the end of the sequence is signaled, no further indirect proofs
     * can be registered.
     *
     * @param blockNumber       the block number for which the indirect proof is being tracked
     * @param proof             the block's (partial) indirect block proof
     * @param previousBlockHash the previous block's root hash
     * @param blockTimestamp    this block's consensus timestamp
     * @param siblingHashes     the sibling hashes for this block's indirect proof
     * @throws IllegalStateException if the end of the sequence has already been reached, or if
     * the blocks
     *                               are not tracked in sequential order
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
        // Block's Merkle Path 1: timestamp path (left child of (sub)root only)
        final var timestampBytes = Timestamp.PROTOBUF.toBytes(blockTimestamp);
        final var mp1 = new PartialMerklePath(
                MerkleLeaf.newBuilder().blockConsensusTimestamp(timestampBytes).build(), null, null);

        // Block's Merkle Path 2: block contents path
        final var mp2 = new PartialMerklePath(null, previousBlockHash, siblingHashes);

        // Block's Merkle Path 3: parent (i.e. combined hash of left child and right child)
        final var mp3 =
                new PartialMerklePath(null, null, List.of()); // This is the combined result and should have no siblings
        this.partialPathsByBlock.put(blockNumber, List.of(mp1, mp2, mp3));

        // We can't verify the indirect proof until we have a signed block proof, so store the
        // indirect proof for
        // later verification
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
     * This method signals the end of the indirect proof sequence, thereby preventing tracking for
     * any
     * further indirect proofs. Triggers construction of all expected indirect proofs based on the
     * partial proofs already collected.
     *
     * @param signedProof the TSS-signed block proof that ends the indirect proof sequence
     */
    void endOfSequence(@NonNull final BlockProof signedProof) {
        this.endOfSequenceReached = true;
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
                .mapToInt(List::size)
                .sum();
        assertEquals(
                actualNumIndirectBlocks * 3,
                combinedPathsSize,
                "Each unsigned block should have 3 merkle paths. Registered unsigned blocks: " + partialPathsByBlock);
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
        // construct the full
        // expected state proof for each unsigned block, beginning with the earliest
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

        // Verify the number of reconstructed indirect proofs (3 paths per indirect block)
        for (long i = firstUnsignedBlockNum; i < signedBlockNum; i++) {
            final var numRemainingBlocks = signedBlockNum - i;
            final var numExpectedPaths = numRemainingBlocks * 3;
            final var numPathsForBlock = expectedIndirectProofs.get(i).paths().size();
            assertEquals(
                    numExpectedPaths,
                    numPathsForBlock,
                    ("Mismatch in number of reconstructed indirect proof paths: %s expected vs. %s actual")
                            .formatted(numExpectedPaths, numPathsForBlock));
        }

        // Compare each reconstructed state proof with the actual state proof for each indirect block
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
        long currentBlockNum = firstUnsignedBlockNum;
        // For each indirect block, construct the full merkle paths from the partial merkle paths. Don't modify the
        // partial paths during any of the below iterations as they will need to be used in each iteration to generate
        // new merkle paths.
        while (currentBlockNum < signedBlockNum) {
            final var timestampDivider = (int) (signedBlockNum - currentBlockNum);
            final var numExpectedTotalPaths = (int) (signedBlockNum - currentBlockNum) * 3;
            final MerklePath[] combinedBlockPaths = new MerklePath[numExpectedTotalPaths];

            int numBlocksRemaining = (int) (signedBlockNum - currentBlockNum);
            int maxIndex = (numBlocksRemaining * 3) - 1;
            var currentMp1ParentPath = maxIndex;
            var tsCounter = 0;
            // Construct and place all MP1's (timestamp paths) in the paths array
            for (int i = timestampDivider - 1; i >= 0; i--) {
                final var currentPmp =
                        partialPathsByBlock.get(currentBlockNum + i).getFirst();
                final var mp1 = MerklePath.newBuilder()
                        .leaf(currentPmp.leaf())
                        .nextPathIndex(currentMp1ParentPath)
                        .build();
                combinedBlockPaths[tsCounter] = mp1;

                currentMp1ParentPath -= 2;
                tsCounter++;
            }

            // Now construct and place all MP2's (block contents paths) and MP3's (parent paths)
            // in the paths array
            var mp2PmpCounter = currentBlockNum;
            final var upperIndex = timestampDivider + (2 * numBlocksRemaining);
            for (int mp2Index = timestampDivider; mp2Index < upperIndex; mp2Index += 2) {
                final var currentMp2Pmp = partialPathsByBlock.get(mp2PmpCounter).get(1);
                final var mp2 = MerklePath.newBuilder()
                        .hash(currentMp2Pmp.hash())
                        .siblings(currentMp2Pmp.siblingHashes().stream()
                                .map(s -> SiblingNode.newBuilder()
                                        .isLeft(false)
                                        .hash(s.siblingHash())
                                        .build())
                                .toList())
                        .nextPathIndex(mp2Index + 1)
                        .build();
                assertEquals(4, mp2.siblings().size(), "Expected 4 siblings in constructed MP2");
                combinedBlockPaths[mp2Index] = mp2;

                final var mp3Path = ((mp2Index + 1) == maxIndex) ? -1 : mp2Index + 2;
                final var mp3 = MerklePath.newBuilder().nextPathIndex(mp3Path).build();
                combinedBlockPaths[mp2Index + 1] = mp3;

                mp2PmpCounter++;
            }

            fullMerklePathsByBlockNum.put(currentBlockNum, combinedBlockPaths);
            currentBlockNum++;
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

        // Calculate the number of expected paths based on the number of blocks remaining in the
        // indirect proof sequence
        final var expectedNumPaths = numIndirectBlocksTillSigned * 3;
        assertEquals(
                expectedNumPaths,
                paths.size(),
                "Expected %s merkle paths in state proof, but found %s".formatted(expectedNumPaths, paths.size()));

        final int timestampDivider = (int) signedBlockNum - numIndirectBlocksTillSigned;

        // Combine the block's merkle paths to produce the block hash
        var currentHash = previousBlockHash;
        // This counter will count backwards from the timestamp divider
        var timestampPathCounter = 0;
        for (int mp2Index = timestampDivider; mp2Index < paths.size(); mp2Index += 2) {
            // Retrieve and verify the timestamp merkle path
            final var currentTimestampIndex = mp2Index - timestampPathCounter;
            final var mp1 = paths.get(currentTimestampIndex);
            assertTrue(
                    mp1.hasLeaf(),
                    "Expected leaf in Merkle timestamp path at index %s".formatted(currentTimestampIndex));
            assertTrue(mp1.siblings().isEmpty(), "Expected no siblings in final Merkle timestamp path");

            // Retrieve and verify the block contents merkle path
            final var mp2 = paths.get(mp2Index);
            assertEquals(
                    mp1.nextPathIndex(),
                    mp2.nextPathIndex(),
                    "Mismatch in next path index between timestamp and block contents paths");
            assertEquals(4, mp2.siblings().size(), "Expected 4 siblings in block contents path");
            // Compute the block contents hash from merkle path 2
            for (final var sibling : mp2.siblings()) {
                // All siblings must be right children
                currentHash = BlockImplUtils.combine(currentHash, sibling.hash());
            }

            // Retrieve and verify the parent merkle path
            final var mp3 = paths.get(mp2Index + 1);
            assertFalse(
                    mp3.hasHash(),
                    "Expected no hash in parent Merkle (sub)root path (index = %s)".formatted(mp1.nextPathIndex()));
            assertFalse(
                    mp3.hasLeaf(),
                    "Expected no leaf in parent Merkle (sub)root path (index = %s)".formatted(mp1.nextPathIndex()));
            // Due to the ordering of the state proof, the next path index of the current parent
            // should be either
            // two greater than the current mp2 index, or -1 if this path is the block's root path
            final var expectedMp3NextPathIndex = (mp2Index == paths.size() - 1) ? -1 : mp2Index + 2;
            assertEquals(expectedMp3NextPathIndex, mp3.nextPathIndex());

            // Retrieve the bytes of the consensus timestamp
            final var timestampBytes = mp1.leafOrThrow().blockConsensusTimestampOrThrow();
            // Then combine the timestamp path with the content hash to get the parent hash
            currentHash = BlockImplUtils.combine(timestampBytes, currentHash);

            timestampPathCounter++;
        }

        // This hash must now equal the root hash of the given block
        return currentHash;
    }

    // A quick note: any field in this record can be null depending on which of the three types of merkle path it
    // represents. For example, the timestamp path will have a leaf but no hash, the block contents path will have a
    // hash but no leaf, and the parent path will have neither.
    private record PartialMerklePath(
            @Nullable MerkleLeaf leaf, @Nullable Bytes hash, @Nullable List<MerkleSiblingHash> siblingHashes) {}
}
