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

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.tokenexpiry;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.ZERO_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_EVERYTHING_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
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
