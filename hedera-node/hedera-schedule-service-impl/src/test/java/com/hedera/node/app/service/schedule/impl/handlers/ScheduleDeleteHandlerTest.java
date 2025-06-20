// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.BDDAssertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.scheduled.ScheduleDeleteTransactionBody;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.fixtures.Assertions;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.workflows.prehandle.PreHandleContextImpl;
import java.security.InvalidKeyException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.mockito.Mockito;

class ScheduleDeleteHandlerTest extends ScheduleHandlerTestBase {
    private final AccountID scheduleDeleter =
            AccountID.newBuilder().accountNum(3001L).build();

    private ScheduleDeleteHandler subject;
    private PreHandleContext realPreContext;

    @BeforeEach
    void setUp() throws PreCheckException, InvalidKeyException {
        setUpBase();
        subject = new ScheduleDeleteHandler(mock(ScheduleFeeCharging.class));
        reset(accountById);
        accountsMapById.put(scheduleDeleter, payerAccount);
    }

    @Test
    void preHandleHappyPath() throws PreCheckException {
        final TransactionBody deleteBody = scheduleDeleteTransaction(testScheduleID);
        realPreContext = new PreHandleContextImpl(
                mockStoreFactory, deleteBody, testConfig, mockDispatcher, mockTransactionChecker, creatorInfo);

        subject.preHandle(realPreContext);
        assertThat(scheduleDeleter).isEqualTo(realPreContext.payer());
        assertThat(Set.of()).isNotEqualTo(realPreContext.requiredNonPayerKeys());
    }

    @Test
    // when schedule id to delete is not found, fail with INVALID_SCHEDULE_ID
    void failsIfScheduleMissing() throws PreCheckException {
        final TransactionBody deleteBody = scheduleDeleteTransaction(testScheduleID);
        realPreContext = new PreHandleContextImpl(
                mockStoreFactory, deleteBody, testConfig, mockDispatcher, mockTransactionChecker, creatorInfo);
        scheduleMapById.put(testScheduleID, null);

        Assertions.assertThrowsPreCheck(() -> subject.preHandle(realPreContext), ResponseCodeEnum.INVALID_SCHEDULE_ID);
    }

    @Test
    // when admin key not set in scheduled tx, fail with SCHEDULE_IS_IMMUTABLE
    void failsIfScheduleIsImmutable() throws PreCheckException {
        final TransactionBody deleteBody = scheduleDeleteTransaction(testScheduleID);
        realPreContext = new PreHandleContextImpl(
                mockStoreFactory, deleteBody, testConfig, mockDispatcher, mockTransactionChecker, creatorInfo);

        final Schedule noAdmin = scheduleInState.copyBuilder().adminKey(nullKey).build();
        reset(writableById);
        scheduleMapById.put(scheduleInState.scheduleId(), noAdmin);
        Assertions.assertThrowsPreCheck(
                () -> subject.preHandle(realPreContext), ResponseCodeEnum.SCHEDULE_IS_IMMUTABLE);
    }

    @Test
    void verifySimpleDelete() throws PreCheckException {
        final Schedule beforeDelete = scheduleStore.get(testScheduleID);
        assertThat(beforeDelete.deleted()).isFalse();
        prepareContext(scheduleDeleteTransaction(testScheduleID));
        subject.handle(mockContext);
        final Schedule afterDelete = scheduleStore.get(testScheduleID);
        assertThat(afterDelete.deleted()).isTrue();
    }

    @Test
    void verifyUnauthorized() throws PreCheckException {
        final Schedule beforeDelete = scheduleStore.get(testScheduleID);
        assertThat(beforeDelete.deleted()).isFalse();
        prepareContext(scheduleDeleteTransaction(testScheduleID));
        given(keyVerifier.verificationFor(adminKey)).willReturn(new SignatureVerificationImpl(adminKey, null, false));
        throwsHandleException(() -> subject.handle(mockContext), ResponseCodeEnum.UNAUTHORIZED);
        final Schedule afterDelete = scheduleStore.get(testScheduleID);
        assertThat(afterDelete.deleted()).isFalse();
    }

    @Test
    void verifyHandleExceptionsForDelete() throws PreCheckException {
        final Schedule beforeDelete = scheduleStore.get(testScheduleID);
        assertThat(beforeDelete.deleted()).isFalse();
        final TransactionBody baseDelete = scheduleDeleteTransaction(testScheduleID);
        ScheduleDeleteTransactionBody.Builder failures =
                baseDelete.scheduleDelete().copyBuilder();
        final TransactionBody.Builder nextFailure = baseDelete.copyBuilder();
        failures.scheduleID(nullScheduleId);
        prepareContext(nextFailure.scheduleDelete(failures).build());
        assertThatThrownBy(() -> subject.handle(mockContext)).isInstanceOf(NullPointerException.class);
        final Schedule failBase = listOfScheduledOptions.get(3);

        final Schedule noAdmin = failBase.copyBuilder().adminKey(nullKey).build();
        writableSchedules.put(noAdmin);
        failures = baseDelete.scheduleDelete().copyBuilder().scheduleID(noAdmin.scheduleId());
        prepareContext(nextFailure.scheduleDelete(failures).build());
        throwsHandleException(() -> subject.handle(mockContext), ResponseCodeEnum.SCHEDULE_IS_IMMUTABLE);

        final Schedule deleted = failBase.copyBuilder().deleted(true).build();
        writableSchedules.put(deleted);
        failures = baseDelete.scheduleDelete().copyBuilder().scheduleID(deleted.scheduleId());
        prepareContext(nextFailure.scheduleDelete(failures).build());
        throwsHandleException(() -> subject.handle(mockContext), ResponseCodeEnum.SCHEDULE_ALREADY_DELETED);

        final Schedule executed = failBase.copyBuilder().executed(true).build();
        writableSchedules.put(executed);
        failures = baseDelete.scheduleDelete().copyBuilder().scheduleID(executed.scheduleId());
        prepareContext(nextFailure.scheduleDelete(failures).build());
        throwsHandleException(() -> subject.handle(mockContext), ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED);
    }

    private TransactionBody scheduleDeleteTransaction(final ScheduleID idToDelete) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(scheduleDeleter))
                .scheduleDelete(ScheduleDeleteTransactionBody.newBuilder().scheduleID(idToDelete))
                .build();
    }

    private void prepareContext(final TransactionBody deleteTransaction) throws PreCheckException {
        given(mockContext.body()).willReturn(deleteTransaction);
        given(mockContext.allKeysForTransaction(Mockito.any(), Mockito.any())).willReturn(testChildKeys);
        // This is how you get side effects replicated, by having the "Answer" called in place of the real method.
        given(keyVerifier.verificationFor(BDDMockito.any(Key.class), BDDMockito.any(VerificationAssistant.class)))
                .will(new VerificationForAnswer(testChildKeys));
        given(keyVerifier.verificationFor(adminKey)).willReturn(new SignatureVerificationImpl(adminKey, null, true));
    }
}
