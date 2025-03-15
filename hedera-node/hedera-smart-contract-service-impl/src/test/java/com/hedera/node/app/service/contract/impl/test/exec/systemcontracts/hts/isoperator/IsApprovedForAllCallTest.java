// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.isoperator;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OPERATOR;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.realm;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.shard;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isapprovedforall.IsApprovedForAllCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isapprovedforall.IsApprovedForAllTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;

class IsApprovedForAllCallTest extends CallTestBase {
    private final Address THE_OWNER =
            asHeadlongAddress(asEvmAddress(shard, realm, A_NEW_ACCOUNT_ID.accountNumOrThrow()));
    private final Address THE_OPERATOR =
            asHeadlongAddress(asEvmAddress(shard, realm, B_NEW_ACCOUNT_ID.accountNumOrThrow()));
    private IsApprovedForAllCall subject;

    @Test
    void revertsWithFungibleToken() {
        subject = new IsApprovedForAllCall(
                gasCalculator, mockEnhancement(), FUNGIBLE_TOKEN, THE_OWNER, THE_OPERATOR, false);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_TOKEN_ID), result.getOutput());
    }

    @Test
    void checksForPresentOwnerAndFindsApprovedOperator() {
        mockEntityIdFactory();
        subject = new IsApprovedForAllCall(
                gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, THE_OWNER, THE_OPERATOR, false);
        given(nativeOperations.getAccount(entityIdFactory.newAccountId(A_NEW_ACCOUNT_ID.accountNumOrThrow())))
                .willReturn(SOMEBODY);
        given(nativeOperations.getAccount(entityIdFactory.newAccountId(B_NEW_ACCOUNT_ID.accountNumOrThrow())))
                .willReturn(OPERATOR);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertHasSuccessVerdict(true, result.getOutput());
    }

    @Test
    void checksForPresentOwnerAndDetectsNoOperator() {
        mockEntityIdFactory();
        subject = new IsApprovedForAllCall(
                gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, THE_OWNER, THE_OWNER, false);
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(SOMEBODY);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertHasSuccessVerdict(false, result.getOutput());
    }

    @Test
    void ercChecksForPresentOwnerAndDetectsNoOperator() {
        mockEntityIdFactory();
        subject = new IsApprovedForAllCall(
                gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, THE_OWNER, THE_OWNER, true);
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(SOMEBODY);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        final boolean verdict = IsApprovedForAllTranslator.ERC_IS_APPROVED_FOR_ALL
                .getOutputs()
                .decode(result.getOutput().toArray())
                .get(0);
        assertFalse(verdict);
    }

    @Test
    void returnsFalseForPresentOwnerAndMissingOperator() {
        mockEntityIdFactory();
        subject = new IsApprovedForAllCall(
                gasCalculator, mockEnhancement(), NON_FUNGIBLE_TOKEN, THE_OWNER, THE_OPERATOR, false);
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(SOMEBODY);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var result = subject.execute().fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertHasSuccessVerdict(false, result.getOutput());
    }

    private void assertHasSuccessVerdict(final boolean verdict, @NonNull final Bytes output) {
        final var outputs = IsApprovedForAllTranslator.CLASSIC_IS_APPROVED_FOR_ALL
                .getOutputs()
                .decode(output.toArray());
        assertEquals(SUCCESS.protoOrdinal(), (long) outputs.get(0));
        if (verdict) {
            assertTrue((boolean) outputs.get(1));
        } else {
            assertFalse((boolean) outputs.get(1));
        }
    }
}
