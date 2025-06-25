// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static org.assertj.core.api.BDDAssertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody.DataOneOfType;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.PreCheckException;
import java.security.InvalidKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HandlerUtilityTest extends ScheduleHandlerTestBase {
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(666L).build();
    private static final Timestamp VALID_START = new Timestamp(1_234_567L, 890);

    @BeforeEach
    void setUp() throws PreCheckException, InvalidKeyException {
        setUpBase();
    }

    @Test
    void scheduledTxnIdIsSchedulingIdWithTrueIfNotAlreadySet() {
        final var schedulingId = TransactionID.newBuilder()
                .accountID(PAYER_ID)
                .transactionValidStart(VALID_START)
                .build();
        final var scheduledId = HandlerUtility.scheduledTxnIdFrom(schedulingId);
        assertThat(scheduledId)
                .isEqualTo(schedulingId.copyBuilder().scheduled(true).build());
    }

    @Test
    void scheduledTxnIdIsSchedulingIdWithIncrementedNonceIfNotAlreadySet() {
        final var nonce = 123;
        final var schedulingId = TransactionID.newBuilder()
                .accountID(PAYER_ID)
                .nonce(nonce)
                .scheduled(true)
                .transactionValidStart(VALID_START)
                .build();
        final var scheduledId = HandlerUtility.scheduledTxnIdFrom(schedulingId);
        assertThat(scheduledId)
                .isEqualTo(scheduledId.copyBuilder().nonce(nonce + 1).build());
    }

    @Test
    void asOrdinaryHandlesAllTypes() {
        for (final Schedule next : listOfScheduledOptions) {
            final AccountID originalPayer =
                    next.originalCreateTransaction().transactionID().accountID();
            final AccountID payer = next.payerAccountIdOrElse(originalPayer);
            final TransactionBody result = HandlerUtility.childAsOrdinary(next);
            assertThat(result).isNotNull();
            assertThat(result.transactionFee()).isGreaterThan(0L);
            assertThat(result.transactionID()).isNotNull();
            assertThat(result.transactionID().scheduled()).isTrue();
            assertThat(result.transactionID().accountID()).isEqualTo(payer);
        }
    }

    @Test
    void functionalityForTypeHandlesAllTypes() {
        for (DataOneOfType input : DataOneOfType.values()) {
            assertThat(HandlerUtility.functionalityForType(input)).isNotNull();
        }
    }

    @Test
    void createProvisionalScheduleCreatesCorrectSchedule() {
        // Creating a provisional schedule should produce the expected Schedule except for Schedule ID.
        for (final Schedule next : listOfScheduledOptions) {
            final TransactionBody createTransaction = next.originalCreateTransaction();
            final String createMemo = createTransaction.scheduleCreate().memo();
            final boolean createWait = createTransaction.scheduleCreate().waitForExpiry();
            final Schedule.Builder build = next.copyBuilder().memo(createMemo);
            final Schedule expected = build.waitForExpiry(createWait).build();
            final long maxLifeSeconds = scheduleConfig.maxExpirationFutureSeconds();
            final Schedule modified = HandlerUtility.createProvisionalSchedule(
                    createTransaction, testConsensusTime, maxLifeSeconds, true);

            assertThat(modified.executed()).isEqualTo(expected.executed());
            assertThat(modified.deleted()).isEqualTo(expected.deleted());
            assertThat(modified.resolutionTime()).isEqualTo(expected.resolutionTime());
            assertThat(modified.signatories()).containsExactlyElementsOf(expected.signatories());

            verifyPartialEquality(modified, expected);
            assertThat(modified.hasScheduleId()).isFalse();
        }
    }

    /**
     * Verify that "actual" is equal to "expected" with respect to almost all values.
     * <p> The following attributes are not verified here:
     * <ul>
     *     <li>schedule ID</li>
     *     <li>executed</li>
     *     <li>deleted</li>
     *     <li>resolution time</li>
     *     <li>signatories</li>
     * </ul>
     * These "un verified" values are what different tests expect to modify, so the specific tests verify each
     * value is, or is not, modified as appropriate for that test.
     * @param expected the expected values to match
     * @param actual the actual values to verify
     * @throws AssertionError if any verified value is not equal between the two parameters.
     */
    private static void verifyPartialEquality(final Schedule actual, final Schedule expected) {
        assertThat(actual.originalCreateTransaction()).isEqualTo(expected.originalCreateTransaction());
        assertThat(actual.memo()).isEqualTo(expected.memo());
        assertThat(actual.calculatedExpirationSecond()).isEqualTo(expected.calculatedExpirationSecond());
        assertThat(actual.providedExpirationSecond()).isEqualTo(expected.providedExpirationSecond());
        assertThat(actual.adminKey()).isEqualTo(expected.adminKey());
        assertThat(actual.payerAccountId()).isEqualTo(expected.payerAccountId());
        assertThat(actual.scheduledTransaction()).isEqualTo(expected.scheduledTransaction());
        assertThat(actual.schedulerAccountId()).isEqualTo(expected.schedulerAccountId());
        assertThat(actual.waitForExpiry()).isEqualTo(expected.waitForExpiry());
        assertThat(actual.scheduleValidStart()).isEqualTo(expected.scheduleValidStart());
    }
}
