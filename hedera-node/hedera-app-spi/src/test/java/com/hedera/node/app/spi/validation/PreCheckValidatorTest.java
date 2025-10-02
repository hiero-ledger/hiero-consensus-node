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

        @Test
        void unsupportedFuncWithFeesThrowsException() {
            List<CustomFeeLimit> fees = List.of(createValidFeeLimit());
            PreCheckException exception =
                    assertThrows(PreCheckException.class, () -> checkMaxCustomFees(fees, UNSUPPORTED_FUNC));
            assertEquals(MAX_CUSTOM_FEES_IS_NOT_SUPPORTED, exception.responseCode());
        }

        @Test
        void unsupportedFuncWithEmptyFeesIsValid() {
            assertDoesNotThrow(() -> checkMaxCustomFees(List.of(), UNSUPPORTED_FUNC));
        }

        @Test
        void nullAccountIdThrowsException() {
            CustomFeeLimit invalidFee = new CustomFeeLimit(null, List.of(new FixedFee(10, TokenID.DEFAULT)));
            PreCheckException exception = assertThrows(
                    PreCheckException.class, () -> checkMaxCustomFees(List.of(invalidFee), SUPPORTED_FUNC));
            assertEquals(INVALID_MAX_CUSTOM_FEES, exception.responseCode());
        }

        @Test
        void emptyFeesListThrowsException() {
            CustomFeeLimit invalidFee = customFeeLimitWith(List.of());
            PreCheckException exception = assertThrows(
                    PreCheckException.class, () -> checkMaxCustomFees(List.of(invalidFee), SUPPORTED_FUNC));
            assertEquals(INVALID_MAX_CUSTOM_FEES, exception.responseCode());
        }

        @Test
        void negativeFeeAmountThrowsException() {
            CustomFeeLimit invalidFee =
                    customFeeLimitWith(List.of(new FixedFee(10, TokenID.DEFAULT), new FixedFee(-1, TokenID.DEFAULT)));
            PreCheckException exception = assertThrows(
                    PreCheckException.class, () -> checkMaxCustomFees(List.of(invalidFee), SUPPORTED_FUNC));
            assertEquals(INVALID_MAX_CUSTOM_FEES, exception.responseCode());
        }

        @Test
        void validFeesForSupportedFunc() {
            assertDoesNotThrow(() -> checkMaxCustomFees(List.of(createValidFeeLimit()), SUPPORTED_FUNC));
        }

        private CustomFeeLimit createValidFeeLimit() {
            return customFeeLimitWith(List.of(new FixedFee(10, TokenID.DEFAULT), new FixedFee(0, TokenID.DEFAULT)));
        }

        private CustomFeeLimit customFeeLimitWith(List<FixedFee> feeLimits) {
            return new CustomFeeLimit(AccountID.DEFAULT, feeLimits);
        }
    }
}
