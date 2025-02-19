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
