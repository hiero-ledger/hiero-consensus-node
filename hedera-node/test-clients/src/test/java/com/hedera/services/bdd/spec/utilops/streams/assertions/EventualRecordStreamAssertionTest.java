// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static com.hederahashgraph.api.proto.java.ContractCreateTransactionBody.InitcodeSourceCase.INITCODESOURCE_NOT_SET;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.support.StreamDataListener;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.AssertionFailedError;

class EventualRecordStreamAssertionTest {
    private static final TransactionID PARENT_ID = TransactionID.newBuilder()
            .setAccountID(AccountID.newBuilder().setShardNum(11).setRealmNum(12).setAccountNum(1117))
            .setTransactionValidStart(Timestamp.newBuilder().setSeconds(1234).setNanos(1858))
            .build();
    private static final TransactionID CHILD_ID =
            PARENT_ID.toBuilder().setNonce(1).build();
    private static final TransactionBody CHILD_BODY = TransactionBody.newBuilder()
            .setTransactionID(CHILD_ID)
            .setContractCreateInstance(ContractCreateTransactionBody.getDefaultInstance())
            .build();
    private static final RecordStreamItem CHILD = item(CHILD_ID, CHILD_BODY);
    private static final RecordStreamItem INVALID_CHILD = item(
            CHILD_ID,
            CHILD_BODY.toBuilder()
                    .setContractCreateInstance(ContractCreateTransactionBody.newBuilder()
                            .setFileID(com.hederahashgraph.api.proto.java.FileID.newBuilder()
                                    .setFileNum(123)))
                    .build());

    private final AtomicReference<Optional<TransactionID>> registeredId = new AtomicReference<>(Optional.empty());
    private final AtomicInteger bodyChecks = new AtomicInteger();
    private HapiSpec spec;
    private EventualRecordStreamAssertion subject;
    private StreamDataListener listener;

    @BeforeEach
    void setUp() {
        spec = mock(HapiSpec.class, RETURNS_DEEP_STUBS);
        when(spec.setup().shard()).thenReturn(11L);
        when(spec.setup().realm()).thenReturn(12L);
        when(spec.registry().getMaybeTxnId("create")).thenAnswer(ignored -> registeredId.get());
        subject = EventualRecordStreamAssertion.eventuallyAssertingExplicitPass(
                s -> new TransactionBodyAssertion("create", s, id -> id.getNonce() == 1, (ignored, body) -> {
                    assertTrue(body.hasContractCreateInstance());
                    assertEquals(
                            INITCODESOURCE_NOT_SET,
                            body.getContractCreateInstance().getInitcodeSourceCase(),
                            "Child create must omit the initcode source");
                    bodyChecks.incrementAndGet();
                }),
                // Force the final check without sleeping or relying on thread scheduling.
                Duration.ZERO);
        listener = subject.listenerFor(spec);
    }

    @Test
    void recoversEarlyChildBeforeWaitingWithoutFurtherStreamDelivery() {
        listener.onNewItem(CHILD);
        assertEquals(0, bodyChecks.get());
        registeredId.set(Optional.of(PARENT_ID));

        assertDoesNotThrow(subject::assertHasPassed);
        assertEquals(1, bodyChecks.get());
    }

    @Test
    void recoversAtTimeoutIfTheIdWasStillUnavailableBeforeWaiting() {
        when(spec.registry().getMaybeTxnId("create"))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(PARENT_ID));
        listener.onNewItem(CHILD);

