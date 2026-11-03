// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsAssertion.SkipSynthItems.NO;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator.EXISTENCE_ONLY_VALIDATOR;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VisibleItemsAssertionTest {
    private static final String EXPECTED = "expected";

    private final HapiSpec spec = mock(HapiSpec.class);
    private final HapiSpecRegistry registry = mock(HapiSpecRegistry.class);

    @BeforeEach
    void setUp() {
        given(spec.registry()).willReturn(registry);
        given(registry.getMaybeTxnId(EXPECTED)).willReturn(Optional.empty());
    }

    @Test
    void newItemDoesNotRescanPendingItemsWhileRegisteredIdsAreUnchanged() {
        final var subject = new VisibleItemsAssertion(spec, EXISTENCE_ONLY_VALIDATOR, NO, EXPECTED);
        for (int i = 0; i < 100; i++) {
            subject.isApplicableTo(itemWith(txnId(i)));
        }
        clearInvocations(registry);

        subject.isApplicableTo(itemWith(txnId(100)));

        // One lookup to see the registered IDs are unchanged, one to try matching the new item
        verify(registry, times(2)).getMaybeTxnId(EXPECTED);
    }

    @Test
    void bufferedItemMatchesOnRecheckOnceItsIdIsRegistered() {
        final var subject = new VisibleItemsAssertion(spec, EXISTENCE_ONLY_VALIDATOR, NO, EXPECTED);
        subject.isApplicableTo(itemWith(txnId(1)));
        assertFalse(subject.recheckPending());

        given(registry.getMaybeTxnId(EXPECTED)).willReturn(Optional.of(txnId(1)));

        assertTrue(subject.recheckPending());
    }

    private static TransactionID txnId(final int validStartSeconds) {
        return TransactionID.newBuilder()
                .setAccountID(AccountID.newBuilder().setAccountNum(1234L))
                .setTransactionValidStart(Timestamp.newBuilder().setSeconds(validStartSeconds))
                .build();
    }

    private static RecordStreamItem itemWith(final TransactionID txnId) {
        final var body = TransactionBody.newBuilder()
                .setTransactionID(txnId)
                .setCryptoTransfer(CryptoTransferTransactionBody.getDefaultInstance())
                .build();
        final var transaction = Transaction.newBuilder()
                .setSignedTransactionBytes(SignedTransaction.newBuilder()
                        .setBodyBytes(body.toByteString())
                        .build()
                        .toByteString())
                .build();
        final var txnRecord = TransactionRecord.newBuilder()
                .setTransactionID(txnId)
                .setConsensusTimestamp(txnId.getTransactionValidStart())
                .build();
        return RecordStreamItem.newBuilder()
                .setTransaction(transaction)
                .setRecord(txnRecord)
                .build();
    }
}
