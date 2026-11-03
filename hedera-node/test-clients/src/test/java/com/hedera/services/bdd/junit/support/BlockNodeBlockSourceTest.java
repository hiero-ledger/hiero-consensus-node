// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.services.bdd.junit.support.FullStateValuesTest.FIRST;
import static com.hedera.services.bdd.junit.support.FullStateValuesTest.SECOND;
import static com.hedera.services.bdd.junit.support.FullStateValuesTest.accountIn;
import static com.hedera.services.bdd.junit.support.FullStateValuesTest.block;
import static com.hedera.services.bdd.junit.support.FullStateValuesTest.full;
import static com.hedera.services.bdd.junit.support.FullStateValuesTest.partial;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.hedera.BlockNodeReader;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockNodeBlockSourceTest {
    private static final Block GENESIS = block(0, full(FIRST));
    private static final Block PARTIAL = block(1, partial(FIRST, SECOND));

    private final BlockNodeReader reader = mock(BlockNodeReader.class);
    private final BlockingQueue<Block> delivered = new LinkedBlockingQueue<>();
    private final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expandsALivePartialAfterLoadingItsBaselineFromTheBlockNode(final boolean real) throws Exception {
        givenGenesisAlreadyOnTheBlockNodeThenPartial(real);
        when(reader.blocks(0, 0)).thenReturn(List.of(GENESIS));

        final var unsubscribe = new BlockNodeBlockSource(reader).subscribe(listener(true));
        try {
            final var received = delivered.poll(5, TimeUnit.SECONDS);
            assertThat(received).isNotNull();
            assertThat(accountIn(received)).isEqualTo(full(SECOND));
            assertThat(errors).isEmpty();
        } finally {
            unsubscribe.run();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMissingBaselineIsReportedInsteadOfDeliveringASparseValue(final boolean real) throws Exception {
        givenGenesisAlreadyOnTheBlockNodeThenPartial(real);
        when(reader.blocks(0, 0)).thenReturn(List.of());

        final var unsubscribe = new BlockNodeBlockSource(reader).subscribe(listener(true));
        try {
            final var error = errors.poll(5, TimeUnit.SECONDS);
            assertThat(error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Blocks #0 to #0 are incomplete on the block node");
            assertThat(delivered).isEmpty();
        } finally {
            unsubscribe.run();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawListenersReceiveTheOriginalSparseBlock(final boolean real) throws Exception {
        givenGenesisAlreadyOnTheBlockNodeThenPartial(real);

        final var unsubscribe = new BlockNodeBlockSource(reader).subscribe(listener(false));
        try {
            assertThat(delivered.poll(5, TimeUnit.SECONDS)).isSameAs(PARTIAL);
            verify(reader, never()).blocks(0, 0);
        } finally {
            unsubscribe.run();
        }
    }

    /** Block 0 is on the block node when the listener subscribes; block 1, with a partial update, arrives next. */
    @SuppressWarnings("unchecked")
    private void givenGenesisAlreadyOnTheBlockNodeThenPartial(final boolean real) throws Exception {
        when(reader.isReal()).thenReturn(real);
        if (real) {
            when(reader.latestAvailableBlock()).thenReturn(0L);
            when(reader.streamLive(anyLong(), any(), any())).thenAnswer(invocation -> {
                final Consumer<Block> onBlock = invocation.getArgument(1);
                onBlock.accept(GENESIS);
                onBlock.accept(PARTIAL);
                return (AutoCloseable) () -> {};
            });
        } else {
            when(reader.latestAvailableBlock()).thenReturn(0L, 1L);
            when(reader.blocks(1, 1)).thenReturn(List.of(PARTIAL));
        }
    }

    private StreamDataListener listener(final boolean wantsFullStateValues) {
        return new StreamDataListener() {
            @Override
            public boolean wantsFullStateValues() {
                return wantsFullStateValues;
            }

            @Override
            public void onNewBlock(final Block block) {
                delivered.add(block);
            }

            @Override
            public void onBlockError(final Throwable error) {
                errors.add(error);
            }
        };
    }
}
