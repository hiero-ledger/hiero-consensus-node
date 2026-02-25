// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack.savepoints;

import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.state.WrappedState;
import com.hedera.node.app.workflows.handle.stack.BuilderSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FirstRootSavepointTest {
    @Mock
    private WrappedState state;

    @Mock
    private BuilderSink parentSink;

    private FirstRootSavepoint subject;

    @Test
    void usesBlockStreamBuilderForBlocksStreamMode() {
        givenSubjectWithCapacities(1, 2);

        final var builder = subject.createBuilder(REVERSIBLE, CHILD, NOOP_SIGNED_TX_CUSTOMIZER, BLOCKS, false);

        assertThat(builder).isInstanceOf(BlockStreamBuilder.class);
    }

    private void givenSubjectWithCapacities(final int maxPreceding, final int maxFollowing) {
        given(parentSink.precedingCapacity()).willReturn(maxPreceding);
        given(parentSink.followingCapacity()).willReturn(maxFollowing);
        subject = new FirstRootSavepoint(state, parentSink);
    }
}
