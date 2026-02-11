// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.BLOCK_CONTENTS_PATH_INDEX;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.EXPECTED_MERKLE_PATH_COUNT;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.FINAL_MERKLE_PATH_INDEX;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.FINAL_NEXT_PATH_INDEX;
import static com.hedera.node.app.blocks.impl.BlockStateProofGenerator.UNSIGNED_BLOCK_SIBLING_COUNT;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
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
import org.junit.jupiter.api.Test;

class BlockStateProofGeneratorTest {
    @Test
    void verifyBlockStateProofs() {
        // Load and verify the pending proofs from resources (precondition)
        final var pendingBlockInputs = loadPendingProofs().stream()
                .map(pp -> new PendingBlock(
                        pp.block(),
                        null,
                        pp.blockHash(),
						pp.blockTimestamp(),
                        pp.previousBlockHash(),
						pp.prevBlocksRootHash(),
						pp.startOfBlockStateRootHash(),
						pp.consensusHeaderRootHash(),
                        BlockProof.newBuilder().block(pp.block()),
                        new NoOpTestWriter(),
                        pp.siblingHashesFromPrevBlockRoot().toArray(new MerkleSiblingHash[0])))
                .toList();
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
        Assertions.assertThat(actualFirstSiblingHashes.size()).isEqualTo(BlockStreamManagerImpl.NUM_SIBLINGS_PER_BLOCK);
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
            Assertions.assertThat(currentPendingBlock.prevBlockHash()).isEqualTo(expectedPrevHash);
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
                .nextPathIndex(FINAL_MERKLE_PATH_INDEX)
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

            // Verify all the sibling hashes in mp2
            final var allMp2Hashes = paths.get(BLOCK_CONTENTS_PATH_INDEX).siblings();

            var sibPerBlockCounter = 0;
            var finalHash = EXPECTED_PREVIOUS_BLOCK_HASHES.get(outerCurrentBlockNum);
            for (int i = 0; i < allMp2Hashes.size(); i++) {
                if (i % UNSIGNED_BLOCK_SIBLING_COUNT == 0) {
                    // Verify the hash so far against the expected previous block hash
                    final var key = ((long) i / (long) UNSIGNED_BLOCK_SIBLING_COUNT) + outerCurrentBlockNum;
                    final var expectedPrevHash = EXPECTED_PREVIOUS_BLOCK_HASHES.get(key);
                    Assertions.assertThat(finalHash).isEqualTo(expectedPrevHash);
                }

                final var currentSibling = allMp2Hashes.get(i);
                sibPerBlockCounter++;
                if (sibPerBlockCounter == UNSIGNED_BLOCK_SIBLING_COUNT) {
                    // Hash this node/level as a single child since the reserved roots aren't encoded in the tree
                    finalHash = BlockImplUtils.hashInternalNodeSingleChild(finalHash);
                }

                if (currentSibling.isLeft()) {
                    // Final combination to reach the current (intermediate) block's root hash
                    finalHash = BlockImplUtils.hashInternalNode(currentSibling.hash(), finalHash);

                    // Reset sibling counter
                    sibPerBlockCounter = 0;
                } else {
                    finalHash = BlockImplUtils.hashInternalNode(finalHash, currentSibling.hash());
                }
            }

            // Hash the one-child node (depth 3, node 1) one final time before combining with the signed block's
            // timestamp
            finalHash = BlockImplUtils.hashInternalNodeSingleChild(finalHash);

            // Combine the hash thus far with the signed block's timestamp to verify the complete path to the signed
            // block root hash
            final var expectedHashedTsBytes = BlockImplUtils.hashLeaf(expectedSignedTsBytes);
            finalHash = BlockImplUtils.hashInternalNode(expectedHashedTsBytes, finalHash);
            Assertions.assertThat(finalHash).isEqualTo(expectedFinalBlockHash);
            System.out.println("Verified merkle path two for block " + outerCurrentBlockNum
                    + " produces expected signed block hash " + expectedFinalBlockHash);

            // Verify mp3
            Assertions.assertThat(paths.getLast()).isEqualTo(expectedMp3);

            System.out.println("Finished verifying loaded state proof file for block " + outerCurrentBlockNum);
        }
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
        Bytes.fromBase64("Neol38vLZtLyxE3J2b6Hah7XTQgwpu3e3TGlyDRlUbW7xA3gqXZnm3jGlXIY9S6j")
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
            Bytes.fromBase64("fPVG8M+HGGrH+OcYj/Huzt21v8MGlTP/uGRGOffAnmfZiWY39pVejl8mz+G/Br/W"),
            1L,
            Bytes.fromBase64("ahOe8gN58oNlZ9ujl4qQPznwhcLb8lm3vpfBAawc9BWVgxhG9M/E9TEcg4IC8Q13"),
            2L,
            Bytes.fromBase64("RRcfehwqOGFE5uitIhIaNoACq8s5exCg++1GtqgP33YVxh2DhPy87G9LpN4Guapz"),
            3L,
            Bytes.fromBase64("FwgU9fmkMUewLy4NinJTWlnx1ZhfynuD0u+Vwsjc+HbF8Ym1rh4msyRQUz++Ea2N"),
            4L,
            Bytes.fromBase64("v0llIoDI9Srpl2Zoqag9xornzUmlaFguYKMB/IxbDlVCzMMVzkygo2QyxGXd0Nuf"),
            5L,
            Bytes.fromBase64("BPQoPITnc3+YX70N8cVeRMlY+QekkYOHiVKxV2H9IO5eqFDTA+X6wkKmCsw1Lk4W"));
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
        public void jumpToBlockAfterFreeze(long blockNumber) {
            // No-op
        }
    }
}
