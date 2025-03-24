// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static org.assertj.core.api.BDDAssertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.scheduled.ScheduleSignTransactionBody;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fixtures.Assertions;
import com.hedera.node.app.spi.key.KeyComparator;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.TransactionKeys;
import com.hedera.node.app.workflows.prehandle.PreHandleContextImpl;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.InvalidKeyException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.mockito.Mockito;

class ScheduleSignHandlerTest extends ScheduleHandlerTestBase {
    private ScheduleSignHandler subject;

    private PreHandleContext realPreContext;

    @BeforeEach
    void setUp() throws PreCheckException, InvalidKeyException {
        subject = new ScheduleSignHandler(mock(ScheduleFeeCharging.class));
        setUpBase();
    }

    @Test
    void vanilla() throws PreCheckException {
        final TransactionBody testTransaction = scheduleSignTransaction(null);
        realPreContext = new PreHandleContextImpl(
                mockStoreFactory, testTransaction, testConfig, mockDispatcher, mockTransactionChecker, creatorInfo);

        subject.preHandle(realPreContext);
        assertThat(realPreContext.payer()).isEqualTo(scheduler);
        assertThat(realPreContext.payerKey()).isEqualTo(schedulerKey);
        assertThat(realPreContext.optionalNonPayerKeys()).isNotEqualTo(Collections.emptySet());
    }

    @Test
    void failsIfScheduleMissing() throws PreCheckException {
        final ScheduleID badScheduleID = ScheduleID.newBuilder().scheduleNum(1L).build();
        final TransactionBody testTransaction = scheduleSignTransaction(badScheduleID);
        realPreContext = new PreHandleContextImpl(
                mockStoreFactory, testTransaction, testConfig, mockDispatcher, mockTransactionChecker, creatorInfo);
        Assertions.assertThrowsPreCheck(() -> subject.preHandle(realPreContext), ResponseCodeEnum.INVALID_SCHEDULE_ID);
    }

    @Test
    void verifySignatoriesAreUpdatedWithoutExecution() throws PreCheckException {
        int successCount = 0;
        for (final Schedule next : listOfScheduledOptions) {
            final int startCount = scheduleMapById.size();
            writableSchedules.put(next);
            final TransactionBody signTransaction = scheduleSignTransaction(next.scheduleId());
            // only some keys are "valid" on the transaction with this mock setup
            final Set<Key> expectedSignatories = prepareContext(signTransaction);
            subject.handle(mockContext);
            verifySignHandleSucceededNoExecution(next, startCount);
            verifySignatorySet(next, expectedSignatories);
            successCount++;
        }
        // verify that all the transactions succeeded.
        assertThat(successCount).isEqualTo(listOfScheduledOptions.size());
    }

    @Test
    void verifyErrorConditions() throws PreCheckException {
        // verify a bad schedule ID fails correctly
        TransactionBody signTransaction = scheduleSignTransaction(badId);
        prepareContext(signTransaction);
        throwsHandleException(() -> subject.handle(mockContext), ResponseCodeEnum.INVALID_SCHEDULE_ID);

        // verify we fail a sign for a deleted transaction.
        // Use an arbitrary schedule from the big list for this.
        Schedule deleteTest = listOfScheduledOptions.get(3);
        deleteTest = deleteTest.copyBuilder().deleted(true).build();
        writableSchedules.put(deleteTest);
        signTransaction = scheduleSignTransaction(deleteTest.scheduleId());
        prepareContext(signTransaction);
        throwsHandleException(() -> subject.handle(mockContext), ResponseCodeEnum.SCHEDULE_ALREADY_DELETED);
    }

