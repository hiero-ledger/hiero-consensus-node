// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest.pending;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BUSY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.WAITING_FOR_LEDGER_ID;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.InitTrigger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PendingTransactionsRestorerTest {
    private static final Bytes TX_A = Bytes.wrap(new byte[] {1});
    private static final Bytes TX_B = Bytes.wrap(new byte[] {2});
    private static final Duration WINDOW = Duration.ofSeconds(180);

    @Mock
    private PendingTransactionsStore store;

    @Mock
    private Function<Bytes, ResponseCodeEnum> submitter;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);

    private PendingTransactionsRestorer subject;

    @BeforeEach
    void setUp() {
        subject = new PendingTransactionsRestorer(store, Runnable::run, now::get, Duration.ZERO);
    }

    @Test
    void restoresInOrderWhenRestartedFromTheFreezeState() {
        given(store.load()).willReturn(Optional.of(new SavedPendingTransactions(7L, List.of(TX_A, TX_B))));
        given(store.delete()).willReturn(true);
        given(submitter.apply(TX_A)).willReturn(OK);
        given(submitter.apply(TX_B)).willReturn(OK);

        subject.onStateInitialized(InitTrigger.RESTART, 7L);
        subject.restoreAsync(submitter, WINDOW);

        final var order = inOrder(store, submitter);
        order.verify(store).delete();
        order.verify(submitter).apply(TX_A);
        order.verify(submitter).apply(TX_B);
    }

    @Test
    void deletesWithoutRestoringWhenStartedFromALaterRound() {
        given(store.load()).willReturn(Optional.of(new SavedPendingTransactions(7L, List.of(TX_A))));

        subject.onStateInitialized(InitTrigger.RESTART, 8L);
        subject.restoreAsync(submitter, WINDOW);

        verify(store).delete();
        verifyNoInteractions(submitter);
    }

    @Test
    void keepsFileWithoutRestoringWhenStartedFromAnEarlierRound() {
        given(store.load()).willReturn(Optional.of(new SavedPendingTransactions(7L, List.of(TX_A))));

        subject.onStateInitialized(InitTrigger.RESTART, 5L);
        subject.restoreAsync(submitter, WINDOW);

        verify(store, never()).delete();
        verifyNoInteractions(submitter);
    }

    @Test
    void deletesWithoutLoadingOnReconnect() {
        subject.onStateInitialized(InitTrigger.RECONNECT, 7L);
        subject.restoreAsync(submitter, WINDOW);

        verify(store, never()).load();
        verify(store).delete();
        verifyNoInteractions(submitter);
    }

    @Test
    void reconnectBeforeActiveDiscardsLoadedTransactions() {
        given(store.load()).willReturn(Optional.of(new SavedPendingTransactions(7L, List.of(TX_A))));

        subject.onStateInitialized(InitTrigger.RESTART, 7L);
        subject.onStateInitialized(InitTrigger.RECONNECT, 9L);
        subject.restoreAsync(submitter, WINDOW);

        verifyNoInteractions(submitter);
    }

    @Test
    void retriesTransientRejectionsThenMovesOn() {
        given(store.load()).willReturn(Optional.of(new SavedPendingTransactions(7L, List.of(TX_A, TX_B))));
        given(store.delete()).willReturn(true);
        given(submitter.apply(TX_A)).willReturn(BUSY, WAITING_FOR_LEDGER_ID, OK);
        given(submitter.apply(TX_B)).willReturn(OK);

        subject.onStateInitialized(InitTrigger.RESTART, 7L);
        subject.restoreAsync(submitter, WINDOW);

        final var order = inOrder(submitter);
        order.verify(submitter, times(3)).apply(TX_A);
        order.verify(submitter).apply(TX_B);
    }

    @Test
    void dropsTransientRejectionsAfterRetryWindow() {
        given(store.load()).willReturn(Optional.of(new SavedPendingTransactions(7L, List.of(TX_A, TX_B))));
        given(store.delete()).willReturn(true);
        given(submitter.apply(TX_A)).willAnswer(invocation -> {
            now.set(now.get().plusSeconds(100));
            return BUSY;
        });
        given(submitter.apply(TX_B)).willReturn(OK);

        subject.onStateInitialized(InitTrigger.RESTART, 7L);
        subject.restoreAsync(submitter, WINDOW);

        // 0s -> 100s (retry) -> 200s (past the 180s window, dropped)
        verify(submitter, times(2)).apply(TX_A);
        verify(submitter).apply(TX_B);
    }

    @Test
    void dropsNonTransientRejectionsWithoutRetry() {
        given(store.load()).willReturn(Optional.of(new SavedPendingTransactions(7L, List.of(TX_A))));
        given(store.delete()).willReturn(true);
        given(submitter.apply(TX_A)).willReturn(TRANSACTION_EXPIRED);

        subject.onStateInitialized(InitTrigger.RESTART, 7L);
        subject.restoreAsync(submitter, WINDOW);

        verify(submitter, times(1)).apply(TX_A);
    }

    @Test
    void restoresOnlyOnce() {
        given(store.load()).willReturn(Optional.of(new SavedPendingTransactions(7L, List.of(TX_A))));
        given(store.delete()).willReturn(true);
        given(submitter.apply(TX_A)).willReturn(OK);

        subject.onStateInitialized(InitTrigger.RESTART, 7L);
        subject.restoreAsync(submitter, WINDOW);
        subject.restoreAsync(submitter, WINDOW);

        verify(submitter, times(1)).apply(TX_A);
    }

    @Test
    void skipsRestoreWhenFileCannotBeDeleted() {
        given(store.load()).willReturn(Optional.of(new SavedPendingTransactions(7L, List.of(TX_A))));
        given(store.delete()).willReturn(false);

        subject.onStateInitialized(InitTrigger.RESTART, 7L);
        subject.restoreAsync(submitter, WINDOW);

        verifyNoInteractions(submitter);
    }
}
