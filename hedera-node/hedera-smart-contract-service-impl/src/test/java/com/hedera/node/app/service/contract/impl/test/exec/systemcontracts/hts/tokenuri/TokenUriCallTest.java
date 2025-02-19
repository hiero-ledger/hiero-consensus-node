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

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.tokenuri;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CIVILIAN_OWNED_NFT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NFT_SERIAL_NO;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenuri.TokenUriCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenuri.TokenUriTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;

class TokenUriCallTest extends CallTestBase {
    private TokenUriCall subject;

    @Test
    void returnsUnaliasedOwnerLongZeroForPresentTokenAndNonTreasuryNft() {
        subject = new TokenUriCall(gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, NFT_SERIAL_NO);

        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID.tokenNum(), NFT_SERIAL_NO))
                .willReturn(CIVILIAN_OWNED_NFT);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenUriTranslator.TOKEN_URI
                        .getOutputs()
                        .encode(Tuple.singleton(
                                new String(CIVILIAN_OWNED_NFT.metadata().toByteArray())))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnNonExistingTokenErrorMetadata() {
        // given
        subject = new TokenUriCall(gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, NFT_SERIAL_NO);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN.tokenId().tokenNum(), NFT_SERIAL_NO))
                .willReturn(null);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(TokenUriTranslator.TOKEN_URI
                        .getOutputs()
                        .encode(Tuple.singleton(TokenUriCall.URI_QUERY_NON_EXISTING_TOKEN_ERROR))
                        .array()),
                result.getOutput());
    }
}