    @Test
    void handleExecutesImmediateIfPossible() throws HandleException, PreCheckException {
        int successCount = 0;
        for (final Schedule next : listOfScheduledOptions) {
            final int startCount = scheduleMapById.size();
            writableSchedules.put(next);
            final TransactionBody signTransaction = scheduleSignTransaction(next.scheduleId());
            // all keys are "valid" on the transaction with this mock setup
            prepareContextAllPass(signTransaction);
            subject.handle(mockContext);
            verifyAllSignatories(next, testChildKeys);
            verifySignHandleSucceededAndExecuted(next, startCount);
            successCount++;
        }
        // verify that all the transactions succeeded.
        assertThat(successCount).isEqualTo(listOfScheduledOptions.size());
    }

    private void verifyAllSignatories(final Schedule original, final TransactionKeys expectedKeys) {
        final Set<Key> combinedSet = new LinkedHashSet<>(5);
        combinedSet.addAll(expectedKeys.requiredNonPayerKeys());
        combinedSet.add(expectedKeys.payerKey());
        verifySignatorySet(original, combinedSet);
    }

    private void verifySignatorySet(final Schedule original, final Set<Key> expectedKeys) {
        assertThat(original.signatories()).isEmpty();
        final Schedule modified = writableSchedules.get(original.scheduleId());
        assertThat(modified).isNotNull();
        assertThat(original.signatories()).isEmpty();
        assertThat(modified.signatories()).containsExactlyInAnyOrderElementsOf(expectedKeys);
    }

    private TransactionBody scheduleSignTransaction(@Nullable final ScheduleID idToUse) {
        final ScheduleID confirmedId = idToUse == null ? testScheduleID : idToUse;
        return TransactionBody.newBuilder()
                .transactionID(alternateCreateTransaction.transactionID())
                .scheduleSign(ScheduleSignTransactionBody.newBuilder().scheduleID(confirmedId))
                .build();
    }

    private Set<Key> prepareContext(final TransactionBody signTransaction) throws PreCheckException {
        given(mockContext.body()).willReturn(signTransaction);
        given(mockContext.allKeysForTransaction(Mockito.any(), Mockito.any())).willReturn(testChildKeys);
        // for signature verification to be in-between, the "Answer" needs to be "valid" for only some required keys
        // We leave out "other" key from the "valid" keys for that reason.
        final Set<Key> acceptedKeys = Set.of(payerKey, optionKey);
        final TestTransactionKeys accepted = new TestTransactionKeys(payerKey, acceptedKeys, Collections.emptySet());
        final var signingSet = new ConcurrentSkipListSet<>(new KeyComparator());
        signingSet.addAll(acceptedKeys);
        given(keyVerifier.authorizingSimpleKeys()).willReturn(signingSet);
        // This is how you get side-effects replicated, by having the "Answer" called in place of the real method.
        given(keyVerifier.verificationFor(BDDMockito.any(Key.class), BDDMockito.any(VerificationAssistant.class)))
                .will(new VerificationForAnswer(accepted));
        return Set.of(payerKey); // return the expected set of signatories after the transaction is handled.
    }

    private void prepareContextAllPass(final TransactionBody signTransaction) throws PreCheckException {
        given(mockContext.body()).willReturn(signTransaction);
        given(mockContext.allKeysForTransaction(Mockito.any(), Mockito.any())).willReturn(testChildKeys);
        // for signature verification to succeed, the "Answer" needs to be "valid" for all keys
        final Set<Key> allKeys = Set.of(payerKey, adminKey, schedulerKey, optionKey, otherKey);
        final var signingSet = new ConcurrentSkipListSet<>(new KeyComparator());
        signingSet.addAll(allKeys);
        given(keyVerifier.authorizingSimpleKeys()).willReturn(signingSet);
        final TestTransactionKeys allRequired = new TestTransactionKeys(payerKey, allKeys, Collections.emptySet());
        // This is how you get side effects replicated, by having the "Answer" called in place of the real method.
        given(keyVerifier.verificationFor(BDDMockito.any(Key.class), BDDMockito.any(VerificationAssistant.class)))
                .will(new VerificationForAnswer(allRequired));
    }
}
