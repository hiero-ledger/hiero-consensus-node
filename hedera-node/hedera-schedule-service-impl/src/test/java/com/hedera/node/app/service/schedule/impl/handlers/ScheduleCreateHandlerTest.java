// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.IDENTICAL_SCHEDULE_ALREADY_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULED_TRANSACTION_NOT_IN_WHITELIST;
import static org.assertj.core.api.BDDAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody.DataOneOfType;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.schedule.WritableScheduleStore;
import com.hedera.node.app.service.schedule.impl.ScheduledTransactionFactory;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.fixtures.Assertions;
import com.hedera.node.app.spi.ids.EntityNumGenerator;
import com.hedera.node.app.spi.key.KeyComparator;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.app.spi.throttle.Throttle;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.workflows.prehandle.PreHandleContextImpl;
import java.security.InvalidKeyException;
import java.time.InstantSource;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ScheduleCreateHandlerTest extends ScheduleHandlerTestBase {
    @Mock
    private Throttle.Factory throttleFactory;

    @Mock
    private Throttle throttle;

    @Mock
    private ScheduleFeeCharging feeCharging;

    private ScheduleCreateHandler subject;
    private PreHandleContext realPreContext;

    @BeforeEach
    void setUp() throws PreCheckException, InvalidKeyException {
        subject = new ScheduleCreateHandler(idFactory, InstantSource.system(), throttleFactory, feeCharging);
        setUpBase();
    }

    @Test
    void preHandleVanilla() throws PreCheckException {
        realPreContext = new PreHandleContextImpl(
                mockStoreFactory,
                scheduleCreateTransaction(payer),
                testConfig,
                mockDispatcher,
                mockTransactionChecker,
                creatorInfo);
        subject.preHandle(realPreContext);

        assertThat(realPreContext).isNotNull();
        assertThat(realPreContext.payerKey()).isNotNull().isEqualTo(schedulerKey);
        assertThat(realPreContext.requiredNonPayerKeys()).isNotNull().hasSize(1);
        assertThat(realPreContext.optionalNonPayerKeys()).isNotNull().hasSize(1);

        assertThat(realPreContext.requiredNonPayerKeys()).isEqualTo(Set.of(adminKey));
        assertThat(realPreContext.optionalNonPayerKeys()).isEqualTo(Set.of(payerKey));

        assertThat(mockContext).isNotNull();
    }

    @Test
    void preHandleVanillaNoAdmin() throws PreCheckException {
        final TransactionBody transactionToTest = ScheduledTransactionFactory.scheduleCreateTransactionWith(
                null, "", payer, scheduler, Timestamp.newBuilder().seconds(1L).build());
        realPreContext = new PreHandleContextImpl(
                mockStoreFactory, transactionToTest, testConfig, mockDispatcher, mockTransactionChecker, creatorInfo);
        subject.preHandle(realPreContext);

        assertThat(realPreContext).isNotNull();
        assertThat(realPreContext.payerKey()).isNotNull().isEqualTo(schedulerKey);
        assertThat(realPreContext.requiredNonPayerKeys()).isNotNull().isEmpty();
        assertThat(realPreContext.optionalNonPayerKeys()).isNotNull().hasSize(1);

        assertThat(realPreContext.optionalNonPayerKeys()).isEqualTo(Set.of(payerKey));
    }

    @Test
    void preHandleUsesCreatePayerIfScheduledPayerNotSet() throws PreCheckException {
        realPreContext = new PreHandleContextImpl(
                mockStoreFactory,
                scheduleCreateTransaction(null),
                testConfig,
                mockDispatcher,
                mockTransactionChecker,
                creatorInfo);
        subject.preHandle(realPreContext);

        assertThat(realPreContext).isNotNull();
        assertThat(realPreContext.payerKey()).isNotNull().isEqualTo(schedulerKey);
        assertThat(realPreContext.requiredNonPayerKeys()).isNotNull().hasSize(1);
        assertThat(realPreContext.optionalNonPayerKeys()).isNotNull().isEmpty();

        assertThat(realPreContext.requiredNonPayerKeys()).isEqualTo(Set.of(adminKey));
    }

    @Test
    void preHandleMissingPayerThrowsInvalidPayer() throws PreCheckException {
        reset(accountById);
        accountsMapById.put(payer, null);

        final TransactionBody createBody = scheduleCreateTransaction(payer);
        realPreContext = new PreHandleContextImpl(
                mockStoreFactory, createBody, testConfig, mockDispatcher, mockTransactionChecker, creatorInfo);
        Assertions.assertThrowsPreCheck(() -> subject.preHandle(realPreContext), ACCOUNT_ID_DOES_NOT_EXIST);
    }

    @Test
    void preHandleRejectsNonWhitelist() throws PreCheckException {
        final Set<HederaFunctionality> configuredWhitelist =
                scheduleConfig.whitelist().functionalitySet();
        for (final Schedule next : listOfScheduledOptions) {
            final TransactionBody createTransaction = next.originalCreateTransaction();
            final SchedulableTransactionBody child = next.scheduledTransaction();
            final DataOneOfType transactionType = child.data().kind();
            final HederaFunctionality functionType = HandlerUtility.functionalityForType(transactionType);
            realPreContext = new PreHandleContextImpl(
                    mockStoreFactory,
                    createTransaction,
                    testConfig,
                    mockDispatcher,
                    mockTransactionChecker,
                    creatorInfo);
            if (configuredWhitelist.contains(functionType)) {
                subject.preHandle(realPreContext);
                assertThat(realPreContext.payerKey()).isNotNull().isEqualTo(schedulerKey);
            } else {
                Assertions.assertThrowsPreCheck(
                        () -> subject.preHandle(realPreContext), SCHEDULED_TRANSACTION_NOT_IN_WHITELIST);
            }
        }
    }

    @Test
    void handleRejectsDuplicateTransaction() throws PreCheckException {
        final TransactionBody createTransaction = otherScheduleInState.originalCreateTransaction();
        prepareContext(createTransaction, otherScheduleInState.scheduleId().scheduleNum() + 1);
        throwsHandleException(() -> subject.handle(mockContext), IDENTICAL_SCHEDULE_ALREADY_CREATED);
    }

    @Test
    void handleRejectsNonWhitelist() throws HandleException, PreCheckException {
        final Set<HederaFunctionality> configuredWhitelist =
                scheduleConfig.whitelist().functionalitySet();
        given(keyVerifier.authorizingSimpleKeys()).willReturn(new ConcurrentSkipListSet<>(new KeyComparator()));
        given(throttleFactory.newThrottle(anyInt(), any())).willReturn(throttle);
        given(throttle.allow(any(), any(), any(), any())).willReturn(true);
        given(throttle.usageSnapshots()).willReturn(ThrottleUsageSnapshots.DEFAULT);
        for (final Schedule next : listOfScheduledOptions) {
            final TransactionBody createTransaction = next.originalCreateTransaction();
            final SchedulableTransactionBody child = next.scheduledTransaction();
            final DataOneOfType transactionType = child.data().kind();
            final HederaFunctionality functionType = HandlerUtility.functionalityForType(transactionType);
            prepareContext(createTransaction, next.scheduleId().scheduleNum());
            final int startCount = scheduleMapById.size();
            if (configuredWhitelist.contains(functionType)) {
                subject.handle(mockContext);
                verifyHandleSucceededForWhitelist(next, startCount);
            } else {
                throwsHandleException(() -> subject.handle(mockContext), SCHEDULED_TRANSACTION_NOT_IN_WHITELIST);
            }
        }
    }

    @Test
    void handleRefusesToExceedCreationLimit() throws HandleException, PreCheckException {
        final Set<HederaFunctionality> configuredWhitelist =
                scheduleConfig.whitelist().functionalitySet();
        assertThat(configuredWhitelist).hasSizeGreaterThan(4);

        final WritableScheduleStore fullStore = mock(WritableScheduleStore.class);
        given(fullStore.numSchedulesInState()).willReturn(scheduleConfig.maxNumber() + 1);
        given(mockContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableScheduleStore.class)).willReturn(fullStore);

        for (final Schedule next : listOfScheduledOptions) {
            final TransactionBody createTransaction = next.originalCreateTransaction();
            final SchedulableTransactionBody child = next.scheduledTransaction();
            final DataOneOfType transactionType = child.data().kind();
            final HederaFunctionality functionType = HandlerUtility.functionalityForType(transactionType);
            prepareContext(createTransaction, next.scheduleId().scheduleNum());
            // all keys are "valid" with this mock setup
            given(keyVerifier.verificationFor(any(Key.class), any(VerificationAssistant.class)))
                    .willReturn(new SignatureVerificationImpl(nullKey, null, true));
            if (configuredWhitelist.contains(functionType)) {
                throwsHandleException(
                        () -> subject.handle(mockContext), MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);
            }
        }
    }

    @Test
    void handleExecutesImmediateIfPossible() throws HandleException, PreCheckException {
        final Set<HederaFunctionality> configuredWhitelist =
                scheduleConfig.whitelist().functionalitySet();
        int successCount = 0;
        // make sure we have at least four items in the whitelist to test.
        assertThat(configuredWhitelist).hasSizeGreaterThan(4);
        given(throttleFactory.newThrottle(anyInt(), any())).willReturn(throttle);
        given(throttle.allow(any(), any(), any(), any())).willReturn(true);
        given(throttle.usageSnapshots()).willReturn(ThrottleUsageSnapshots.DEFAULT);
        for (final Schedule next : listOfScheduledOptions) {
            final TransactionBody createTransaction = next.originalCreateTransaction();
            final SchedulableTransactionBody child = next.scheduledTransaction();
            final DataOneOfType transactionType = child.data().kind();
            final HederaFunctionality functionType = HandlerUtility.functionalityForType(transactionType);
            prepareContext(createTransaction, next.scheduleId().scheduleNum());
            // all keys are "valid" with this mock setup
            given(keyVerifier.verificationFor(any(Key.class), any(VerificationAssistant.class)))
                    .willReturn(new SignatureVerificationImpl(nullKey, null, true));
            given(keyVerifier.authorizingSimpleKeys()).willReturn(new ConcurrentSkipListSet<>(new KeyComparator()));
            final int startCount = scheduleMapById.size();
            if (configuredWhitelist.contains(functionType)) {
                subject.handle(mockContext);
                verifyHandleSucceededAndExecuted(next, startCount);
                successCount++;
            } // only using whitelisted txns for this test
        }
        // verify that all the whitelisted txns actually executed and verified.
        assertThat(successCount).isEqualTo(configuredWhitelist.size());
    }

    private void verifyHandleSucceededForWhitelist(final Schedule next, final int startCount) {
        commit(writableById); // commit changes so we can inspect the underlying map
        // should be a new schedule in the map
        assertThat(scheduleMapById).hasSize(startCount + 1);
        // verifying that the handle really ran and created the new schedule
        final Schedule wrongSchedule = writableSchedules.get(next.scheduleId());
        assertThat(wrongSchedule).isNull(); // shard and realm *should not* match here
        // get a corrected schedule ID.
        final ScheduleID correctedId = adjustRealmShard(next);
        final Schedule resultSchedule = writableSchedules.get(correctedId);
        // verify the schedule was created ready for sign transactions
        assertThat(resultSchedule).isNotNull(); // shard and realm *should* match here
        assertThat(resultSchedule.deleted()).isFalse();
        assertThat(resultSchedule.executed()).isFalse();
    }

    private TransactionBody scheduleCreateTransaction(final AccountID payer) {
        final Timestamp timestampValue =
                Timestamp.newBuilder().seconds(1_234_567L).build();
        return ScheduledTransactionFactory.scheduleCreateTransactionWith(
                adminKey, "test", payer, scheduler, timestampValue);
    }

    private void prepareContext(final TransactionBody createTransaction, final long nextEntityId)
            throws PreCheckException {
        final EntityNumGenerator entityNumGenerator = mock(EntityNumGenerator.class);
        given(mockContext.body()).willReturn(createTransaction);
        given(mockContext.entityNumGenerator()).willReturn(entityNumGenerator);
        given(entityNumGenerator.newEntityNum()).willReturn(nextEntityId);
        given(mockContext.allKeysForTransaction(any(), any())).willReturn(testChildKeys);
        // This is how you get side effects replicated, by having the "Answer" called in place of the real method.
        given(keyVerifier.verificationFor(any(Key.class), any(VerificationAssistant.class)))
                .will(new VerificationForAnswer(testChildKeys));
    }
}
