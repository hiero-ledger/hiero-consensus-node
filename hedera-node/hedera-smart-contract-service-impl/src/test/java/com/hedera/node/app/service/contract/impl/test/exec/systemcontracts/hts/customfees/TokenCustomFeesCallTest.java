// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.customfees;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPECTED_FIXED_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPECTED_FRACTIONAL_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EXPECTED_ROYALTY_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_EVERYTHING_TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.customfees.TokenCustomFeesCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.customfees.TokenCustomFeesTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.util.Collections;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenCustomFeesCallTest extends CallTestBase {
    @Test
    void returnsTokenCustomFeesStatusForPresentToken() {
        final var subject = new TokenCustomFeesCall(gasCalculator, mockEnhancement(), false, FUNGIBLE_EVERYTHING_TOKEN);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenCustomFeesTranslator.TOKEN_CUSTOM_FEES
                        .getOutputs()
                        .encode(Tuple.of(
                                SUCCESS.protoOrdinal(),
                                EXPECTED_FIXED_CUSTOM_FEES.toArray(new Tuple[EXPECTED_FIXED_CUSTOM_FEES.size()]),
                                EXPECTED_FRACTIONAL_CUSTOM_FEES.toArray(
                                        new Tuple[EXPECTED_FRACTIONAL_CUSTOM_FEES.size()]),
                                EXPECTED_ROYALTY_CUSTOM_FEES.toArray(new Tuple[EXPECTED_ROYALTY_CUSTOM_FEES.size()])))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsTokenCustomFeesStatusForMissingToken() {
        final var subject = new TokenCustomFeesCall(gasCalculator, mockEnhancement(), false, null);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenCustomFeesTranslator.TOKEN_CUSTOM_FEES
                        .getOutputs()
                        .encode(Tuple.of(
                                INVALID_TOKEN_ID.protoOrdinal(),
                                Collections.emptyList().toArray(new Tuple[0]),
                                Collections.emptyList().toArray(new Tuple[0]),
                                Collections.emptyList().toArray(new Tuple[0])))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsTokenCustomFeesStatusForMissingTokenStaticCall() {
        final var subject = new TokenCustomFeesCall(gasCalculator, mockEnhancement(), true, null);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenCustomFeesTranslator.TOKEN_CUSTOM_FEES
                        .getOutputs()
                        .encode(Tuple.of(
                                SUCCESS.protoOrdinal(),
                                Collections.emptyList().toArray(new Tuple[0]),
                                Collections.emptyList().toArray(new Tuple[0]),
                                Collections.emptyList().toArray(new Tuple[0])))
                        .array()),
                result.getOutput());
    }
}
