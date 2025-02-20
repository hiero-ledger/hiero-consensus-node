// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.utils;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.service.contract.impl.utils.SystemContractUtils.HTS_PRECOMPILE_MIRROR_ID;
import static com.hedera.node.app.service.contract.impl.utils.SystemContractUtils.contractFunctionResultFailedFor;
import static com.hedera.node.app.service.contract.impl.utils.SystemContractUtils.successResultOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.utils.SystemContractUtils;
import java.nio.ByteBuffer;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemContractUtilsTest {
    private static final long gasUsed = 0L;
    private static final Bytes result = Bytes.EMPTY;
    private static final com.hedera.pbj.runtime.io.buffer.Bytes contractCallResult =
            com.hedera.pbj.runtime.io.buffer.Bytes.wrap("Contract Call Result");
    private static final ContractID contractID =
            ContractID.newBuilder().contractNum(111).build();
    private static final String errorMessage = FAIL_INVALID.name();

    @Mock
    private MessageFrame frame;

    @Test
    void includesAllFieldsWithTraceabilityOn() {
        final var gasRemaining = 100L;
        final var gasRequirement = 200L;
        final var value = 123L;
        final var outputData = org.apache.tuweni.bytes.Bytes.wrap(new byte[] {(byte) 0x01, (byte) 0x02});
        final var inputData = org.apache.tuweni.bytes.Bytes.wrap(new byte[] {(byte) 0x03, (byte) 0x04});
        final var senderId = AccountID.newBuilder().build();
        final var result = FullResult.successResult(ByteBuffer.wrap(outputData.toArray()), gasRequirement);

        final var expected = ContractFunctionResult.newBuilder()
                .gasUsed(gasRequirement)
                .contractCallResult(tuweniToPbjBytes(result.output()))
                .contractID(HTS_PRECOMPILE_MIRROR_ID)
                .gas(gasRemaining)
                .amount(value)
                .senderId(senderId)
                .functionParameters(tuweniToPbjBytes(inputData))
                .build();
        given(frame.getValue()).willReturn(Wei.of(value));
        given(frame.getRemainingGas()).willReturn(gasRemaining);
        given(frame.getInputData()).willReturn(inputData);

        final var actual = successResultOf(senderId, result, frame, true);

        assertEquals(expected, actual);
    }

    @Test
    void validateSuccessfulContractResults() {
        final var gas = 100L;
        final var inputData = org.apache.tuweni.bytes.Bytes.EMPTY;
        final var senderId = AccountID.newBuilder().build();

        final var expected = ContractFunctionResult.newBuilder()
                .gasUsed(gasUsed)
                .contractCallResult(tuweniToPbjBytes(result))
                .contractID(HTS_PRECOMPILE_MIRROR_ID)
                .gas(gas)
                .senderId(senderId)
                .functionParameters(tuweniToPbjBytes(inputData))
                .build();
        final var actual =
                SystemContractUtils.successResultOfZeroValueTraceable(gasUsed, result, gas, inputData, senderId);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void validateFailedContractResults() {
        final var fullResult = FullResult.revertResult(FAIL_INVALID, gasUsed);
        final var expected = ContractFunctionResult.newBuilder()
                .senderId(SENDER_ID)
                .gasUsed(gasUsed)
                .errorMessage(errorMessage)
                .contractCallResult(tuweniToPbjBytes(fullResult.result().getOutput()))
                .contractID(contractID)
                .build();
        final var actual = contractFunctionResultFailedFor(SENDER_ID, fullResult, errorMessage, contractID);
        assertThat(actual).isEqualTo(expected);
    }
}
