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
