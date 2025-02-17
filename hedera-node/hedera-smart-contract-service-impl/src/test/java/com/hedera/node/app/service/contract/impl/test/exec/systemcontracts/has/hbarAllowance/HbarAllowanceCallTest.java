/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.hbarAllowance;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OPERATOR;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNAUTHORIZED_SPENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HbarAllowanceCallTest extends CallTestBase {
    private HbarAllowanceCall subject;

    @Mock
    private HasCallAttempt attempt;

    @Test
    void revertsWithNoOwner() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        subject = new HbarAllowanceCall(attempt, APPROVED_ID, UNAUTHORIZED_SPENDER_ID);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_ALLOWANCE_OWNER_ID), result.getOutput());
    }

    @Test
    void callHbarAllowance() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(nativeOperations.getAccount(B_NEW_ACCOUNT_ID)).willReturn(OPERATOR);
        subject = new HbarAllowanceCall(attempt, B_NEW_ACCOUNT_ID, UNAUTHORIZED_SPENDER_ID);

        final var result = subject.execute(frame).fullResult().result();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.getNumber(), BigInteger.valueOf(0L)))
                        .array()),
                result.getOutput());
    }
}
