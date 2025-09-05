// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import org.junit.jupiter.api.Test;

@Deprecated
class QueueNodeTest extends MerkleTestBase {
    @Test
    void usesQueueNodeIdFromMetadataIfAvailable() {
        final var node = new QueueNode<>(
                FIRST_SERVICE,
                FRUIT_STATE_KEY,
                queueNodeClassId(FRUIT_STATE_KEY),
                singletonClassId(FRUIT_STATE_KEY),
                STRING_CODEC);
        assertNotEquals(0x990FF87AD2691DCL, node.getClassId());
    }

    @Test
    void usesDefaultClassIdWithoutMetadata() {
        final var node = new QueueNode<>();
        assertEquals(0x990FF87AD2691DCL, node.getClassId());
    }
}
