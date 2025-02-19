/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.setunlimitedautoassociations;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.setunlimitedautoassociations.SetUnlimitedAutoAssociationsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.setunlimitedautoassociations.SetUnlimitedAutoAssociationsTranslator;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SetUnlimitedAutoAssociationsCallTest extends CallTestBase {

    @Mock
    private HasCallAttempt attempt;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    private SetUnlimitedAutoAssociationsCall subject;

    @Test
    void successCall() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);

        subject = new SetUnlimitedAutoAssociationsCall(attempt, transactionBody);
        final var result = subject.execute(frame).fullResult().result();
        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(SetUnlimitedAutoAssociationsTranslator.SET_UNLIMITED_AUTO_ASSOC
                        .getOutputs()
                        .encode(Tuple.singleton((long) SUCCESS.getNumber()))
                        .array()),
                result.getOutput());
    }

    @Test
    void revertCall() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.REVERTED_SUCCESS);

        subject = new SetUnlimitedAutoAssociationsCall(attempt, transactionBody);
        final var result = subject.execute(frame).fullResult().result();
        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(SetUnlimitedAutoAssociationsTranslator.SET_UNLIMITED_AUTO_ASSOC
                        .getOutputs()
                        .encode(Tuple.singleton((long) REVERTED_SUCCESS.getNumber()))
                        .array()),
                result.getOutput());
    }
}
