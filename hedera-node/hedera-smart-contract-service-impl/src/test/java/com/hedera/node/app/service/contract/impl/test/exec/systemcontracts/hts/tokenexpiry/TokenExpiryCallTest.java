// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.tokenexpiry;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_EVERYTHING_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenexpiry.TokenExpiryCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenexpiry.TokenExpiryTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenExpiryCallTest extends CallTestBase {
    @Test
    void returnsValidTokenExpiryStatusForPresentToken() {
        final var subject = new TokenExpiryCall(gasCalculator, mockEnhancement(), false, FUNGIBLE_EVERYTHING_TOKEN);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenExpiryTranslator.TOKEN_EXPIRY
                        .getOutputs()
                        .encode(Tuple.of(SUCCESS.protoOrdinal(), Tuple.of(100L, headlongAddressOf(SENDER_ID), 200L)))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsTokenExpiryStatusForMissingToken() {
        final var subject = new TokenExpiryCall(gasCalculator, mockEnhancement(), false, null);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenExpiryTranslator.TOKEN_EXPIRY
                        .getOutputs()
                        .encode(Tuple.of(
                                INVALID_TOKEN_ID.protoOrdinal(), Tuple.of(0L, headlongAddressOf(ZERO_ACCOUNT_ID), 0L)))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsTokenExpiryStatusForMissingTokenStaticCall() {
        final var subject = new TokenExpiryCall(gasCalculator, mockEnhancement(), true, null);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_ID), result.getOutput());
    }
}
