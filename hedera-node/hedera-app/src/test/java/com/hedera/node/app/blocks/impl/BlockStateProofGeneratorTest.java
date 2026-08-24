// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.BLOCK_CONTENTS_PATH_INDEX;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.EXPECTED_MERKLE_PATH_COUNT;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.FINAL_NEXT_PATH_INDEX;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.ROOT_HASH_MERKLE_PATH_INDEX;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.UNSIGNED_BLOCK_SIBLING_COUNT;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class BlockStateProofGeneratorTest {
    @Test
    void verifyBlockStateProofs() {
        // Load and verify the pending proofs from resources (precondition)
        final var pendingBlockInputs = loadPendingBlocks();
        verifyLoadedBlocks(pendingBlockInputs);
        // Load and verify the expected state proofs from resources (precondition)
        final var expectedProofs = loadExpectedStateProofs();
        verifyLoadedProofs(expectedProofs);

        // Generate proofs from the test subject and verify they match the expected proofs
        final var pendingBlocksByBlockNum =
                pendingBlockInputs.stream().collect(Collectors.toMap(PendingBlock::number, pb -> pb));

        final var minBlockNum = pendingBlocksByBlockNum.keySet().stream()
                .min(Comparator.naturalOrder())
                .orElseThrow();
        final var latestSignedBlockNum = MAX_BLOCK_NUM;
        final var latestSignedBlockTimestamp =
                pendingBlocksByBlockNum.get(latestSignedBlockNum).blockTimestamp();

        for (long blockNum = minBlockNum; blockNum < latestSignedBlockNum; blockNum++) {
            final var currentBlock = pendingBlocksByBlockNum.remove(blockNum);

            // Generate the actual state proof
            final StateProof result = BlockStateProofGenerator.generateStateProof(
                    currentBlock,
                    latestSignedBlockNum,
                    FINAL_SIGNATURE,
                    latestSignedBlockTimestamp,
                    pendingBlocksByBlockNum.values().stream());
            // Verify the generated proof matches the expected proof
            Assertions.assertThat(result).isEqualTo(expectedProofs.get(blockNum));
        }
    }

    @Test
    void throwsOnDuplicatePendingBlockNumber() {
        final var current = pendingBlock(0L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
        final var duplicate = pendingBlock(0L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
        Assertions.assertThatThrownBy(() -> BlockStateProofGenerator.generateStateProof(
                        current, 1L, FINAL_SIGNATURE, Timestamp.DEFAULT, Stream.of(duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate pending block #0");
    }

    @Test
    void throwsWhenSignedBlockIsNotAfterCurrentPendingBlock() {
        final var current = pendingBlock(5L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
        // The signed block number equals the current pending block number, so there is no indirect range to prove
        Assertions.assertThatThrownBy(() -> BlockStateProofGenerator.generateStateProof(
                        current, 5L, FINAL_SIGNATURE, Timestamp.DEFAULT, Stream.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot construct an indirect proof for pending block #5 from signed block #5");
    }

    @Test
    void throwsWhenPendingBlocksDoNotCoverEntireRange() {
        final var current = pendingBlock(0L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
        // Blocks #1 and #2 between the current (#0) and signed (#3) blocks are absent from the queue
        final var signed = pendingBlock(3L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
        Assertions.assertThatThrownBy(() -> BlockStateProofGenerator.generateStateProof(
                        current, 3L, FINAL_SIGNATURE, Timestamp.DEFAULT, Stream.of(signed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending block #1 is missing");
    }

    @Test
    void throwsWhenSignedBlockItselfIsMissing() {
        // Every intermediate block is present; only the signed block (#102) at the top of the range is missing
        final var current = pendingBlock(100L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
        final var intermediate = pendingBlock(101L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
        Assertions.assertThatThrownBy(() -> BlockStateProofGenerator.generateStateProof(
                        current, 102L, FINAL_SIGNATURE, Timestamp.DEFAULT, Stream.of(intermediate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending block #102 is missing");
    }

    @Test
    void throwsWhenIndirectBlockHasUnexpectedSiblingCount() {
        // The proof starts from the current block's own root hash, so an intermediate indirect block (#1) is the
        // first whose siblings are consumed. Give it one too few sibling hashes for the expected layout.
        final var current = pendingBlock(0L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
        final var indirect = pendingBlock(1L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK - 1);
        final var signed = pendingBlock(2L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
        Assertions.assertThatThrownBy(() -> BlockStateProofGenerator.generateStateProof(
                        current, 2L, FINAL_SIGNATURE, Timestamp.DEFAULT, Stream.of(indirect, signed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Pending block #1 produced %d sibling hashes but exactly %d were expected"
                        .formatted(
                                BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK - 1,
                                BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK));
    }

    @Test
    void throwsWhenSignedBlockHasUnexpectedSiblingCount() {
        final var current = pendingBlock(0L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
        // The signed block has one too many sibling hashes for the fixed array layout
        final var signed = pendingBlock(1L, BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK + 1);
        Assertions.assertThatThrownBy(() -> BlockStateProofGenerator.generateStateProof(
                        current, 1L, FINAL_SIGNATURE, Timestamp.DEFAULT, Stream.of(signed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Signed block #1 produced %d sibling hashes but exactly %d were expected"
                        .formatted(
                                BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK + 1,
                                BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK));
    }

    /**
     * Builds a minimal {@link PendingBlock} with the given number and sibling-hash count. Each sibling hash reuses
     * {@link com.hedera.node.app.blocks.BlockStreamManager#HASH_OF_ZERO}, a valid SHA-384-length value, so that hash
     * conversion succeeds and only the guard under test can fail.
     */
    private static PendingBlock pendingBlock(final long number, final int siblingCount) {
        final var siblings = new MerkleSiblingHash[siblingCount];
        for (int i = 0; i < siblingCount; i++) {
            siblings[i] = new MerkleSiblingHash(false, HASH_OF_ZERO);
        }
        return new PendingBlock(
                number,
                null,
                HASH_OF_ZERO,
                HASH_OF_ZERO,
                BlockProof.newBuilder().block(number),
                new NoOpTestWriter(),
                Timestamp.DEFAULT,
                siblings);
    }

    /**
     * Verifies each generated state proof by following {@link MerklePath#nextPathIndex()} links —
     * the same traversal strategy the block-node's {@code StateProofVerifier} uses — and confirms
     * that the recomputed hash equals the signed block's expected root hash.
     */
    @Test
    void verifyStateProofsByTraversal() {
        final var pendingBlockInputs = loadPendingBlocks();

        final var pendingBlocksByBlockNum =
                pendingBlockInputs.stream().collect(Collectors.toMap(PendingBlock::number, pb -> pb));
        final var minBlockNum = pendingBlocksByBlockNum.keySet().stream()
                .min(Comparator.naturalOrder())
                .orElseThrow();
        final var latestSignedBlockTimestamp =
                pendingBlocksByBlockNum.get(MAX_BLOCK_NUM).blockTimestamp();
        final var expectedSignedBlockHash = EXPECTED_BLOCK_HASHES.get(MAX_BLOCK_NUM);

        // Blocks [minBlockNum, MAX_BLOCK_NUM-2] produce multi-block proofs (2+ indirect blocks);
        // block MAX_BLOCK_NUM-1 produces a single-block proof (exactly 1 indirect block).
        // Both cases must traverse correctly — the assertion message records the indirect-block count.
        for (long blockNum = minBlockNum; blockNum < MAX_BLOCK_NUM; blockNum++) {
            final var currentBlock = pendingBlocksByBlockNum.remove(blockNum);
            final StateProof stateProof = BlockStateProofGenerator.generateStateProof(
                    currentBlock,
                    MAX_BLOCK_NUM,
                    FINAL_SIGNATURE,
                    latestSignedBlockTimestamp,
                    pendingBlocksByBlockNum.values().stream());

            final var computedHash = traverseStateProof(stateProof, currentBlock.blockHash());
            Assertions.assertThat(computedHash)
                    .as(
                            "Traversal of state proof for block %d (%d indirect block(s)) must reproduce the signed block's root hash",
                            blockNum, MAX_BLOCK_NUM - blockNum)
                    .isEqualTo(expectedSignedBlockHash);
        }
    }

    /**
     * Traverses a {@link StateProof} in depth-first order and returns the recomputed root hash.
     *
     * <p>Paths are stored in DFS order so children always precede their parents. Each path's
     * {@code nextPathIndex} is a parent pointer. A bottom-up scan computes each path's hash
     * from its already-computed children:
     * <ul>
     *   <li>Timestamp leaf: {@code hashLeaf(timestamp)}</li>
     *   <li>Sibling path (has siblings): accumulate from {@code startHash}; left siblings are
     *       block timestamps, right siblings are sub-tree roots.</li>
     *   <li>Internal node (no content): combine the children that point to this path as
     *       {@code hashInternalNode(timestampChild, otherChild)}.</li>
     * </ul>
     * The terminal path (last in DFS order, {@code nextPathIndex < 0}) holds the root hash.
     */
    private Bytes traverseStateProof(final StateProof stateProof, final Bytes startHash) {
        final var paths = stateProof.paths();
        final var pathHashes = new Bytes[paths.size()];

        for (int i = 0; i < paths.size(); i++) {
            final var path = paths.get(i);
            if (path.hasTimestampLeaf()) {
                pathHashes[i] = BlockImplUtils.hashLeaf(path.timestampLeafOrThrow());
            } else if (!path.siblings().isEmpty()) {
                // In a valid state proof only mp2 (BLOCK_CONTENTS_PATH_INDEX) carries siblings.
                // If mp3 or mp4 were to have siblings the hash check below would incorrectly pass
                // or fail with a confusing message, so assert the path index up front.
                Assertions.assertThat(i)
                        .as("Only the block-contents path (index %d) should have siblings", BLOCK_CONTENTS_PATH_INDEX)
                        .isEqualTo(BLOCK_CONTENTS_PATH_INDEX);
                Assertions.assertThat(path.hash())
                        .as("Sibling path hash must equal startHash")
                        .isEqualTo(startHash);
                var current = startHash;
                for (final SiblingNode sibling : path.siblings()) {
                    if (sibling.isLeft()) {
                        // Left sibling is an indirect block's consensus timestamp
                        current = BlockImplUtils.hashInternalNode(sibling.hash(), current);
                    } else {
                        current = BlockImplUtils.hashInternalNode(current, sibling.hash());
                    }
                }
                pathHashes[i] = current;
            } else {
                // Only mp3 (the root path) falls here. Its two children are mp1 (timestamp) and mp2
                // (block-contents, which already climbed to the signed block's sub-tree root).
                Bytes timestampChildHash = null;
                Bytes otherChildHash = null;
                for (int j = 0; j < i; j++) {
                    if (paths.get(j).nextPathIndex() == i) {
                        if (paths.get(j).hasTimestampLeaf()) {
                            timestampChildHash = pathHashes[j];
                        } else {
                            otherChildHash = pathHashes[j];
                        }
                    }
                }
                if (timestampChildHash != null && otherChildHash != null) {
                    pathHashes[i] = BlockImplUtils.hashInternalNode(timestampChildHash, otherChildHash);
                }
            }
        }

        // The terminal path is last in DFS order and holds the root hash
        return pathHashes[paths.size() - 1];
    }

    /**
     * Regenerates the golden {@code .proof.json} files under {@code src/test/resources/state-proof/}
     * from the {@code .pnd.json} input files and the current {@link BlockStateProofGenerator} logic.
     * Remove {@code @Disabled} and run once whenever the generator's output format changes, then
     * restore {@code @Disabled} and commit the updated files.
     */
    @Test
    @Disabled("Run manually to regenerate golden proof files after changing BlockStateProofGenerator")
    void regenerateGoldenFiles() throws Exception {
        final var pendingBlockInputs = loadPendingBlocks();

        final var pendingBlocksByBlockNum =
                pendingBlockInputs.stream().collect(Collectors.toMap(PendingBlock::number, pb -> pb));
        final var minBlockNum = pendingBlocksByBlockNum.keySet().stream()
                .min(Comparator.naturalOrder())
                .orElseThrow();
        final var latestSignedBlockTimestamp =
                pendingBlocksByBlockNum.get(MAX_BLOCK_NUM).blockTimestamp();

        final var outDir = Path.of("src/test/resources/state-proof");
        for (long blockNum = minBlockNum; blockNum < MAX_BLOCK_NUM; blockNum++) {
            final var currentBlock = pendingBlocksByBlockNum.remove(blockNum);
            final StateProof result = BlockStateProofGenerator.generateStateProof(
                    currentBlock,
                    MAX_BLOCK_NUM,
                    FINAL_SIGNATURE,
                    latestSignedBlockTimestamp,
                    pendingBlocksByBlockNum.values().stream());
            Files.writeString(outDir.resolve(blockNum + ".proof.json"), StateProof.JSON.toJSON(result));
            System.out.println("Wrote golden file for block " + blockNum);
        }
    }

    /**
     * Precondition-checking method that verifies the pending blocks on disk match expectations.
     * @param pendingBlocks the loaded pending blocks
     */
    private void verifyLoadedBlocks(final List<PendingBlock> pendingBlocks) {
        // First verify the constant siblings of the first pending block (block 1)
        final var actualFirstSiblingHashes = Arrays.stream(
                        pendingBlocks.getFirst().siblingHashes())
                .map(MerkleSiblingHash::siblingHash)
                .toList();
        Assertions.assertThat(actualFirstSiblingHashes).hasSize(BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
        Assertions.assertThat(actualFirstSiblingHashes)
                .containsExactlyElementsOf(List.of(EXPECTED_FIRST_SIBLING_HASHES));

        // Verify that we have the expected number of pending block files: 5 indirect blocks, 1 direct block
        final var numProofs = pendingBlocks.size();
        Assertions.assertThat(numProofs).isEqualTo(EXPECTED_NUM_INDIRECT_PROOFS + 1);

        // Verify the timestamps of the loaded pending proofs
        for (int i = 0; i < numProofs - 1; i++) {
            final var currentPendingBlock = pendingBlocks.get(i);
            final var expectedTs = EXPECTED_BLOCK_TIMESTAMPS.get(i + MIN_INDIRECT_BLOCK_NUM);
            Assertions.assertThat(currentPendingBlock.blockTimestamp()).isEqualTo(expectedTs);
        }

        // Verify the block and previous block hashes of the loaded pending proofs
        for (int i = 0; i < numProofs; i++) {
            final var currentPendingBlock = pendingBlocks.get(i);
            final var expectedPrevHash = EXPECTED_PREVIOUS_BLOCK_HASHES.get((long) i);
            Assertions.assertThat(currentPendingBlock.previousBlockHash()).isEqualTo(expectedPrevHash);
            final var expectedHash = EXPECTED_BLOCK_HASHES.get((long) i);
            Assertions.assertThat(currentPendingBlock.blockHash()).isEqualTo(expectedHash);
        }
    }

    private void verifyLoadedProofs(@NonNull final Map<Long, StateProof> expectedIndirectProofs) {
        // Verify that we have the expected number of proof files, including the final signed block proof
        Assertions.assertThat(expectedIndirectProofs.size()).isEqualTo(EXPECTED_NUM_INDIRECT_PROOFS);
        expectedIndirectProofs.values().forEach(sp -> Assertions.assertThat(sp.signedBlockProof())
                .isEqualTo(EXPECTED_TSS_PROOF));

        // Verify the contents of each expected indirect proof
        final var min = expectedIndirectProofs.keySet().stream()
                .min(Comparator.naturalOrder())
                .orElseThrow();
        final long max = expectedIndirectProofs.keySet().stream()
                .max(Comparator.naturalOrder())
                .orElseThrow();
        Assertions.assertThat(expectedIndirectProofs.size()).isEqualTo((int) (max - min) + 1);
        final var expectedSignedTs = EXPECTED_BLOCK_TIMESTAMPS.get(MAX_BLOCK_NUM);

        // Merkle paths 1 and 3 are constant for all proofs, so pre-build them
        final var expectedSignedTsBytes = Timestamp.PROTOBUF.toBytes(expectedSignedTs);
        final var expectedMp1 = MerklePath.newBuilder()
                .timestampLeaf(expectedSignedTsBytes)
                .nextPathIndex(ROOT_HASH_MERKLE_PATH_INDEX)
                .build();
        final var expectedMp3 =
                MerklePath.newBuilder().nextPathIndex(FINAL_NEXT_PATH_INDEX).build();
        final var expectedFinalBlockHash = EXPECTED_BLOCK_HASHES.get(MAX_BLOCK_NUM);

        for (long outerCurrentBlockNum = min; outerCurrentBlockNum <= max; outerCurrentBlockNum++) {
            System.out.println("Verifying proof for block num: " + outerCurrentBlockNum);
            final var expectedStateProof = expectedIndirectProofs.get(outerCurrentBlockNum);
            final var paths = expectedStateProof.paths();
            Assertions.assertThat(paths.size()).isEqualTo(EXPECTED_MERKLE_PATH_COUNT);

            // Verify mp1
            Assertions.assertThat(paths.getFirst()).isEqualTo(expectedMp1);

            // Verify all the sibling hashes in mp2. Proof starts from the block's own root hash;
            // siblings begin at the next block, the last for each block being its reserved-branches root.
            final var allMp2Hashes = paths.get(BLOCK_CONTENTS_PATH_INDEX).siblings();

            var finalHash = EXPECTED_BLOCK_HASHES.get(outerCurrentBlockNum);
            for (int i = 0; i < allMp2Hashes.size(); i++) {
                if (i % UNSIGNED_BLOCK_SIBLING_COUNT == 0) {
                    // Verify the running hash against the expected previousBlockHash of the next block
                    final var key = ((long) i / (long) UNSIGNED_BLOCK_SIBLING_COUNT) + outerCurrentBlockNum + 1;
                    final var expectedPrevHash = EXPECTED_PREVIOUS_BLOCK_HASHES.get(key);
                    Assertions.assertThat(finalHash).isEqualTo(expectedPrevHash);
                }

                final var currentSibling = allMp2Hashes.get(i);
                if (currentSibling.isLeft()) {
                    // Left sibling is an indirect block's consensus timestamp
                    finalHash = BlockImplUtils.hashInternalNode(currentSibling.hash(), finalHash);
                } else {
                    finalHash = BlockImplUtils.hashInternalNode(finalHash, currentSibling.hash());
                }
            }

            // Combine the signed block's sub-tree root with its timestamp to reach the signed block root hash
            final var expectedHashedTsBytes = BlockImplUtils.hashLeaf(expectedSignedTsBytes);
            finalHash = BlockImplUtils.hashInternalNode(expectedHashedTsBytes, finalHash);
            Assertions.assertThat(finalHash).isEqualTo(expectedFinalBlockHash);
            System.out.println("Verified merkle path two for block " + outerCurrentBlockNum
                    + " produces expected signed block hash " + expectedFinalBlockHash);

            // Verify mp3 (terminal path)
            Assertions.assertThat(paths.getLast()).isEqualTo(expectedMp3);

            System.out.println("Finished verifying loaded state proof file for block " + outerCurrentBlockNum);
        }
    }

    private List<PendingBlock> loadPendingBlocks() {
        return loadPendingProofs().stream()
                .map(pp -> new PendingBlock(
                        pp.block(),
                        null,
                        pp.blockHash(),
                        pp.previousBlockHash(),
                        BlockProof.newBuilder().block(pp.block()),
                        new NoOpTestWriter(),
                        pp.blockTimestamp(),
                        pp.siblingHashesFromPrevBlockRoot().toArray(new MerkleSiblingHash[0])))
                .toList();
    }

    private List<PendingProof> loadPendingProofs() {
        try {
            final Path dir = stateProofResourceDir();

            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(p -> p.getFileName().toString().endsWith(".pnd.json"))
                        .sorted(Comparator.comparing(Path::toString))
                        .map(p -> {
                            try {
                                return PendingProof.JSON.parse(Bytes.wrap(Files.readAllBytes(p)));
                            } catch (IOException | ParseException e) {
                                throw new IllegalStateException("Unable to parse pending proof bytes from " + p, e);
                            }
                        })
                        .collect(Collectors.toList());
            }
        } catch (IOException | java.net.URISyntaxException e) {
            throw new IllegalStateException("Unable to load pending proof files", e);
        }
    }

    private Map<Long, StateProof> loadExpectedStateProofs() {
        try {
            final Path dir = stateProofResourceDir();

            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(p -> p.getFileName().toString().endsWith(".proof.json"))
                        .sorted(Comparator.comparing(Path::toString))
                        .map(p -> {
                            final var proofNum =
                                    Long.parseLong(p.getFileName().toString().split("\\.")[0]);

                            try {
                                return Pair.of(proofNum, StateProof.JSON.parse(Bytes.wrap(Files.readAllBytes(p))));
                            } catch (IOException | ParseException e) {
                                throw new IllegalStateException("Unable to parse state proof bytes from " + p, e);
                            }
                        })
                        .collect(Collectors.toMap(Pair::left, Pair::right));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load state proof files", e);
        }
    }

    private static Path stateProofResourceDir() throws URISyntaxException {
        Path dir;
        var resource = BlockStateProofGeneratorTest.class.getResource("/state-proof");
        if (resource != null) {
            dir = Path.of(resource.toURI());
        } else {
            dir = Path.of("src", "test", "resources");
        }

        return dir;
    }

    private static final int EXPECTED_NUM_INDIRECT_PROOFS = 5;
    private static final Bytes FINAL_SIGNATURE = Bytes.fromHex(
            "0bc4da96b97bc427f83cee9dd4e913b552c9b3ae13257112094551059d8f480ebfba086103f0d85e6a69827117d341981103"
                    + "8c5ad9cfea3fad92b8a5f21809d2955044684c3293f3bf2b26b5a5c12d3c289e9f6abbff93b9a17ce37617a9b02903000"
                    + "00000000000000000000000000000000000000000000000000000000000129ab62bfdc0a1c934a3d0db03e66398cec6973"
                    + "07995e5246837ab52e6a563d7805d7362bdf2c118e6464c06d22989b50a612bbe7b3567e10025e52c9e4c2b17e6c477c74"
                    + "663505bce399828f62456cd96922dc51ad9af417c4b52e6c8536cbb0d1d228ce8a355be6d151caae131cd8ee750b3209c0"
                    + "63e0239d4c1e4f68962f32a6708a5fe697ec50d6b8c73ab18169e0c474220ec7b405f0ebd0dea8ad294bde9487eb5f38e7"
                    + "289aa0ad7af5193367bd3a227b8b6052c56e6afe0f4b1ce147b1128af06a79e5e903702d162d94dced0f39107ad1484afa"
                    + "54c995d7a8bd760cf7e423def7810264854df51ce6ebfd2700b87d1add8ab3c64babd5bf637e8b439a7d780da999c3c40d"
                    + "42bda5cb1514315b4b909d0bece826dd61d210f73f76721008981a701b8b432c883bc7251f656849eb5d40ea0416846129"
                    + "2913577000d2c7d1dd9c886d5293fa8583fffb7c0cabe04a59d3c360704ae9b32153562ebdeb455ef0c52c2ffdfa3faa4a"
                    + "c5a68f80edcba14ef5e9c4b9dbfbb48d9dfe10a9cc21187741f5a66928702dadc848b40e2bb0ad77b597335301b13c41fa"
                    + "f2c7469a5814a0307d077af99e32790a720038d3307edb7fba153b85ed1db9b92c20040bb388ce2d6d4359bf03b7050653"
                    + "ee38b9901ae06d4aaa4d521e0112077e6e9b1770b373e2bcd67206564924bb33069943a271fafbedd6d1b02ec41ed889ba"
                    + "5c61c9f0e1b691c9507cf192f714d69e039c3120b14e7d268dc2a353d6369904cae5b7c188d346a9105e4d5729b51a6596"
                    + "6976f880bd15beb2061444510d136854e8c0612f87e01ea7d2dee2f09a6ebf378de787a9cbe554132187eaaec453ef882d"
                    + "a99cfa783a3917d1e65d362f6953aaf0311657ad55cc7cec4d2c774f2c68bbb3f8fd66d7c1d794ab189f8fae5542bc2ea2"
                    + "a609542753338d7726cc0496eba1aed0423f641bf99cd7cc3e8b18a26a415dd17fc2934b414004e72501cd92f083613e73"
                    + "1e36ca2c54b32cf8dc60ba3855f0f0e3f227abe77cf896291e9389f866119ce1430193d745c7d9eebf4e809447be6a91a3"
                    + "6de9118ef71e7acfc85b9639831142e670940eb6d7597935b4c7d3013cb73a529895ebab82a378fb3659c67c992dd81dce"
                    + "b3a5156ff4f6c7bb0c08da31819f6ddfcd234e5aa9b789d8a7d4166bde12abe78a5d5ba7e72580fed3b0cc3c6afba516b5"
                    + "d660b3cfa30f714f447479d14d464483990ddbc3335cb8657ab81b4a579a7114c6b7ab16e3de819bf90982976bc48b412c"
                    + "d7950c4a2cf2b29960713185f8274cbb1a4f5a309b269d0f352a1186acd2d79f5b0302cbfc8327ee83da52c6c87e809cb9"
                    + "ad429bd698378c0473516cf7ad6be057e13a280e3ee056011b50f670228661869df1da5029a521501620c12ee7c1a9472c"
                    + "d2e05fdf22756bb690670bba190adc57142e1dcadac3074a1b81fa64dfdf6c0b8ee469450b1c7cc6171282c2613a3c2e58"
                    + "398d35aa1bd8a690677226c69d32930a03e1cb57592e4fa16ed4346e006d6bf0dbc4bb16e489828507e6f4c441ec2d17fb"
                    + "29364e7d5c56d028a05aa85e7ec00227f5657f6b00544f046a142690ddb56c4a2253c25f2f8a3c5ed38bde1f996cf63257"
                    + "25ba755b37e0dee8ba55beb32c155dbd98457b4d1cbed58b36705857b3921f4654b840025804d1ef6f3cb30ce905531b0b"
                    + "abc7d0f47023afbae873bc8073823e38c1408617851b0579bef2660d7ad6cc28bb8aa317e789ce7eaf1b9a702f14f87165"
                    + "6d2a2aef73c0ae0b26a359c99fc77ce79fa0fbdaed923c650dc904ec0458c624e824a131cb379214b29def7c17c32139f7"
                    + "79559482af72707a17477a3760898cf57bc36acf10560a9113009f7dc5674f9fd97822003567cca4a50fa87c4dd02ae863"
                    + "028d07c1409c731f8bead0dd78053038812f37fd1f36fb1f96434d48ca148e21b55d24a913d5c46958a909ef7d6becccc1"
                    + "4ad47376de5d6e47e9fc366c7acae855a8d98f9e08052e55687de5ef0421316d48d70d53ea34ce5080eb0cedf8e95975d2"
                    + "eefb629a01a636206b797b8515764bbda54d5acb4daf54192e1c4e3165be31bc325f9ed2dc9d52342d8abfed0199e343d7"
                    + "888f162394ace1955f1e8d77ed429");

    private static final Bytes FIRST_EXPECTED_PREVIOUS_BLOCK_HASH = HASH_OF_ZERO;

    private static final long MIN_INDIRECT_BLOCK_NUM = 0L;
    private static final long MAX_BLOCK_NUM = 5L; // Includes the final pending (signed) block

    private static final Bytes[] EXPECTED_FIRST_SIBLING_HASHES = new Bytes[] {
        Bytes.fromBase64("vsAhtPNo4waRNOASwrQwcIPTqb3SBuJOXw2G4T1mNmVZM+wrQTRllmgXqcIIoRcX"),
        Bytes.fromBase64("szITXG1kGEeXF7DN1DvaAbyUh8cPXASqotbz+ddav6nSZkOGN3cg44MAtTf49zxN"),
        Bytes.fromBase64("Neol38vLZtLyxE3J2b6Hah7XTQgwpu3e3TGlyDRlUbW7xA3gqXZnm3jGlXIY9S6j"),
        // The root of the reserved branches 9-16
        Bytes.fromBase64("z352R/V4BwBvT1hw0iELW0A40ACyv6cRvO639KMnNGtQxh/aTlxoEQsDznCPuRz4")
    };

    private static final Map<Long, Timestamp> EXPECTED_BLOCK_TIMESTAMPS = Map.of(
            0L,
            Timestamp.newBuilder().seconds(1767744161).nanos(615197000).build(),
            1L,
            Timestamp.newBuilder().seconds(1767744172).nanos(723579000).build(),
            2L,
            Timestamp.newBuilder().seconds(1767744173).nanos(794422000).build(),
            3L,
            Timestamp.newBuilder().seconds(1767744174).nanos(880399000).build(),
            4L,
            Timestamp.newBuilder().seconds(1767744175).nanos(898747000).build(),
            5L,
            Timestamp.newBuilder().seconds(1767744176).nanos(922790000).build());
    private static final TssSignedBlockProof EXPECTED_TSS_PROOF =
            TssSignedBlockProof.newBuilder().blockSignature(FINAL_SIGNATURE).build();
    private static final Map<Long, Bytes> EXPECTED_BLOCK_HASHES = Map.of(
            0L,
            Bytes.fromBase64("uSoOcoQxNudtZLRHmr5xRGFJ6ulFlCpVkJGy66zOy79Eblzud7HXmWraO10BGNBe"),
            1L,
            Bytes.fromBase64("bf8uIiwZm7Q7HuyWNUyu0Vm7BKaU3EtfQMpn3cWMr3DndK3EIVcXVbI9vdRe4unV"),
            2L,
            Bytes.fromBase64("4ZovgLbr2KOVk3zTgjVWclMgIDTRR5wVMA16s2SVlcBTYM46z9g4IO7jBngztTPl"),
            3L,
            Bytes.fromBase64("rV4yBTsadYQcKbiLNurMTskWz0WglVts057Xu3RBWgclDsjZMWLifvoqx1uJPNHi"),
            4L,
            Bytes.fromBase64("8Hz6NrOOi+iyv6fResvEZ8CQwzX1KVAlDZvpvrQBCqH79aY/8ElKowzPa9xYAdp2"),
            5L,
            Bytes.fromBase64("6gXxZsMRh+2XEFovdXrdnKPW1uX0p8JtlreK3MvMLJX1+WmnzBaW/WtfSazGuzWg"));
    private static final Map<Long, Bytes> EXPECTED_PREVIOUS_BLOCK_HASHES;

    static {
        final var previousBlockHashesByBlock = new HashMap<Long, Bytes>();
        EXPECTED_BLOCK_HASHES.keySet().forEach(k -> {
            if (k == 0L) {
                previousBlockHashesByBlock.put(k, FIRST_EXPECTED_PREVIOUS_BLOCK_HASH);
            } else {
                previousBlockHashesByBlock.put(k, EXPECTED_BLOCK_HASHES.get(k - 1));
            }
        });
        previousBlockHashesByBlock.put(MAX_BLOCK_NUM, EXPECTED_BLOCK_HASHES.get(MAX_BLOCK_NUM - 1));
        EXPECTED_PREVIOUS_BLOCK_HASHES = previousBlockHashesByBlock;
    }

    private static class NoOpTestWriter implements BlockItemWriter {
        @Override
        public void openBlock(long blockNumber) {
            // No-op
        }

        @Override
        public void writePbjItemAndBytes(@NonNull BlockItem item, @NonNull Bytes bytes) {
            // No-op
        }

        @Override
        public void writePbjItem(@NonNull BlockItem item) {
            // No-op
        }

        @Override
        public void closeCompleteBlock() {
            // No-op
        }

        @Override
        public void flushPendingBlock(@NonNull final PendingProof pendingProof) {
            // No-op
        }

        @Override
        public void flushIncompleteBlock() {
            // No-op
        }
    }
}
