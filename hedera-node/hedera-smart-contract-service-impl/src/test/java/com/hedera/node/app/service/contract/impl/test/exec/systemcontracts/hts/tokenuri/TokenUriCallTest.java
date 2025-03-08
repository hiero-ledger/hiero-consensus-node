// SPDX-License-Identifier: Apache-2.0
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

        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, NFT_SERIAL_NO)).willReturn(CIVILIAN_OWNED_NFT);

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
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN.tokenId(), NFT_SERIAL_NO))
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
