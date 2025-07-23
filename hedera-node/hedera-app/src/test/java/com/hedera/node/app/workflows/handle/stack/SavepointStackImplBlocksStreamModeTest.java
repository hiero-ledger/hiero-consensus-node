// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.swirlds.state.State;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SavepointStackImplBlocksStreamModeTest {
    @Mock
    private State state;

    @Mock
    private StreamBuilder streamBuilder;

    @Mock
    private BoundaryStateChangeListener boundaryStateChangeListener;

    @Mock
    private ImmediateStateChangeListener immediateStateChangeListener;

    private SavepointStackImpl subject;

    @BeforeEach
    void setUp() {
        subject = SavepointStackImpl.newRootStack(
                state, 3, 50, boundaryStateChangeListener, immediateStateChangeListener, BLOCKS);
    }

    @Test
    void commitsFullStackAsExpected() {
        final var mockChanges = List.of(StateChange.DEFAULT);
        given(immediateStateChangeListener.getStateChanges()).willReturn(mockChanges);

        subject.commitTransaction(streamBuilder);

        verify(immediateStateChangeListener).reset(null);
        verify(streamBuilder).stateChanges(mockChanges);
    }

    @Test
    void usesBlockStreamBuilderForChild() {
        final var childBuilder = subject.addChildRecordBuilder(StreamBuilder.class, CONTRACT_CALL);
        assertThat(childBuilder).isInstanceOf(BlockStreamBuilder.class);
    }

    @Test
    void usesBlockStreamBuilderForRemovableChild() {
        final var childBuilder = subject.addRemovableChildRecordBuilder(StreamBuilder.class, CONTRACT_CALL);
        assertThat(childBuilder).isInstanceOf(BlockStreamBuilder.class);
    }

    @Test
    void allCreatedBuildersAreBlockStreamBuilders() {
        assertThat(subject.createIrreversiblePrecedingBuilder()).isInstanceOf(BlockStreamBuilder.class);
        assertThat(subject.createRemovableChildBuilder()).isInstanceOf(BlockStreamBuilder.class);
        assertThat(subject.createReversibleChildBuilder()).isInstanceOf(BlockStreamBuilder.class);
    }
}
