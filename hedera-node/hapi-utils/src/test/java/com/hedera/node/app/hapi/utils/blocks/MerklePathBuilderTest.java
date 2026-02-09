// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.MerkleProof;
import com.swirlds.state.SiblingHash;
import java.util.List;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MerklePathBuilder}.
 */
class MerklePathBuilderTest {

    private static final Bytes TEST_STATE_ITEM = Bytes.wrap("test-state-item");
    private static final Hash TEST_HASH_1 = new Hash(new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
        31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48
    });
    private static final Hash TEST_HASH_2 = new Hash(new byte[] {
        48, 47, 46, 45, 44, 43, 42, 41, 40, 39, 38, 37, 36, 35, 34, 33, 32, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21,
        20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1
    });
    private static final Hash TEST_INNER_HASH = new Hash(new byte[] {
        10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
        28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48
    });

    @Test
    @DisplayName("fromStateApi() should throw NullPointerException for null input")
    void fromStateApiThrowsOnNull() {
        assertThatThrownBy(() -> MerklePathBuilder.fromStateApi(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("merkleProof must not be null");
    }

    @Test
    @DisplayName("build() should create MerklePath with single sibling")
    void buildWithSingleSibling() {
        // Given a MerkleProof with one sibling as represented by the State API (direction inverted)
        final var siblingHash = new SiblingHash(true, TEST_HASH_1);
        final var merkleProof = new MerkleProof(TEST_STATE_ITEM, List.of(siblingHash), List.of(TEST_INNER_HASH));

        // When building the MerklePath
        final var merklePath = MerklePathBuilder.fromStateApi(merkleProof).build();

        // Then the path should have the correct structure
        assertThat(merklePath).isNotNull();
        assertThat(merklePath.hasStateItemLeaf()).isTrue();
        assertThat(merklePath.stateItemLeaf()).isEqualTo(TEST_STATE_ITEM);
        assertThat(merklePath.siblings()).hasSize(1);
        assertThat(merklePath.siblings().get(0).isLeft()).isFalse();
        assertThat(merklePath.siblings().get(0).hash()).isEqualTo(Bytes.wrap(TEST_HASH_1.copyToByteArray()));
        assertThat(merklePath.nextPathIndex()).isEqualTo(-1);
    }

    @Test
    @DisplayName("build() should create MerklePath with multiple siblings")
    void buildWithMultipleSiblings() {
        // Given a MerkleProof with multiple siblings as represented by the State API (direction inverted)
        final var sibling1 = new SiblingHash(false, TEST_HASH_1); // Right sibling
        final var sibling2 = new SiblingHash(true, TEST_HASH_2); // Left sibling
        final var merkleProof = new MerkleProof(TEST_STATE_ITEM, List.of(sibling1, sibling2), List.of(TEST_INNER_HASH));

        // When building the MerklePath
        final var merklePath = MerklePathBuilder.fromStateApi(merkleProof).build();

        // Then the siblings should be converted correctly
        assertThat(merklePath.siblings()).hasSize(2);

        // First sibling (right)
        assertThat(merklePath.siblings().get(0).isLeft()).isTrue();
        assertThat(merklePath.siblings().get(0).hash()).isEqualTo(Bytes.wrap(TEST_HASH_1.copyToByteArray()));

        // Second sibling (left)
        assertThat(merklePath.siblings().get(1).isLeft()).isFalse();
        assertThat(merklePath.siblings().get(1).hash()).isEqualTo(Bytes.wrap(TEST_HASH_2.copyToByteArray()));
    }

    @Test
    @DisplayName("build() should create MerklePath with no siblings (root)")
    void buildWithNoSiblings() {
        // Given a MerkleProof with no siblings (root node)
        final var merkleProof = new MerkleProof(TEST_STATE_ITEM, List.of(), List.of(TEST_INNER_HASH));

        // When building the MerklePath
        final var merklePath = MerklePathBuilder.fromStateApi(merkleProof).build();

        // Then the path should have no siblings
        assertThat(merklePath.siblings()).isEmpty();
        assertThat(merklePath.hasStateItemLeaf()).isTrue();
        assertThat(merklePath.stateItemLeaf()).isEqualTo(TEST_STATE_ITEM);
        assertThat(merklePath.nextPathIndex()).isEqualTo(-1);
    }

    @Test
    @DisplayName("build() should preserve sibling order")
    void buildPreservesSiblingOrder() {
        // Given a MerkleProof with siblings in specific order (nearest to farthest from leaf)
        final var sibling1 = new SiblingHash(true, TEST_HASH_1);
        final var sibling2 = new SiblingHash(false, TEST_HASH_2);
        final var merkleProof = new MerkleProof(TEST_STATE_ITEM, List.of(sibling1, sibling2), List.of(TEST_INNER_HASH));

        // When building the MerklePath
        final var merklePath = MerklePathBuilder.fromStateApi(merkleProof).build();

        // Then the order should be preserved
        assertThat(merklePath.siblings()).hasSize(2);
        assertThat(merklePath.siblings().get(0).hash()).isEqualTo(Bytes.wrap(TEST_HASH_1.copyToByteArray()));
        assertThat(merklePath.siblings().get(1).hash()).isEqualTo(Bytes.wrap(TEST_HASH_2.copyToByteArray()));
    }

    @Test
    @DisplayName("build() should set nextPathIndex to -1 for standalone path")
    void buildSetsNextPathIndexToNegativeOne() {
        // Given any MerkleProof
        final var merkleProof = new MerkleProof(TEST_STATE_ITEM, List.of(), List.of(TEST_INNER_HASH));

        // When building the MerklePath
        final var merklePath = MerklePathBuilder.fromStateApi(merkleProof).build();

        // Then nextPathIndex should be -1 (indicating no parent path)
        assertThat(merklePath.nextPathIndex()).isEqualTo(-1);
    }

    @Test
    @DisplayName("fromStateApi() should invert sibling direction flag from the State API")
    void fromStateApiInvertsSiblingDirection() {
        // Given a MerkleProof with a sibling marked `isLeft=true` by the State API
        final var leftSibling = new SiblingHash(true, TEST_HASH_1);
        final var merkleProof = new MerkleProof(TEST_STATE_ITEM, List.of(leftSibling), List.of(TEST_INNER_HASH));

        // When building the MerklePath
        final var merklePath = MerklePathBuilder.fromStateApi(merkleProof).build();

        // Then the protobuf direction flag is inverted
        assertThat(merklePath.siblings().get(0).isLeft()).isFalse();
    }

    @Test
    @DisplayName("fromStateApi() should invert sibling direction flag from the State API (right sibling case)")
    void fromStateApiInvertsSiblingDirectionForRightSiblingCase() {
        // Given a MerkleProof with a sibling marked `isLeft=false` by the State API
        final var rightSibling = new SiblingHash(false, TEST_HASH_1);
        final var merkleProof = new MerkleProof(TEST_STATE_ITEM, List.of(rightSibling), List.of(TEST_INNER_HASH));

        // When building the MerklePath
        final var merklePath = MerklePathBuilder.fromStateApi(merkleProof).build();

        // Then the protobuf direction flag is inverted
        assertThat(merklePath.siblings().get(0).isLeft()).isTrue();
    }

    @Test
    @DisplayName("build() can be called multiple times with same result")
    void buildIsIdempotent() {
        // Given a MerkleProof
        final var siblingHash = new SiblingHash(true, TEST_HASH_1);
        final var merkleProof = new MerkleProof(TEST_STATE_ITEM, List.of(siblingHash), List.of(TEST_INNER_HASH));
        final var builder = MerklePathBuilder.fromStateApi(merkleProof);

        // When calling build multiple times
        final var path1 = builder.build();
        final var path2 = builder.build();

        // Then both results should be equal
        assertThat(path1).isEqualTo(path2);
    }

    @Test
    @DisplayName("build() should create state item leaf with correct bytes")
    void buildCreatesMerkleLeafWithStateItem() {
        // Given a MerkleProof with a specific state item
        final var stateItem = Bytes.wrap("custom-state-item-data");
        final var merkleProof = new MerkleProof(stateItem, List.of(), List.of(TEST_INNER_HASH));

        // When building the MerklePath
        final var merklePath = MerklePathBuilder.fromStateApi(merkleProof).build();

        // Then the MerklePath should contain the state item leaf bytes
        assertThat(merklePath.hasStateItemLeaf()).isTrue();
        assertThat(merklePath.stateItemLeaf()).isEqualTo(stateItem);
    }

    @Test
    @DisplayName("prefixMerklePathBuilder should retain shared suffix and recompute root hash")
    void prefixMerklePathBuilderRetainsSuffix() {
        final var sibling1 = new SiblingHash(false, TEST_HASH_1);
        final var sibling2 = new SiblingHash(true, TEST_HASH_2);
        final var merkleProof =
                new MerkleProof(TEST_STATE_ITEM, List.of(sibling1, sibling2), List.of(TEST_HASH_1, TEST_INNER_HASH));
        final var builder = MerklePathBuilder.fromStateApi(merkleProof);

        final var prefix = builder.prefixMerklePathBuilder(1);

        assertThat(prefix.hasLeaf()).isFalse();
        assertThat(prefix.getRootHash()).isEqualTo(builder.getRootHash());
        assertThat(prefix.getSiblingNodes()).hasSize(1);
        assertThat(prefix.getSiblingNodes().getFirst())
                .isEqualTo(builder.getSiblingNodes().getLast());
    }

    @Test
    @DisplayName("pruneFromRoot should drop root-most siblings and preserve leaf content")
    void pruneFromRootRemovesRootLevels() {
        final var sibling1 = new SiblingHash(false, TEST_HASH_1);
        final var sibling2 = new SiblingHash(true, TEST_HASH_2);
        final var merkleProof =
                new MerkleProof(TEST_STATE_ITEM, List.of(sibling1, sibling2), List.of(TEST_HASH_1, TEST_INNER_HASH));
        final var builder = MerklePathBuilder.fromStateApi(merkleProof);

        final var pruned = builder.pruneFromRoot(1);

        assertThat(pruned.build().stateItemLeaf()).isEqualTo(builder.build().stateItemLeaf());
        assertThat(pruned.getSiblingNodes()).hasSize(1);
        assertThat(pruned.getSiblingNodes().getFirst())
                .isEqualTo(builder.getSiblingNodes().getFirst());
        assertThat(pruned.getRootHash()).isNotNull();
    }

    @Test
    @DisplayName("appendSiblingNodes should recompute cached root hash")
    void appendSiblingNodesRecomputesRoot() {
        final var merkleProof = new MerkleProof(TEST_STATE_ITEM, List.of(), List.of(TEST_INNER_HASH));
        final var builder = MerklePathBuilder.fromStateApi(merkleProof);
        final var originalRoot = builder.getRootHash();

        final var sibling = SiblingNode.newBuilder()
                .isLeft(false)
                .hash(Bytes.wrap(TEST_HASH_1.copyToByteArray()))
                .build();
        builder.appendSiblingNode(sibling);

        assertThat(builder.getSiblingNodes()).hasSize(1);
        assertThat(builder.getRootHash()).isNotEqualTo(originalRoot);
    }
}
