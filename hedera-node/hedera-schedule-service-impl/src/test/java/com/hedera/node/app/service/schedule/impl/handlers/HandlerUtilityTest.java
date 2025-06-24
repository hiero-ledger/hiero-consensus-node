// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_MAX_CUSTOM_FEES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CUSTOM_FEES_IS_NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static org.assertj.core.api.BDDAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody.DataOneOfType;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.transaction.CustomFeeLimit;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.PreCheckException;
import java.security.InvalidKeyException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Nested
    class MemoValidation {
        private static final int MAX_MEMO_BYTES = 10;

        @Test
        void nullMemoIsValid() {
            assertDoesNotThrow(() -> HandlerUtility.checkMemo(null, MAX_MEMO_BYTES));
        }

        @Test
        void memoWithinLimitIsValid() {
            assertDoesNotThrow(() -> HandlerUtility.checkMemo("hello", MAX_MEMO_BYTES));
        }

        @Test
        void memoExceedingLimitThrowsException() {
            PreCheckException exception = assertThrows(
                    PreCheckException.class, () -> HandlerUtility.checkMemo("hello world", MAX_MEMO_BYTES));
            assertEquals(MEMO_TOO_LONG, exception.responseCode());
        }

        @ParameterizedTest
        @ValueSource(strings = {"\0", "\0Hello World", "Hello \0 World", "Hello World\0"})
        void memoWithNullByteThrowsException(String input) {
            PreCheckException exception =
                    assertThrows(PreCheckException.class, () -> HandlerUtility.checkMemo(input, 20));
            assertEquals(INVALID_ZERO_BYTE_IN_STRING, exception.responseCode());
        }
    }

    @Nested
    class CustomFeeValidation {
        private static final HederaFunctionality SUPPORTED_FUNC = CONSENSUS_SUBMIT_MESSAGE;
        private static final HederaFunctionality UNSUPPORTED_FUNC = CRYPTO_TRANSFER;

        @Test
        void unsupportedFuncWithFeesThrowsException() {
            List<CustomFeeLimit> fees = List.of(createValidFeeLimit());
            PreCheckException exception = assertThrows(
                    PreCheckException.class, () -> HandlerUtility.checkMaxCustomFees(fees, UNSUPPORTED_FUNC));
            assertEquals(MAX_CUSTOM_FEES_IS_NOT_SUPPORTED, exception.responseCode());
        }

        @Test
        void unsupportedFuncWithEmptyFeesIsValid() {
            assertDoesNotThrow(() -> HandlerUtility.checkMaxCustomFees(List.of(), UNSUPPORTED_FUNC));
        }

        @Test
        void nullAccountIdThrowsException() {
            CustomFeeLimit invalidFee = new CustomFeeLimit(null, List.of(new FixedFee(10, TokenID.DEFAULT)));
            PreCheckException exception = assertThrows(
                    PreCheckException.class,
                    () -> HandlerUtility.checkMaxCustomFees(List.of(invalidFee), SUPPORTED_FUNC));
            assertEquals(INVALID_MAX_CUSTOM_FEES, exception.responseCode());
        }

        @Test
        void emptyFeesListThrowsException() {
            CustomFeeLimit invalidFee = customFeeLimitWith(List.of());
            PreCheckException exception = assertThrows(
                    PreCheckException.class,
                    () -> HandlerUtility.checkMaxCustomFees(List.of(invalidFee), SUPPORTED_FUNC));
            assertEquals(INVALID_MAX_CUSTOM_FEES, exception.responseCode());
        }

        @Test
        void negativeFeeAmountThrowsException() {
            CustomFeeLimit invalidFee =
                    customFeeLimitWith(List.of(new FixedFee(10, TokenID.DEFAULT), new FixedFee(-1, TokenID.DEFAULT)));
            PreCheckException exception = assertThrows(
                    PreCheckException.class,
                    () -> HandlerUtility.checkMaxCustomFees(List.of(invalidFee), SUPPORTED_FUNC));
            assertEquals(INVALID_MAX_CUSTOM_FEES, exception.responseCode());
        }

        @Test
        void validFeesForSupportedFunc() {
            assertDoesNotThrow(() -> HandlerUtility.checkMaxCustomFees(List.of(createValidFeeLimit()), SUPPORTED_FUNC));
        }

        private CustomFeeLimit createValidFeeLimit() {
            return customFeeLimitWith(List.of(new FixedFee(10, TokenID.DEFAULT), new FixedFee(0, TokenID.DEFAULT)));
        }

        private CustomFeeLimit customFeeLimitWith(List<FixedFee> feeLimits) {
            return new CustomFeeLimit(AccountID.DEFAULT, feeLimits);
        }
    }
}
