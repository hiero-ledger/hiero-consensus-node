// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.validation;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_MAX_CUSTOM_FEES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CUSTOM_FEES_IS_NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hedera.node.app.spi.validation.PreCheckValidator.checkMaxCustomFees;
import static com.hedera.node.app.spi.validation.PreCheckValidator.checkMemo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.transaction.CustomFeeLimit;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class PreCheckValidatorTest {

    @Nested
    class MemoValidation {
        private static final int MAX_MEMO_BYTES = 10;

        @Test
        void nullMemoIsValid() {
            assertDoesNotThrow(() -> checkMemo(null, MAX_MEMO_BYTES));
        }

        @Test
        void memoWithinLimitIsValid() {
            assertDoesNotThrow(() -> checkMemo("hello", MAX_MEMO_BYTES));
        }

        @Test
        void memoExceedingLimitThrowsException() {
            PreCheckException exception =
                    assertThrows(PreCheckException.class, () -> checkMemo("hello world", MAX_MEMO_BYTES));
            assertEquals(MEMO_TOO_LONG, exception.responseCode());
        }

        @ParameterizedTest
        @ValueSource(strings = {"\0", "\0Hello World", "Hello \0 World", "Hello World\0"})
        void memoWithNullByteThrowsException(String input) {
            PreCheckException exception = assertThrows(PreCheckException.class, () -> checkMemo(input, 20));
            assertEquals(INVALID_ZERO_BYTE_IN_STRING, exception.responseCode());
        }
    }

    @Nested
    class CustomFeeValidation {
        private static final HederaFunctionality SUPPORTED_FUNC = CONSENSUS_SUBMIT_MESSAGE;
        private static final HederaFunctionality UNSUPPORTED_FUNC = CRYPTO_TRANSFER;
        private static final long SHARD = 1L;
        private static final long REALM = 2L;
        private static final AccountID PAYER_ID = accountId(SHARD, REALM, 1234L);
        private static final TokenID TOKEN_ID = tokenId(SHARD, REALM, 4321L);
        private static final List<FixedFee> FEES = List.of(new FixedFee(10, TOKEN_ID));

        @Test
        void unsupportedFuncWithFeesThrowsException() {
            List<CustomFeeLimit> fees = List.of(createValidFeeLimit());
            PreCheckException exception = assertThrows(
                    PreCheckException.class, () -> checkMaxCustomFees(fees, UNSUPPORTED_FUNC, SHARD, REALM));
            assertEquals(MAX_CUSTOM_FEES_IS_NOT_SUPPORTED, exception.responseCode());
        }

        @Test
        void unsupportedFuncWithEmptyFeesIsValid() {
            assertDoesNotThrow(() -> checkMaxCustomFees(List.of(), UNSUPPORTED_FUNC, SHARD, REALM));
        }

        @Test
        void nullAccountIdThrowsException() {
            CustomFeeLimit invalidFee = new CustomFeeLimit(null, List.of(new FixedFee(10, TOKEN_ID)));
            assertRejected(invalidFee);
        }

        @Test
        void emptyFeesListThrowsException() {
            assertRejected(customFeeLimitWith(List.of()));
        }

        @Test
        void negativeFeeAmountThrowsException() {
            assertRejected(customFeeLimitWith(List.of(new FixedFee(10, TOKEN_ID), new FixedFee(-1, TOKEN_ID))));
        }

        @Test
        void unsetAccountIdThrowsException() {
            assertRejected(new CustomFeeLimit(AccountID.DEFAULT, List.of(new FixedFee(10, TOKEN_ID))));
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void nonPositiveAccountNumThrowsException(final long accountNum) {
            assertRejected(
                    new CustomFeeLimit(accountId(SHARD, REALM, accountNum), List.of(new FixedFee(10, TOKEN_ID))));
        }

        @Test
        void aliasAccountIdIsValid() {
            final var limit = new CustomFeeLimit(aliasedAccountId(Bytes.wrap("0123456789012345678")), FEES);
            assertDoesNotThrow(() -> checkMaxCustomFees(List.of(limit), SUPPORTED_FUNC, SHARD, REALM));
        }

        @Test
        void emptyAliasThrowsException() {
            assertRejected(new CustomFeeLimit(aliasedAccountId(Bytes.EMPTY), FEES));
        }

        @Test
        void accountIdInAnotherShardOrRealmThrowsException() {
            assertRejected(new CustomFeeLimit(accountId(SHARD + 1, REALM, 1234L), List.of(new FixedFee(10, TOKEN_ID))));
            assertRejected(new CustomFeeLimit(accountId(SHARD, REALM + 1, 1234L), List.of(new FixedFee(10, TOKEN_ID))));
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void nonPositiveDenominatingTokenNumThrowsException(final long tokenNum) {
            assertRejected(customFeeLimitWith(List.of(new FixedFee(10, tokenId(SHARD, REALM, tokenNum)))));
        }

        @Test
        void unsetDenominatingTokenIdThrowsException() {
            assertRejected(customFeeLimitWith(List.of(new FixedFee(10, TokenID.DEFAULT))));
        }

        @Test
        void denominatingTokenIdInAnotherShardOrRealmThrowsException() {
            assertRejected(customFeeLimitWith(List.of(new FixedFee(10, tokenId(SHARD + 1, REALM, 4321L)))));
            assertRejected(customFeeLimitWith(List.of(new FixedFee(10, tokenId(SHARD, REALM + 1, 4321L)))));
        }

        @Test
        void entriesBeyondTheFirstAreAlsoValidated() {
            final var fees = List.of(
                    createValidFeeLimit(),
                    new CustomFeeLimit(accountId(SHARD, REALM, 4567L), List.of(new FixedFee(10, TokenID.DEFAULT))));
            PreCheckException exception =
                    assertThrows(PreCheckException.class, () -> checkMaxCustomFees(fees, SUPPORTED_FUNC, SHARD, REALM));
            assertEquals(INVALID_MAX_CUSTOM_FEES, exception.responseCode());
        }

        @Test
        void hbarLimitWithoutDenominatingTokenIsValid() {
            final var hbarLimit = new CustomFeeLimit(PAYER_ID, List.of(new FixedFee(10, null)));
            assertDoesNotThrow(() -> checkMaxCustomFees(List.of(hbarLimit), SUPPORTED_FUNC, SHARD, REALM));
        }

        @Test
        void validFeesForSupportedFunc() {
            assertDoesNotThrow(() -> checkMaxCustomFees(List.of(createValidFeeLimit()), SUPPORTED_FUNC, SHARD, REALM));
        }

        private void assertRejected(final CustomFeeLimit feeLimit) {
            PreCheckException exception = assertThrows(
                    PreCheckException.class, () -> checkMaxCustomFees(List.of(feeLimit), SUPPORTED_FUNC, SHARD, REALM));
            assertEquals(INVALID_MAX_CUSTOM_FEES, exception.responseCode());
        }

        private CustomFeeLimit createValidFeeLimit() {
            return customFeeLimitWith(List.of(new FixedFee(10, TOKEN_ID), new FixedFee(0, TOKEN_ID)));
        }

        private CustomFeeLimit customFeeLimitWith(List<FixedFee> feeLimits) {
            return new CustomFeeLimit(PAYER_ID, feeLimits);
        }

        private static AccountID aliasedAccountId(final Bytes alias) {
            return AccountID.newBuilder()
                    .shardNum(SHARD)
                    .realmNum(REALM)
                    .alias(alias)
                    .build();
        }

        private static AccountID accountId(final long shard, final long realm, final long num) {
            return AccountID.newBuilder()
                    .shardNum(shard)
                    .realmNum(realm)
                    .accountNum(num)
                    .build();
        }

        private static TokenID tokenId(final long shard, final long realm, final long num) {
            return TokenID.newBuilder()
                    .shardNum(shard)
                    .realmNum(realm)
                    .tokenNum(num)
                    .build();
        }
    }
}
