// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockAcknowledgementsTrackerTest {
    private static final String NODE1 = "node1:50211";
    private static final String NODE2 = "node2:50211";
    private static final String NODE3 = "node3:50211";

    @Mock
    private BlockStreamStateManager blockStreamStateManager;

    private BlockAcknowledgementsTracker blockAcknowledgementsTracker;

    @Test
    void testAcknowledgementTrackerCreation() {
        blockAcknowledgementsTracker = new BlockAcknowledgementsTracker(blockStreamStateManager, false);
        // then
        assertThat(blockAcknowledgementsTracker.getLastVerifiedBlock()).isEqualTo(-1L);
    }

    @Test
    void testAcknowledgementForSingleNode() {
        blockAcknowledgementsTracker = new BlockAcknowledgementsTracker(blockStreamStateManager, false);

        // when
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE1, 1L);

        // then
        assertThat(blockAcknowledgementsTracker.getLastVerifiedBlock()).isEqualTo(1L);
    }

    @Test
    void testUpdateLastVerifiedBlockWhenNewAckReceived() {
        blockAcknowledgementsTracker = spy(new BlockAcknowledgementsTracker(blockStreamStateManager, false));

        // when
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE1, 1L);
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE1, 2L);

        verify(blockAcknowledgementsTracker, times(2)).checkBlockDeletion(anyLong());
        verify(blockAcknowledgementsTracker, times(0)).onBlockReadyForCleanup(anyLong());
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);

        // then
        assertThat(blockAcknowledgementsTracker.getLastVerifiedBlock()).isEqualTo(2L);
    }

    @Test
    void testTrackerDoesNotDeleteFilesOnDiskWhenFalse() {
        blockAcknowledgementsTracker = spy(new BlockAcknowledgementsTracker(blockStreamStateManager, false));
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE1, 1L);
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE2, 1L);

        verify(blockAcknowledgementsTracker, times(2)).checkBlockDeletion(anyLong());
        verify(blockAcknowledgementsTracker, times(0)).onBlockReadyForCleanup(anyLong());
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);

        // then
        assertThat(blockAcknowledgementsTracker.getLastVerifiedBlock()).isEqualTo(1L);
    }

    @Test
    void testTrackerDeleteFilesOnDiskWhenTrue() {
        blockAcknowledgementsTracker = spy(new BlockAcknowledgementsTracker(blockStreamStateManager, true));
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE1, 1L);
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE2, 1L);

        verify(blockAcknowledgementsTracker, times(2)).checkBlockDeletion(1L);
        verify(blockAcknowledgementsTracker, times(1)).onBlockReadyForCleanup(1L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);

        assertThat(blockAcknowledgementsTracker.getLastVerifiedBlock()).isEqualTo(1L);
    }

    @Test
    void shouldTestDifferentBlocksForDifferentNodes() {
        blockAcknowledgementsTracker = spy(new BlockAcknowledgementsTracker(blockStreamStateManager, true));

        // when
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE1, 1L);
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE2, 2L);
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE3, 3L);

        // then
        verify(blockAcknowledgementsTracker, times(3)).checkBlockDeletion(anyLong());
        verify(blockAcknowledgementsTracker, times(3)).onBlockReadyForCleanup(anyLong());
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(2L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(3L);

        assertThat(blockAcknowledgementsTracker.getLastVerifiedBlock()).isEqualTo(3L);
    }

    @Test
    void shouldTriggerCleanupOnlyOnceForSameBlock() {
        blockAcknowledgementsTracker = spy(new BlockAcknowledgementsTracker(blockStreamStateManager, true));

        // when
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE1, 1L);
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE2, 1L);
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE3, 1L);

        // then
        verify(blockAcknowledgementsTracker, times(1)).onBlockReadyForCleanup(1L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);

        assertThat(blockAcknowledgementsTracker.getLastVerifiedBlock()).isEqualTo(1L);
    }

    @Test
    void shouldHandleMultipleBlocksSimultaneously() {
        blockAcknowledgementsTracker = spy(new BlockAcknowledgementsTracker(blockStreamStateManager, true));

        // when
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE1, 1L);
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE1, 2L);
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE2, 1L);
        blockAcknowledgementsTracker.trackBlockAcknowledgements(NODE2, 2L);

        // then
        verify(blockAcknowledgementsTracker, times(1)).onBlockReadyForCleanup(1L);
        verify(blockAcknowledgementsTracker, times(1)).onBlockReadyForCleanup(2L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(1L);
        verify(blockStreamStateManager, times(1)).cleanUpBlockState(2L);

        assertThat(blockAcknowledgementsTracker.getLastVerifiedBlock()).isEqualTo(2L);
        assertThat(blockAcknowledgementsTracker.getLastVerifiedBlock()).isEqualTo(2L);
    }
}
