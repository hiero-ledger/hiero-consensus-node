// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.getapproved;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CIVILIAN_OWNED_NFT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OPERATOR;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.getapproved.GetApprovedCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.getapproved.GetApprovedTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for get approved calls.
 */
public class GetApprovedCallTest extends CallTestBase {

    private GetApprovedCall subject;

    @Test
    void revertsWithFungibleTokenStaticCall() {
        subject = new GetApprovedCall(gasCalculator, mockEnhancement(), FUNGIBLE_TOKEN, 123L, true, true);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_ID), result.getOutput());
    }

    @Test
    void revertsWithFungibleToken() {
        subject = new GetApprovedCall(gasCalculator, mockEnhancement(), FUNGIBLE_TOKEN, 123L, true, false);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_NFT_SERIAL_NUMBER), result.getOutput());
    }

    @Test
    void successGetApprovedErc() {
        subject = new GetApprovedCall(gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, 123L, true, false);

        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, 123)).willReturn(CIVILIAN_OWNED_NFT);
        given(nativeOperations.getAccount(B_NEW_ACCOUNT_ID)).willReturn(OPERATOR);

        final var result = subject.execute().fullResult().result();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(GetApprovedTranslator.ERC_GET_APPROVED
                        .getOutputs()
                        .encode(Tuple.singleton(headlongAddressOf(OPERATOR)))
                        .array()),
                result.getOutput());
    }

    @Test
    void successGetApprovedErcNegativeSerial() {
        subject = new GetApprovedCall(gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, -1, true, false);

        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, -1)).willReturn(null);

        final var result = subject.execute().fullResult().result();
        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_NFT_SERIAL_NUMBER), result.getOutput());
    }

    @Test
    void successGetApprovedHapi() {
        subject = new GetApprovedCall(gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, 123L, false, false);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, 123)).willReturn(CIVILIAN_OWNED_NFT);
        given(nativeOperations.getAccount(B_NEW_ACCOUNT_ID)).willReturn(OPERATOR);

        final var result = subject.execute().fullResult().result();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(GetApprovedTranslator.HAPI_GET_APPROVED
                        .getOutputs()
                        .encode(Tuple.of(SUCCESS.getNumber(), headlongAddressOf(OPERATOR)))
                        .array()),
                result.getOutput());
    }

    @Test
    void successGetApprovedHapiNegativeSerial() {
        subject = new GetApprovedCall(gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, -1, false, false);

        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, -1)).willReturn(null);

        final var result = subject.execute().fullResult().result();
        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_NFT_SERIAL_NUMBER), result.getOutput());
    }
}
