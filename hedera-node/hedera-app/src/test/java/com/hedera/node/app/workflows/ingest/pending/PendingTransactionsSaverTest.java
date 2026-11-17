// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.util.List;
import org.hiero.consensus.transaction.TransactionPoolNexus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PendingTransactionsSaverTest {
    private static final Bytes TX_A = Bytes.wrap(new byte[] {1});
    private static final Bytes TX_B = Bytes.wrap(new byte[] {2});

    @Mock
    private TransactionPoolNexus transactionPool;

    @Mock
    private PendingTransactionsStore store;

    private PendingTransactionsSaver subject;

    @BeforeEach
    void setUp() {
        subject = new PendingTransactionsSaver(transactionPool, store, Runnable::run);
    }

    @Test
    void savesEverythingDrainedAtFreezingAndAtSeal() throws Exception {
        given(transactionPool.drainApplicationTransactions()).willReturn(List.of(TX_A), List.of(TX_B));

        subject.drain();
        subject.drainAndSaveAsync(7L).join();

        verify(store).save(new SavedPendingTransactions(7L, List.of(TX_A, TX_B)));
    }

    @Test
    void writesNothingWhenNothingWasDrained() throws Exception {
        given(transactionPool.drainApplicationTransactions()).willReturn(List.of());

        final var future = subject.drainAndSaveAsync(7L);

        assertThat(future).isCompleted();
        verify(store, never()).save(any());
    }

    @Test
    void failedWriteStillCompletesNormally() throws Exception {
        given(transactionPool.drainApplicationTransactions()).willReturn(List.of(TX_A));
        willThrow(new IOException("disk full")).given(store).save(any());

        final var future = subject.drainAndSaveAsync(7L);

        assertThat(future).isCompleted().isNotCompletedExceptionally();
    }
}
