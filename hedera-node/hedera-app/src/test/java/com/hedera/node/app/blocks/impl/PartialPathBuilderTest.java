// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.blocks.HashUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class PartialPathBuilderTest {
    private static final Bytes PREVIOUS_BLOCK_HASH = Bytes.fromHex("0123");
    private static final Bytes PREVIOUS_BLOCK_ROOTS_HASH = Bytes.fromHex("3456");
    private static final Bytes STARTING_STATE_HASH = Bytes.fromHex("6543");
    private static final Bytes CONSENSUS_HEADER_ROOT_HASH = Bytes.fromHex("3210");

    private static final MerkleSiblingHash[] PREV_BLOCK_HASH_SIBLINGS;

    static {
        final var digest = CommonUtils.sha384DigestOrThrow();
        final MerkleSiblingHash[] prevBlockHashSiblings = new MerkleSiblingHash[4];
        Bytes combinedHash = PREVIOUS_BLOCK_HASH;

        prevBlockHashSiblings[0] = MerkleSiblingHash.newBuilder()
                .isFirst(false)
                .siblingHash(PREVIOUS_BLOCK_ROOTS_HASH)
                .build();

        combinedHash = Bytes.wrap(
                HashUtils.joinHashes(digest, combinedHash.toByteArray(), PREVIOUS_BLOCK_ROOTS_HASH.toByteArray()));
        prevBlockHashSiblings[1] = MerkleSiblingHash.newBuilder()
                .isFirst(false)
                .siblingHash(combinedHash.replicate())
                .build();

        combinedHash = Bytes.wrap(
                HashUtils.joinHashes(digest, combinedHash.toByteArray(), PREVIOUS_BLOCK_ROOTS_HASH.toByteArray()));
        prevBlockHashSiblings[2] = MerkleSiblingHash.newBuilder()
                .isFirst(false)
                .siblingHash(combinedHash.replicate())
                .build();

        combinedHash = Bytes.wrap(HashUtils.computeSingleChildHash(digest, combinedHash.toByteArray()));
        prevBlockHashSiblings[3] = MerkleSiblingHash.newBuilder()
                .isFirst(false)
                .siblingHash(combinedHash.replicate())
                .build();

        PREV_BLOCK_HASH_SIBLINGS = prevBlockHashSiblings;
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void nullParamsThrow() {
        Assertions.assertThatThrownBy(() -> PartialPathBuilder.startingStateToBlockRoot(
                        null,
                        PREVIOUS_BLOCK_ROOTS_HASH,
                        STARTING_STATE_HASH,
                        CONSENSUS_HEADER_ROOT_HASH,
                        PREV_BLOCK_HASH_SIBLINGS))
                .isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> PartialPathBuilder.startingStateToBlockRoot(
                        PREVIOUS_BLOCK_HASH,
                        null,
                        STARTING_STATE_HASH,
                        CONSENSUS_HEADER_ROOT_HASH,
                        PREV_BLOCK_HASH_SIBLINGS))
                .isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> PartialPathBuilder.startingStateToBlockRoot(
                        PREVIOUS_BLOCK_HASH,
                        PREVIOUS_BLOCK_ROOTS_HASH,
                        null,
                        CONSENSUS_HEADER_ROOT_HASH,
                        PREV_BLOCK_HASH_SIBLINGS))
                .isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> PartialPathBuilder.startingStateToBlockRoot(
                        PREVIOUS_BLOCK_HASH,
                        PREVIOUS_BLOCK_ROOTS_HASH,
                        STARTING_STATE_HASH,
                        null,
                        PREV_BLOCK_HASH_SIBLINGS))
                .isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> PartialPathBuilder.startingStateToBlockRoot(
                        PREVIOUS_BLOCK_HASH,
                        PREVIOUS_BLOCK_ROOTS_HASH,
                        STARTING_STATE_HASH,
                        CONSENSUS_HEADER_ROOT_HASH,
                        ((MerkleSiblingHash[]) null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void buildsExpectedPartialPath() {
        final var result = PartialPathBuilder.startingStateToBlockRoot(
                PREVIOUS_BLOCK_HASH,
                PREVIOUS_BLOCK_ROOTS_HASH,
                STARTING_STATE_HASH,
                CONSENSUS_HEADER_ROOT_HASH,
                PREV_BLOCK_HASH_SIBLINGS);
        Assertions.assertThat(result.hasTimestampLeaf()).isFalse();
        Assertions.assertThat(result.hash()).isEqualTo(STARTING_STATE_HASH);
        Assertions.assertThat(result.siblings())
                .containsExactly(
                        SiblingNode.newBuilder()
                                .isLeft(false)
                                .hash(CONSENSUS_HEADER_ROOT_HASH)
                                .build(),
                        SiblingNode.newBuilder()
                                .isLeft(true)
                                .hash(Bytes.wrap(HashUtils.joinHashes(
                                        CommonUtils.sha384DigestOrThrow(),
                                        PREVIOUS_BLOCK_HASH.toByteArray(),
                                        PREVIOUS_BLOCK_ROOTS_HASH.toByteArray())))
                                .build(),
                        SiblingNode.newBuilder()
                                .isLeft(false)
                                .hash(PREV_BLOCK_HASH_SIBLINGS[2].siblingHash())
                                .build());
    }

    @Test
    void differentPrevBlockHashProducesDifferentPath() {
        final var differentPrevBlockHash = Bytes.fromHex("ffff");

        final var result = PartialPathBuilder.startingStateToBlockRoot(
                differentPrevBlockHash,
                PREVIOUS_BLOCK_ROOTS_HASH,
                STARTING_STATE_HASH,
                CONSENSUS_HEADER_ROOT_HASH,
                PREV_BLOCK_HASH_SIBLINGS);
        Assertions.assertThat(result.hasTimestampLeaf()).isFalse();
        Assertions.assertThat(result.hash()).isEqualTo(STARTING_STATE_HASH);
        Assertions.assertThat(result.siblings())
                .containsExactly(
                        SiblingNode.newBuilder()
                                .isLeft(false)
                                .hash(CONSENSUS_HEADER_ROOT_HASH)
                                .build(),
                        SiblingNode.newBuilder()
                                .isLeft(true)
                                .hash(Bytes.wrap(HashUtils.joinHashes(
                                        CommonUtils.sha384DigestOrThrow(),
                                        differentPrevBlockHash.toByteArray(),
                                        PREVIOUS_BLOCK_ROOTS_HASH.toByteArray())))
                                .build(),
                        SiblingNode.newBuilder()
                                .isLeft(false)
                                .hash(PREV_BLOCK_HASH_SIBLINGS[2].siblingHash())
                                .build());
    }
}