        assertDoesNotThrow(subject::assertHasPassed);
        assertEquals(1, bodyChecks.get());
    }

    @Test
    void stillPassesForDeliveryAfterRegistration() {
        registeredId.set(Optional.of(PARENT_ID));
        listener.onNewItem(CHILD);

        assertEquals(1, bodyChecks.get());
        assertDoesNotThrow(subject::assertHasPassed);
        listener.onNewItem(INVALID_CHILD);
        assertEquals(1, bodyChecks.get(), "delivery after completion must not access the spec again");
    }

    @ParameterizedTest
    @MethodSource("unrelatedIds")
    void doesNotRecoverAnUnrelatedOrWrongNonceRecord(final TransactionID id) {
        listener.onNewItem(item(id, CHILD_BODY));
        registeredId.set(Optional.of(PARENT_ID));

        assertTimedOut();
        assertEquals(0, bodyChecks.get());
    }

    @Test
    void stillTimesOutWhenTheTransactionIdNeverAppears() {
        listener.onNewItem(CHILD);

        assertTimedOut();
        assertEquals(0, bodyChecks.get());
    }

    @Test
    void stillTimesOutWhenTheMatchingChildIsAbsent() {
        registeredId.set(Optional.of(PARENT_ID));

        assertTimedOut();
    }

    @Test
    void rejectsAnInvalidBufferedBody() {
        listener.onNewItem(INVALID_CHILD);
        registeredId.set(Optional.of(PARENT_ID));

        final var failure = assertThrows(AssertionFailedError.class, subject::assertHasPassed);
        assertTrue(failure.getMessage().contains("Child create must omit the initcode source"));
        assertEquals(0, bodyChecks.get());
    }

    @Test
    void doesNotLetALaterPassingRecordMaskABufferedFailure() {
        listener.onNewItem(INVALID_CHILD);
        registeredId.set(Optional.of(PARENT_ID));
        listener.onNewItem(CHILD);

        final var failure = assertThrows(AssertionFailedError.class, subject::assertHasPassed);
        assertTrue(failure.getMessage().contains("Child create must omit the initcode source"));
        assertEquals(1, bodyChecks.get());
    }

    @Test
    void preservesAnExplicitFailureEvenIfAPassingRecordArrivesLater() {
        registeredId.set(Optional.of(PARENT_ID));
        listener.onNewItem(INVALID_CHILD);
        listener.onNewItem(CHILD);

        final var failure = assertThrows(AssertionFailedError.class, subject::assertHasPassed);
        assertTrue(failure.getMessage().contains("Child create must omit the initcode source"));
        assertEquals(0, bodyChecks.get());
    }

    @Test
    void retainsRecentCandidatesWhenThePendingBufferFills() {
        final var unrelatedChildId = CHILD_ID.toBuilder()
                .setTransactionValidStart(Timestamp.newBuilder().setSeconds(9999))
                .build();
        for (int i = 0; i < 600; i++) {
            listener.onNewItem(item(unrelatedChildId, CHILD_BODY));
        }
        listener.onNewItem(CHILD);
        registeredId.set(Optional.of(PARENT_ID));

        assertDoesNotThrow(subject::assertHasPassed);
        assertEquals(1, bodyChecks.get());
    }

    private void assertTimedOut() {
        final var failure = assertThrows(AssertionFailedError.class, subject::assertHasPassed);
        assertTrue(failure.getMessage().contains("Timed out"));
    }

    private static Stream<TransactionID> unrelatedIds() {
        return Stream.of(
                PARENT_ID,
                CHILD_ID.toBuilder().setNonce(2).build(),
                CHILD_ID.toBuilder()
                        .setAccountID(PARENT_ID.getAccountID().toBuilder().setAccountNum(1118))
                        .build(),
                CHILD_ID.toBuilder()
                        .setTransactionValidStart(
                                PARENT_ID.getTransactionValidStart().toBuilder().setNanos(1859))
                        .build());
    }

    private static RecordStreamItem item(final TransactionID id, final TransactionBody body) {
        final ByteString signedBytes = SignedTransaction.newBuilder()
                .setBodyBytes(body.toByteString())
                .build()
                .toByteString();
        return RecordStreamItem.newBuilder()
                .setTransaction(Transaction.newBuilder().setSignedTransactionBytes(signedBytes))
                .setRecord(TransactionRecord.newBuilder().setTransactionID(id))
                .build();
    }
}
