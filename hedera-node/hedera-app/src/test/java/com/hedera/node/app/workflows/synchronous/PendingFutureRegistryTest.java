// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.synchronous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.node.app.spi.records.RecordSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PendingFutureRegistryTest {

    private PendingFutureRegistry registry;
    private TransactionID txnId;

    @BeforeEach
    void setup() {
        registry = new PendingFutureRegistry();
        txnId = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(1001).build())
                .build();
    }

    @Test
    @DisplayName("register() returns a non-null incomplete CompletableFuture")
    void registerReturnsNonNullIncompleteFuture() {
        final var future = registry.register(txnId);
        assertThat(future).isNotNull();
        assertThat(future).isNotDone();
    }

    @Test
    @DisplayName("complete() resolves the future with the given RecordSource")
    void completeResolvesTheFuture() {
        final var future = registry.register(txnId);
        final var recordSource = mock(RecordSource.class);

        registry.complete(txnId, recordSource);

        assertThat(future).isCompletedWithValue(recordSource);
    }

    @Test
    @DisplayName("complete() for unknown txnId is a no-op (no exception)")
    void completeForUnknownTxnIdIsNoOp() {
        assertThatNoException().isThrownBy(() -> registry.complete(txnId, mock(RecordSource.class)));
    }

    @Test
    @DisplayName("fail() completes the future exceptionally")
    void failCompletesExceptionally() {
        final var future = registry.register(txnId);
        final var cause = new RuntimeException("test failure");

        registry.fail(txnId, cause);

        assertThat(future).isCompletedExceptionally();
    }

    @Test
    @DisplayName("fail() for unknown txnId is a no-op (no exception)")
    void failForUnknownTxnIdIsNoOp() {
        assertThatNoException().isThrownBy(() -> registry.fail(txnId, new RuntimeException("test")));
    }

    @Nested
    @DisplayName("Independence of separate registrations")
    class Independence {

        @Test
        @DisplayName("Two separate registrations for different txnIds are independent")
        void twoRegistrationsAreIndependent() {
            final var txnId2 = TransactionID.newBuilder()
                    .accountID(AccountID.newBuilder().accountNum(1002).build())
                    .build();

            final var future1 = registry.register(txnId);
            final var future2 = registry.register(txnId2);

            final var source1 = mock(RecordSource.class);
            registry.complete(txnId, source1);

            assertThat(future1).isCompletedWithValue(source1);
            assertThat(future2).isNotDone();
        }
    }

    @Nested
    @DisplayName("Entry removal after completion")
    class EntryRemoval {

        @Test
        @DisplayName("complete() removes the entry so a second complete() call is a no-op")
        void completeRemovesEntryForSubsequentCalls() {
            final var future = registry.register(txnId);
            final var source1 = mock(RecordSource.class);
            final var source2 = mock(RecordSource.class);

            registry.complete(txnId, source1);
            assertThat(future).isCompletedWithValue(source1);

            // Second complete() should be a no-op since entry was removed
            registry.complete(txnId, source2);
            // Future remains completed with source1, not source2
            assertThat(future.getNow(null)).isSameAs(source1);
        }
    }
}
