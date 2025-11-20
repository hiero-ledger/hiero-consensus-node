// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;

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

    private final BlockStateProofGenerator testSubject = new BlockStateProofGenerator();

    @Test
    void verifyBlockStateProofs() {
        // Load and verify the pending proofs from resources
        final var pendingBlocks = loadPendingProofs().stream()
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
        verifyLoadedBlocks(pendingBlocks);
        verifyLoadedProofs(loadExpectedStateProofs());

        // Generate and verify the proofs
        final var pendingBlocksByBlockNum =
                pendingBlocks.stream().collect(Collectors.toMap(PendingBlock::number, pb -> pb));
        final var latestSignedBlockNum = MAX_BLOCK_NUM;
        var outerCurrentBlockNum = MIN_INDIRECT_BLOCK_NUM;
        while (outerCurrentBlockNum < latestSignedBlockNum) {
            final var currentBlock = pendingBlocksByBlockNum.remove(outerCurrentBlockNum);

            // Generate the state proof
            final StateProof result = testSubject.generateStateProof(
                    currentBlock, latestSignedBlockNum, FINAL_SIGNATURE, pendingBlocksByBlockNum.values().stream());

            // Verify the state proof:
            // 1. The expected TSS proof should be the 'inner' proof for all produced state proofs
            Assertions.assertThat(result.signedBlockProof()).isEqualTo(EXPECTED_TSS_PROOF);

            // 2. Verify the Merkle Paths
            final var paths = result.paths();
            Assertions.assertThat(paths.size()).isEqualTo(3 * (latestSignedBlockNum - outerCurrentBlockNum));
            // This timestamp divider is an index into the array of merkle paths that separates the timestamp
            // paths–listed first–from the other two path types, listed in pairs afterward
            final var timestampDivider = (int) (latestSignedBlockNum - outerCurrentBlockNum);

            // The depth-first ordering of merkle paths isn't very convenient for computing hashes up the levels of the
            // tree, so we'll maintain a rearranged ordering conducive to hash computations
            final var reorderedPaths = new MerklePath[paths.size()];
            // Calculate the first merkle path (the block timestamp) for all blocks. Since a depth-first order is
            // required these paths must be listed in reverse-numerical order (i.e. depth 6, 5, 4, etc.)
            var currentParentPath = paths.size() - 1;
            var currentBlockNum = latestSignedBlockNum - 1;
            for (int i = 0; i < timestampDivider; i++) {
                final var mp1ForBlockX = paths.get(i);

                // 2a. Verify merkle path 1
                Assertions.assertThat(mp1ForBlockX.hasLeaf()).isTrue();
                Assertions.assertThat(mp1ForBlockX.leafOrThrow().hasBlockConsensusTimestamp())
                        .isTrue();
                Assertions.assertThat(mp1ForBlockX.siblings()).isEmpty();
                final var expectedTsMp1 = EXPECTED_BLOCK_TIMESTAMPS.get(currentBlockNum);
                final var expectedMp1Bytes = Timestamp.PROTOBUF.toBytes(expectedTsMp1);
                final var actualMp1Bytes = mp1ForBlockX.leafOrThrow().blockConsensusTimestampOrThrow();
                Assertions.assertThat(actualMp1Bytes).isEqualTo(expectedMp1Bytes);
                final var actualPathIndexMp1 = mp1ForBlockX.nextPathIndex();
                final var expectedPathIndexMp1 = currentParentPath;
                Assertions.assertThat(actualPathIndexMp1).isEqualTo(expectedPathIndexMp1);

                // Put merkle path 1 in the re-ordered array
                final var orderedArrayMp1Index = (int) (3 * (currentBlockNum - outerCurrentBlockNum));
                reorderedPaths[orderedArrayMp1Index] = mp1ForBlockX;

                currentBlockNum--;
                currentParentPath -= 2;
            }

            // Verify merkle paths 2 and 3 for each block
            var currentInnerBlock = outerCurrentBlockNum;
            var currentMp2ParentPath = timestampDivider + 1;
            for (int mp2Index = timestampDivider; mp2Index < paths.size(); mp2Index += 2) {
                // 2b. Verify the second merkle path (the path to the previous block root's hash)
                final var currentMp2 = paths.get(mp2Index);
                Assertions.assertThat(currentMp2.hasLeaf()).isFalse();
                Assertions.assertThat(currentMp2.hasHash()).isTrue();
                Assertions.assertThat(currentMp2.siblings()).hasSize(4);
                final var actualPathIndexMp2 = currentMp2.nextPathIndex();
                final var expectedPathIndexMp2 = currentMp2ParentPath;
                Assertions.assertThat(actualPathIndexMp2).isEqualTo(expectedPathIndexMp2);

                // Put merkle path 2 in the re-ordered array
                final var orderedArrayMp1Index = (int) (3 * (currentInnerBlock - outerCurrentBlockNum));
                reorderedPaths[orderedArrayMp1Index + 1] = currentMp2;

                // 2c. Verify the third merkle path (the parent of paths 1 and 2, also the block root hash)
                // mp3's index for block X should be mp1,mp2's next path index
                final var currentMp3 = paths.get(actualPathIndexMp2);

                // Verify mp3
                Assertions.assertThat(currentMp3.hasLeaf()).isFalse();
                Assertions.assertThat(currentMp3.hasHash()).isFalse();
                Assertions.assertThat(currentMp3.siblings()).isEmpty();
                // We'll verify the merkle paths further down; just a sanity check here
                Assertions.assertThat(currentMp3.nextPathIndex()).isGreaterThan(-2);

                // Put merkle path 3 in the re-ordered array
                reorderedPaths[orderedArrayMp1Index + 2] = currentMp3;

                currentInnerBlock++;
                currentMp2ParentPath += 2;
            }

            // Combine the paths to produce the signed block's previous block hash, from the bottom of the block merkle
            // tree upwards
            var finalHash = EXPECTED_PREVIOUS_BLOCK_HASHES.get(outerCurrentBlockNum);
            for (int mp1Index = 0; mp1Index < reorderedPaths.length; mp1Index += 3) {
                // Each set of three merkle paths represents the paths for a single block
                final var currentInnerBlockNum = (mp1Index / 3) + outerCurrentBlockNum;

                final var mp1 = reorderedPaths[mp1Index];
                final var mp2 = reorderedPaths[mp1Index + 1];
                Assertions.assertThat(mp2.hashOrThrow())
                        .isEqualTo(EXPECTED_PREVIOUS_BLOCK_HASHES.get(currentInnerBlockNum));

                // The mp2 combined hash should equal the computed hash
                for (final var sibling : mp2.siblings()) {
                    finalHash = BlockImplUtils.combine(finalHash, sibling.hash());
                }
                // Combine mp1's timestamp with mp2's combined hash
                final var hashedTs = noThrowSha384HashOf(mp1.leafOrThrow().blockConsensusTimestampOrThrow());
                finalHash = BlockImplUtils.combine(hashedTs, finalHash);

                Assertions.assertThat(finalHash).isEqualTo(EXPECTED_BLOCK_HASHES.get(currentInnerBlockNum));

                // Verify that the produced state proof matches the expected state proof for this block
                Assertions.assertThat(result)
                        .isEqualTo(loadExpectedStateProofs().get(outerCurrentBlockNum));
            }
            // 3. The final computed hash should equal the previous block hash for the latest signed block
            Assertions.assertThat(finalHash).isEqualTo(EXPECTED_BLOCK_HASHES.get(latestSignedBlockNum - 1));
            Assertions.assertThat(finalHash).isEqualTo(EXPECTED_PREVIOUS_BLOCK_HASHES.get(latestSignedBlockNum));

            // Verify that the last path's index is -1 (signals the end of the proof)
            Assertions.assertThat(reorderedPaths[reorderedPaths.length - 1].nextPathIndex())
                    .isEqualTo(-1);

            outerCurrentBlockNum++;
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
        Assertions.assertThat(actualFirstSiblingHashes.size()).isEqualTo(4);
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
    }

    private void verifyLoadedProofs(@NonNull final Map<Long, StateProof> expectedIndirectProofs) {
        // Verify that we have the expected number of proof files, including the final signed block proof
        Assertions.assertThat(expectedIndirectProofs.size()).isEqualTo(EXPECTED_NUM_INDIRECT_PROOFS);
        expectedIndirectProofs.values().forEach(sp -> Assertions.assertThat(sp.signedBlockProof())
                .isEqualTo(EXPECTED_TSS_PROOF));
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
            "ddc656d5bd85ca85ec3efd403421505352a2aa46f12f9dfd34abe4276529096f555492bdff46b4b703dced92db0c5a22");
    private static final Bytes FIRST_EXPECTED_PREVIOUS_BLOCK_HASH = Bytes.fromHex(
            "27130540fdaefd0f2ca260e14977feaa65e3f0a7d4fe8ef41b98fdb8270777059686935749cfcf772756e177fe3905c3");

    private static final long MIN_INDIRECT_BLOCK_NUM = 2L;
    private static final long MAX_BLOCK_NUM = 7L; // Includes the final signed block

    private static final Bytes[] EXPECTED_FIRST_SIBLING_HASHES = new Bytes[] {
        Bytes.fromHex(
                "126ba71c5677f64add52bd1927f211b570c6ae7e8c83a448b5c6f146a54979246a7bf15d5d4bafcceb734b851234a0ec"),
        Bytes.fromHex(
                "60766f786795ffe6520900453bae101b3b00d5d022c7ab87b1aac85604ed19139545932ea1aa6746a7cdf84597b68880"),
        Bytes.fromHex(
                "8c6764676177659a408e4db46f89cdbd2c6512ca45ea28a6b1b30c5e52756652ca08bc4d3126ca53f4e2e22036472e7f"),
        Bytes.fromHex(
                "94a6494ce6e4879af2a4ee4c7cd8185ce89c405cecdd4945483b3763fc7b184590db9a7bc6cbd9187fc277a1adb70cca")
    };
    private static final Map<Long, Timestamp> EXPECTED_BLOCK_TIMESTAMPS = Map.of(
            2L,
            Timestamp.newBuilder().seconds(1719230752).nanos(487328000).build(),
            3L,
            Timestamp.newBuilder().seconds(1719230757).nanos(487328000).build(),
            4L,
            Timestamp.newBuilder().seconds(1719230765).nanos(487328000).build(),
            5L,
            Timestamp.newBuilder().seconds(1719230773).nanos(487328000).build(),
            6L,
            Timestamp.newBuilder().seconds(1719230781).nanos(487328000).build());
    private static final TssSignedBlockProof EXPECTED_TSS_PROOF =
            TssSignedBlockProof.newBuilder().blockSignature(FINAL_SIGNATURE).build();
    private static final Map<Long, Bytes> EXPECTED_BLOCK_HASHES = Map.of(
            2L,
            Bytes.fromHex(
                    "5419e2857101ec86ed9f3459d56c6f9b98f84d30d4dd4e75d1f301450699d29f844eb77790901eecfa1536794ec368d5"),
            3L,
            Bytes.fromHex(
                    "fe4eaaa95fb97fed8c85809ecd2b065ba34de2d20a630409589bbc60e9fe846963a3d16de14d4cc15e8cbe4d7445f54a"),
            4L,
            Bytes.fromHex(
                    "b43ffbc025d8239612d9f32cbabf22a6a5ccaaa238a5accb3f8e7822e3521912a52399d5c689eeab03d967aadc7ba900"),
            5L,
            Bytes.fromHex(
                    "fa95d7a087a338ea8573100bc269f152fcf05122fe1ffcd7fe265e74eb7356bc91296d029d68b0a65a54259a7a9ac96d"),
            6L,
            Bytes.fromHex(
                    "b241ee40d60fcee871a257ef9cac7eadeeba4ddbe156006ee9e893dffdbba821241529263213757370c0a342369e32f7"));
    private static final Map<Long, Bytes> EXPECTED_PREVIOUS_BLOCK_HASHES;

    static {
        final var previousBlockHashesByBlock = new HashMap<Long, Bytes>();
        EXPECTED_BLOCK_HASHES.keySet().forEach(k -> {
            if (k == 2L) {
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
        public void flushPendingBlock(PendingProof pendingProof) {
            // No-op
        }

        @Override
        public void jumpToBlockAfterFreeze(long blockNumber) {
            // No-op
        }
    }
}
