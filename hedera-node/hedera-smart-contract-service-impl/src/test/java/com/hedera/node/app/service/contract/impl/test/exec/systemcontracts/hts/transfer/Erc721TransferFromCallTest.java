// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RECEIVER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.convertAccountToLog;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.readableRevertReason;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc721TransferFromCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.SpecialRewardReceivers;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.service.token.ReadableAccountStore;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.log.Log;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

class Erc721TransferFromCallTest extends CallTestBase {
    private static final Address FROM_ADDRESS = ConversionUtils.asHeadlongAddress(EIP_1014_ADDRESS.toArray());
    private static final Address TO_ADDRESS =
            ConversionUtils.asHeadlongAddress(asEvmAddress(B_NEW_ACCOUNT_ID.accountNumOrThrow()));

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private SpecialRewardReceivers specialRewardReceivers;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    private Erc721TransferFromCall subject;

    @Test
    void happyPathSucceedsWithEmptyBytes() {
        givenSynthIdHelperForToAccount();
        given(nativeOperations.readableAccountStore()).willReturn(accountStore);
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(SENDER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);
        given(addressIdConverter.convert(FROM_ADDRESS)).willReturn(SENDER_ID);
        given(addressIdConverter.convertCredit(TO_ADDRESS)).willReturn(RECEIVER_ID);
        final var localSender = Account.newBuilder().accountId(SENDER_ID).build();
        given(accountStore.getAliasedAccountById(SENDER_ID)).willReturn(localSender);
        final var localReceiver = Account.newBuilder().accountId(RECEIVER_ID).build();
        given(accountStore.getAliasedAccountById(RECEIVER_ID)).willReturn(localReceiver);
        final List<Log> logs = new ArrayList<>();
        Mockito.doAnswer(e -> logs.add(e.getArgument(0))).when(frame).addLog(any());

        subject = subjectFor(1L);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(Bytes.EMPTY, result.getOutput());
        // check that events was added
        assertEquals(1, logs.size());
        assertEquals(4, logs.getFirst().getTopics().size());
        assertEquals(
                convertAccountToLog(localSender), logs.getFirst().getTopics().get(1));
        assertEquals(
                convertAccountToLog(localReceiver), logs.getFirst().getTopics().get(2));
        assertEquals(
                1L,
                UInt256.fromBytes(Bytes.wrap(logs.getFirst().getTopics().get(3).toArray()))
                        .toLong());
    }

    @Test
    void unhappyPathRevertsWithReason() {
        givenSynthIdHelperForToAccount();
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(SENDER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO);

        subject = subjectFor(1L);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(readableRevertReason(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO), result.getOutput());
    }

    private void givenSynthIdHelperForToAccount() {
        given(addressIdConverter.convertCredit(TO_ADDRESS)).willReturn(B_NEW_ACCOUNT_ID);
    }

    private Erc721TransferFromCall subjectFor(final long serialNo) {
        return new Erc721TransferFromCall(
                serialNo,
                FROM_ADDRESS,
                TO_ADDRESS,
                NON_FUNGIBLE_TOKEN_ID,
                verificationStrategy,
                mockEnhancement(),
                gasCalculator,
                SENDER_ID,
                addressIdConverter,
                specialRewardReceivers);
    }
}
