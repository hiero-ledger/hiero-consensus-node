// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.node.app.hapi.utils.ValidationUtils.validateFalse;
import static com.hedera.node.app.hapi.utils.ValidationUtils.validateFalseOrRevert;
import static com.hedera.node.app.hapi.utils.ValidationUtils.validateTrue;
import static com.hedera.node.app.hapi.utils.ValidationUtils.validateTrueOrRevert;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_SYMBOL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_TOKEN_SYMBOL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

class ValidationUtilsTest {

    @Test
    void testValidateTrue() {
        final var falseExCapturedByCode =
                assertThrows(InvalidTransactionException.class, () -> validateTrue(false, MEMO_TOO_LONG));
        validateTrue(true, MEMO_TOO_LONG);
        final var falseExCapturedByCodeAndMsg = assertThrows(
                InvalidTransactionException.class,
                () -> validateTrue(false, INVALID_TOKEN_BURN_AMOUNT, "Should be true!"));
        validateTrue(true, INVALID_TOKEN_BURN_AMOUNT, "Should be true!");
        final var falseExCapturedbyCodeAndLambda = assertThrows(
                InvalidTransactionException.class,
                () -> validateTrue(false, INVALID_TOKEN_SYMBOL, () -> String.format("Should %s be true!", "also")));
        validateTrue(true, INVALID_TOKEN_SYMBOL, () -> String.format("Should %s be true!", "also"));

        assertEquals(MEMO_TOO_LONG, falseExCapturedByCode.getResponseCode());
        assertEquals(INVALID_TOKEN_BURN_AMOUNT, falseExCapturedByCodeAndMsg.getResponseCode());
        assertEquals("Should be true!", falseExCapturedByCodeAndMsg.getMessage());
        assertEquals(INVALID_TOKEN_SYMBOL, falseExCapturedbyCodeAndLambda.getResponseCode());
        assertEquals("Should also be true!", falseExCapturedbyCodeAndLambda.getMessage());
    }

    @Test
    void testValidateFalse() {
        final var trueExCapturedByCode = assertThrows(
                InvalidTransactionException.class, () -> validateFalse(true, CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT));
        validateFalse(false, CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT);
        final var trueExCapturedByCodeAndMsg = assertThrows(
                InvalidTransactionException.class,
                () -> validateFalse(true, TOKEN_HAS_NO_SUPPLY_KEY, "Should be false!"));
        validateFalse(false, TOKEN_HAS_NO_SUPPLY_KEY, "Should be false!");
        final var trueExCapturedbyCodeAndLambda = assertThrows(
                InvalidTransactionException.class,
                () -> validateFalse(true, MISSING_TOKEN_SYMBOL, () -> String.format("Should %s be false!", "also")));
        validateFalse(false, MISSING_TOKEN_SYMBOL, () -> String.format("Should %s be false!", "also"));

        assertEquals(CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT, trueExCapturedByCode.getResponseCode());
        assertEquals(TOKEN_HAS_NO_SUPPLY_KEY, trueExCapturedByCodeAndMsg.getResponseCode());
        assertEquals("Should be false!", trueExCapturedByCodeAndMsg.getMessage());
        assertEquals(MISSING_TOKEN_SYMBOL, trueExCapturedbyCodeAndLambda.getResponseCode());
        assertEquals("Should also be false!", trueExCapturedbyCodeAndLambda.getMessage());
    }

    @Test
    void validatesWithRevertingReason() {
        final var capturedEx = assertThrows(
                InvalidTransactionException.class, () -> validateTrueOrRevert(false, INVALID_ALLOWANCE_OWNER_ID));
        final var trueExCapturedByCode = assertThrows(
                InvalidTransactionException.class,
                () -> validateFalseOrRevert(true, CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT));
        validateFalseOrRevert(false, CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT);
        validateTrueOrRevert(true, INVALID_ALLOWANCE_OWNER_ID);
        assertTrue(capturedEx.isReverting());
        assertTrue(trueExCapturedByCode.isReverting());
        final var reason = Bytes.of(INVALID_ALLOWANCE_OWNER_ID.name().getBytes());
        assertEquals(reason, capturedEx.getRevertReason());
    }
}
