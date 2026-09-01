// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncrementalStreamingHasherTest {
    @Test
    void foldingReusesTheRemovedLeftChild() {
        final var left = BlockImplUtils.hashLeaf(new byte[] {1});
        final var right = BlockImplUtils.hashLeaf(new byte[] {2});
        final var originalRight = right.clone();
        final var expected = BlockImplUtils.hashInternalNode(left, right);
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(left), 1);

        hasher.addNodeByHash(right);

        assertThat(left).containsExactly(expected);
        assertThat(right).containsExactly(originalRight);
        assertThat(hasher.intermediateHashingState())
                .containsExactly(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(left));
    }

    @Test
    void computedRootsDoNotExposeOrMutateRetainedState() {
        for (int leafCount = 0; leafCount <= 3; leafCount++) {
            final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
            for (int i = 0; i < leafCount; i++) {
                hasher.addLeaf(new byte[] {(byte) i});
            }
            final var stateBefore = hasher.intermediateHashingState();
            final var expectedRoot = hasher.computeRootHash();

            final var returnedRoot = hasher.computeRootHash();
            returnedRoot[0] ^= 0x7f;

            assertThat(hasher.computeRootHash()).containsExactly(expectedRoot);
            assertThat(hasher.intermediateHashingState()).containsExactlyElementsOf(stateBefore);
        }
    }

    @Test
    void capturedIntermediateStateIsNotMutatedByLaterFolds() {
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        hasher.addLeaf(new byte[] {1});
        final var captured = hasher.intermediateHashingState();

        hasher.addLeaf(new byte[] {2});

        assertThat(captured)
                .containsExactly(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(BlockImplUtils.hashLeaf(new byte[] {1})));
        assertThat(hasher.intermediateHashingState()).isNotEqualTo(captured);
    }

    @Test
    void persistedStateRoundTripsAndContinuesHashing(@TempDir final Path tempDir) throws Exception {
        final var original = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        for (int i = 0; i < 7; i++) {
            original.addLeaf(new byte[] {(byte) i, (byte) (i + 1)});
        }
        final var stateFile = tempDir.resolve("hash-state.bin");
        original.save(stateFile);

        final var restored = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        restored.load(stateFile);

        assertThat(restored.leafCount()).isEqualTo(original.leafCount());
        assertThat(restored.intermediateHashingState()).containsExactlyElementsOf(original.intermediateHashingState());
        assertThat(restored.computeRootHash()).containsExactly(original.computeRootHash());

        final byte[] nextLeaf = {8, 9};
        original.addLeaf(nextLeaf);
        restored.addLeaf(nextLeaf);
        assertThat(restored.computeRootHash()).containsExactly(original.computeRootHash());
    }
}
