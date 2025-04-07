// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.ownerof;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ALIASED_SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CIVILIAN_OWNED_NFT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NFT_SERIAL_NO;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.TREASURY_OWNED_NFT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ordinalRevertOutputFor;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.hapi.utils.HederaExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ownerof.OwnerOfCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ownerof.OwnerOfTranslator;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;

class OwnerOfCallTest extends CallTestBase {

    private OwnerOfCall subject;

    @Test
    void revertsWithMissingToken() {
        subject = new OwnerOfCall(gasCalculator, mockEnhancement(), null, NFT_SERIAL_NO);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_ID), result.getOutput());
    }

    @Test
    void revertsWithMissingNft() {
        subject = new OwnerOfCall(gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, NFT_SERIAL_NO);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(ordinalRevertOutputFor(INVALID_TOKEN_NFT_SERIAL_NUMBER), result.getOutput());
    }

    @Test
    void haltWhenTokenIsNotERC721() {
        // given
        subject = new OwnerOfCall(gasCalculator, mockEnhancement(), FUNGIBLE_TOKEN, NFT_SERIAL_NO);

        // when
        final var result = subject.execute().fullResult().result();

        // then
        assertEquals(MessageFrame.State.EXCEPTIONAL_HALT, result.getState());
        assertEquals(
                HederaExceptionalHaltReason.ERROR_DECODING_PRECOMPILE_INPUT,
                result.getHaltReason().get());
    }

    @Test
    void revertsWithMissingOwner() {
        subject = new OwnerOfCall(gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, NFT_SERIAL_NO);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, NFT_SERIAL_NO)).willReturn(CIVILIAN_OWNED_NFT);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_ACCOUNT_ID), result.getOutput());
    }

    @Test
    void returnsUnaliasedOwnerLongZeroForPresentTokenAndNonTreasuryNft() {
        subject = new OwnerOfCall(gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, NFT_SERIAL_NO);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, NFT_SERIAL_NO)).willReturn(CIVILIAN_OWNED_NFT);
        final long ownerNum = CIVILIAN_OWNED_NFT.ownerIdOrThrow().accountNumOrThrow();
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(TestHelpers.SOMEBODY);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(OwnerOfTranslator.OWNER_OF
                        .getOutputs()
                        .encode(Tuple.singleton(asHeadlongAddress(asLongZeroAddress(entityIdFactory, ownerNum))))
                        .array()),
                result.getOutput());
    }

    @Test
    void returnsAliasedOwnerLongZeroForPresentTokenAndTreasuryNft() {
        subject = new OwnerOfCall(gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, NFT_SERIAL_NO);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, NFT_SERIAL_NO)).willReturn(TREASURY_OWNED_NFT);
        final long ownerNum = NON_FUNGIBLE_TOKEN.treasuryAccountIdOrThrow().accountNumOrThrow();
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(TestHelpers.ALIASED_SOMEBODY);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(OwnerOfTranslator.OWNER_OF
                        .getOutputs()
                        .encode(Tuple.singleton(
                                asHeadlongAddress(ALIASED_SOMEBODY.alias().toByteArray())))
                        .array()),
                result.getOutput());
    }
}
