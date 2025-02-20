// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PLATFORM_NOT_ACTIVE;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppContextTest {
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final Duration RETRY_DELAY = Duration.ofMillis(1);
    private static final Duration VALID_DURATION = Duration.ofSeconds(120);
    private static final AccountID NODE_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(3L).build();
    private static final int TIMES_TO_TRY = 2;
    private static final int IDS_PER_TRY = 3;

    @Mock
    private Consumer<TransactionBody.Builder> spec;

    @Mock
    private BiConsumer<TransactionBody, String> onFailure;

    @Mock
    private AppContext.Gossip subject;

    private final AtomicReference<Runnable> task = new AtomicReference<>();
    private final Executor fakeExecutor = task::set;

    @BeforeEach
    void setUp() {
        doCallRealMethod()
                .when(subject)
                .submitFuture(
                        NODE_ACCOUNT_ID,
                        CONSENSUS_NOW,
                        VALID_DURATION,
                        spec,
                        fakeExecutor,
                        TIMES_TO_TRY,
                        IDS_PER_TRY,
                        RETRY_DELAY,
                        onFailure);
    }

    @Test
    void successfulSubmissionEndsDuplicateRetryLoop() {
        final var i = new AtomicInteger();
        doAnswer(invocationOnMock -> {
                    if (i.getAndIncrement() < 4) {
                        throw new IllegalArgumentException(DUPLICATE_TRANSACTION.protoName());
                    }
                    return null;
                })
                .when(subject)
                .submit(any());

        subject.submitFuture(
                NODE_ACCOUNT_ID,
                CONSENSUS_NOW,
                VALID_DURATION,
                spec,
                fakeExecutor,
                TIMES_TO_TRY,
                IDS_PER_TRY,
                RETRY_DELAY,
                onFailure);

        requireNonNull(task.get()).run();
        verify(onFailure).accept(any(), any());
    }

    @Test
    void backsOffAndRetriesInResponseToExcessiveDuplicates() {
        final var i = new AtomicInteger();
        doAnswer(invocationOnMock -> {
                    if (i.getAndIncrement() == 0) {
                        throw new IllegalArgumentException(DUPLICATE_TRANSACTION.protoName());
                    } else {
                        final var body = invocationOnMock.getArgument(0, TransactionBody.class);
                        final var txnId = body.transactionIDOrThrow();
                        final var validStart = txnId.transactionValidStartOrThrow();
                        assertEquals(
                                CONSENSUS_NOW.plusNanos(AppContext.Gossip.NANOS_TO_SKIP_ON_DUPLICATE),
                                asInstant(validStart));
                    }
                    return null;
                })
                .when(subject)
                .submit(any());

        subject.submitFuture(
                NODE_ACCOUNT_ID,
                CONSENSUS_NOW,
                VALID_DURATION,
                spec,
                fakeExecutor,
                TIMES_TO_TRY,
                IDS_PER_TRY,
                RETRY_DELAY,
                onFailure);

        requireNonNull(task.get()).run();
    }

    @Test
    void nonDuplicateFailureExhaustsRetries() {
        doAnswer(invocationOnMock -> {
                    throw new IllegalStateException(PLATFORM_NOT_ACTIVE.protoName());
                })
                .when(subject)
                .submit(any());

        final var future = subject.submitFuture(
                NODE_ACCOUNT_ID,
                CONSENSUS_NOW,
                VALID_DURATION,
                spec,
                fakeExecutor,
                TIMES_TO_TRY,
                IDS_PER_TRY,
                RETRY_DELAY,
                onFailure);

        requireNonNull(task.get()).run();
        assertTrue(future.isCompletedExceptionally());
        verify(onFailure, times(TIMES_TO_TRY)).accept(any(), any());
    }

    @Test
    void illegalArgumentBesidesDuplicateIsFatal() {
        doAnswer(invocationOnMock -> {
                    throw new IllegalArgumentException(NOT_SUPPORTED.protoName());
                })
                .when(subject)
                .submit(any());

        final var future = subject.submitFuture(
                NODE_ACCOUNT_ID,
                CONSENSUS_NOW,
                VALID_DURATION,
                spec,
                fakeExecutor,
                TIMES_TO_TRY,
                IDS_PER_TRY,
                RETRY_DELAY,
                onFailure);

        requireNonNull(task.get()).run();
        assertTrue(future.isCompletedExceptionally());
        verify(onFailure).accept(any(), eq(NOT_SUPPORTED.protoName()));
    }
}
