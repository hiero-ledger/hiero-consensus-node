// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.istoken;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.iskyc.IsKycTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.istoken.IsTokenCall;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IsTokenCallTest extends CallTestBase {
    @Test
    void returnsIsTokenForPresentToken() {
        final var subject = new IsTokenCall(gasCalculator, mockEnhancement(), false, FUNGIBLE_TOKEN);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(IsKycTranslator.IS_KYC
                        .getOutputs()
                        .encode(Tuple.of(SUCCESS.protoOrdinal(), true))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsIsTokenForMissingToken() {
        final var subject = new IsTokenCall(gasCalculator, mockEnhancement(), false, null);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(IsKycTranslator.IS_KYC
                        .getOutputs()
                        .encode(Tuple.of(SUCCESS.protoOrdinal(), false))
                        .array()),
                result.getOutput());
    }
}
