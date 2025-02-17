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
