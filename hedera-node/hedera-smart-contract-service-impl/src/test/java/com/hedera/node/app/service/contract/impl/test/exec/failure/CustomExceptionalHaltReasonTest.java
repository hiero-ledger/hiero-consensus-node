// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.failure;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.CONTRACT_ENTITY_LIMIT_REACHED;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_ALIAS_KEY;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SIGNATURE;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.SELF_DESTRUCT_TO_SELF;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.statusFor;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.failure.HandleExceptionHaltReason;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.junit.jupiter.api.Test;

class CustomExceptionalHaltReasonTest {
    @Test
    void translatesSameStatusesAsMonoService() {
        assertEquals(ResponseCodeEnum.OBTAINER_SAME_CONTRACT_ID, statusFor(SELF_DESTRUCT_TO_SELF));
        assertEquals(ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS, statusFor(INVALID_SOLIDITY_ADDRESS));
        assertEquals(ResponseCodeEnum.INVALID_ALIAS_KEY, statusFor(INVALID_ALIAS_KEY));
        assertEquals(
                ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED,
                statusFor(CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS));

        assertEquals(ResponseCodeEnum.INVALID_SIGNATURE, statusFor(INVALID_SIGNATURE));
        assertEquals(ResponseCodeEnum.INSUFFICIENT_GAS, statusFor(ExceptionalHaltReason.INSUFFICIENT_GAS));
        assertEquals(
                ResponseCodeEnum.LOCAL_CALL_MODIFICATION_EXCEPTION,
                statusFor(ExceptionalHaltReason.ILLEGAL_STATE_CHANGE));
        assertEquals(
                ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED,
                statusFor(CONTRACT_ENTITY_LIMIT_REACHED));
        assertEquals(ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION, statusFor(ExceptionalHaltReason.PRECOMPILE_ERROR));
    }

    @Test
    void usesToStringForErrorMessages() {
        assertEquals("INVALID_SOLIDITY_ADDRESS", CustomExceptionalHaltReason.errorMessageFor(INVALID_SOLIDITY_ADDRESS));
    }

    @Test
    void preservesStatusOfHandleExceptionHaltReason() {
        final var reason = new HandleExceptionHaltReason(ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE);
        assertEquals(ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE, statusFor(reason));
        assertEquals("INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE", reason.name());
    }

    @Test
    void usesHexEncodedStatusNameAsErrorMessageForHandleExceptionHaltReason() {
        final var reason = new HandleExceptionHaltReason(ResponseCodeEnum.INVALID_SIGNATURE);
        assertEquals(
                Bytes.of(ResponseCodeEnum.INVALID_SIGNATURE.name().getBytes()).toHexString(),
                CustomExceptionalHaltReason.errorMessageFor(reason));
    }
}
