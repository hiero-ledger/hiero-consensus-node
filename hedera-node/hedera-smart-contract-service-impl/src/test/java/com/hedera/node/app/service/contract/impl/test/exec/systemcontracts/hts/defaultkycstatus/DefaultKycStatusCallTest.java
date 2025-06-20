// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.defaultkycstatus;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_EVERYTHING_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.defaultkycstatus.DefaultKycStatusCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.defaultkycstatus.DefaultKycStatusTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultKycStatusCallTest extends CallTestBase {
    @Test
    void returnsDefaultKycStatusForPresentToken() {
        final var subject = new DefaultKycStatusCall(gasCalculator, mockEnhancement(), false, FUNGIBLE_TOKEN);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(DefaultKycStatusTranslator.DEFAULT_KYC_STATUS
                        .getOutputs()
                        .encode(Tuple.of(SUCCESS.protoOrdinal(), true))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsDefaultKycStatusForPresentTokenWithKey() {
        final var token = FUNGIBLE_EVERYTHING_TOKEN
                .copyBuilder()
                .accountsKycGrantedByDefault(false)
                .build();
        final var subject = new DefaultKycStatusCall(gasCalculator, mockEnhancement(), false, token);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(DefaultKycStatusTranslator.DEFAULT_KYC_STATUS
                        .getOutputs()
                        .encode(Tuple.of(SUCCESS.protoOrdinal(), false))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsDefaultKycStatusForMissingToken() {
        final var subject = new DefaultKycStatusCall(gasCalculator, mockEnhancement(), false, null);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(DefaultKycStatusTranslator.DEFAULT_KYC_STATUS
                        .getOutputs()
                        .encode(Tuple.of(INVALID_TOKEN_ID.protoOrdinal(), false))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsDefaultKycStatusForMissingTokenStaticCall() {
        final var subject = new DefaultKycStatusCall(gasCalculator, mockEnhancement(), true, null);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_ID), result.getOutput());
    }
}
