// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.address_0x167.tokentype;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokentype.TokenTypeCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokentype.address_0x167.TokenTypeTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenTypeCallTest extends CallTestBase {
    @Test
    void returnsTokenTypeForPresentToken() {
        final var subject = new TokenTypeCall(gasCalculator, mockEnhancement(), false, FUNGIBLE_TOKEN);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenTypeTranslator.TOKEN_TYPE
                        .getOutputs()
                        .encode(Tuple.of(SUCCESS.protoOrdinal(), 0))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsTokenTypeForMissingToken() {
        final var subject = new TokenTypeCall(gasCalculator, mockEnhancement(), false, null);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenTypeTranslator.TOKEN_TYPE
                        .getOutputs()
                        .encode(Tuple.of(INVALID_TOKEN_ID.protoOrdinal(), 0))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsTokenTypeForMissingTokenStaticCall() {
        final var subject = new TokenTypeCall(gasCalculator, mockEnhancement(), true, null);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_ID), result.getOutput());
    }
}
