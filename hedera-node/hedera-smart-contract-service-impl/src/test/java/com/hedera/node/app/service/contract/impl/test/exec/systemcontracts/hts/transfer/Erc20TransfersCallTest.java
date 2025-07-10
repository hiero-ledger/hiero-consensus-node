// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc20TransfersTranslator.ERC_20_TRANSFER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc20TransfersTranslator.ERC_20_TRANSFER_FROM;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ALIASED_RECEIVER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ACCOUNT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asBytesResult;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.readableRevertReason;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc20TransfersCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.SpecialRewardReceivers;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.service.token.ReadableAccountStore;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class Erc20TransfersCallTest extends CallTestBase {
    private static final Address FROM_ADDRESS = ConversionUtils.asHeadlongAddress(EIP_1014_ADDRESS.toArray());
    private static final Address TO_ADDRESS =
            ConversionUtils.asHeadlongAddress(asEvmAddress(B_NEW_ACCOUNT_ID.accountNumOrThrow()));

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private ReadableAccountStore readableAccountStore;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ContractCallStreamBuilder streamBuilder;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private SpecialRewardReceivers specialRewardReceivers;

    @Mock
    private ProxyWorldUpdater worldUpdater;

    private Erc20TransfersCall subject;

    @Test
    void revertsOnMissingToken() {
        subject = new Erc20TransfersCall(
                systemContractGasCalculator,
                mockEnhancement(),
                1234,
                null,
                TO_ADDRESS,
                null,
                verificationStrategy,
                SENDER_ID,
                addressIdConverter,
                false,
                specialRewardReceivers);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(Bytes.wrap(INVALID_TOKEN_ID.protoName().getBytes()), result.getOutput());
    }

    @Test
    void transferHappyPathSucceedsWithTrue() {
        givenSynthIdHelperWithoutFrom();
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(SENDER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(streamBuilder);
        given(streamBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);
        given(streamBuilder.contractCallResult(any())).willReturn(streamBuilder);
        given(nativeOperations.readableAccountStore()).willReturn(readableAccountStore);
        given(readableAccountStore.getAliasedAccountById(SENDER_ID)).willReturn(OWNER_ACCOUNT);
        given(readableAccountStore.getAliasedAccountById(B_NEW_ACCOUNT_ID)).willReturn(ALIASED_RECEIVER);

        subject = subjectForTransfer(1L);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(asBytesResult(ERC_20_TRANSFER.getOutputs().encode(Tuple.singleton(true))), result.getOutput());
    }

    @Test
    void transferFromHappyPathSucceedsWithTrue() {
        givenSynthIdHelperWithFrom();
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(SENDER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(streamBuilder);
        given(nativeOperations.readableAccountStore()).willReturn(readableAccountStore);
        given(readableAccountStore.getAliasedAccountById(A_NEW_ACCOUNT_ID)).willReturn(OWNER_ACCOUNT);
        given(readableAccountStore.getAliasedAccountById(B_NEW_ACCOUNT_ID)).willReturn(ALIASED_RECEIVER);
        given(streamBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);
        given(streamBuilder.contractCallResult(any())).willReturn(streamBuilder);

        subject = subjectForTransferFrom(1L);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                asBytesResult(ERC_20_TRANSFER_FROM.getOutputs().encode(Tuple.singleton(true))), result.getOutput());
    }

    @Test
    void unhappyPathRevertsWithReason() {
        givenSynthIdHelperWithoutFrom();
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(SENDER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(streamBuilder);
        given(streamBuilder.status()).willReturn(INSUFFICIENT_ACCOUNT_BALANCE);

        subject = subjectForTransfer(1L);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(readableRevertReason(INSUFFICIENT_ACCOUNT_BALANCE), result.getOutput());
    }

    private void givenSynthIdHelperWithFrom() {
        given(addressIdConverter.convert(FROM_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);
        given(addressIdConverter.convertCredit(TO_ADDRESS)).willReturn(B_NEW_ACCOUNT_ID);
    }

    private void givenSynthIdHelperWithoutFrom() {
        given(addressIdConverter.convertCredit(TO_ADDRESS)).willReturn(B_NEW_ACCOUNT_ID);
    }

    private Erc20TransfersCall subjectForTransfer(final long amount) {
        return new Erc20TransfersCall(
                systemContractGasCalculator,
                mockEnhancement(),
                amount,
                null,
                TO_ADDRESS,
                FUNGIBLE_TOKEN_ID,
                verificationStrategy,
                SENDER_ID,
                addressIdConverter,
                false,
                specialRewardReceivers);
    }

    private Erc20TransfersCall subjectForTransferFrom(final long amount) {
        return new Erc20TransfersCall(
                systemContractGasCalculator,
                mockEnhancement(),
                amount,
                FROM_ADDRESS,
                TO_ADDRESS,
                FUNGIBLE_TOKEN_ID,
                verificationStrategy,
                SENDER_ID,
                addressIdConverter,
                false,
                specialRewardReceivers);
    }
}
