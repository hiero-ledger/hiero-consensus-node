// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.grantapproval;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_SPENDER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NEGATIVE_ALLOWANCE_AMOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NFT_SERIAL_NO;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OPERATOR_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.REVOKE_APPROVAL_SPENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNAUTHORIZED_SPENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asBytesResult;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ordinalRevertOutputFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.ERCGrantApprovalCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.GrantApprovalTranslator;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.AssertMessages;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.service.token.ReadableAccountStore;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito.BDDMyOngoingStubbing;
import org.mockito.Mock;

class ERCGrantApprovalCallTest extends CallTestBase {
    private static final Account NFT_OWNER =
            Account.newBuilder().accountId(OWNER_ID).alias(OWNER_ADDRESS).build();
    private static final Account NFT_SPENDER =
            Account.newBuilder().accountId(APPROVED_ID).build();
    private static final Account OPERATOR =
            Account.newBuilder().accountId(OPERATOR_ACCOUNT_ID).build();
    private static final Account TREASURY =
            Account.newBuilder().accountId(NON_SYSTEM_ACCOUNT_ID).build();
    private static final Nft OWNED_NFT = Nft.newBuilder().ownerId(OWNER_ID).build();

    private ERCGrantApprovalCall subject;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private Nft nft;

    @Mock
    private Account account;

    @Mock
    private MessageFrame frame;

    @Mock
    private ReadableAccountStore accountStore;

    private void prepareErc20approve(final long amount, final ResponseCodeEnum status) {
        subject = new ERCGrantApprovalCall(
                mockEnhancement(),
                systemContractGasCalculator,
                verificationStrategy,
                OWNER_ID,
                FUNGIBLE_TOKEN_ID,
                UNAUTHORIZED_SPENDER_ID,
                amount,
                TokenType.FUNGIBLE_COMMON);
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(OWNER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(status);
        if (status == SUCCESS) {
            given(nativeOperations.readableAccountStore()).willReturn(accountStore);
            given(accountStore.getAccountById(any(AccountID.class))).willReturn(account);
            given(account.accountIdOrThrow())
                    .willReturn(AccountID.newBuilder().accountNum(1).build());
            given(account.alias()).willReturn(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[] {1, 2, 3}));
        }
    }

    @Test
    void erc20approve() {
        // given
        prepareErc20approve(100L, SUCCESS);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state(), AssertMessages.STATUS);
        assertEquals(
                asBytesResult(
                        GrantApprovalTranslator.ERC_GRANT_APPROVAL.getOutputs().encode(Tuple.singleton(true))),
                result.output(),
                AssertMessages.OUTPUT);
    }

    @Test
    void erc20approveNegativeAmount() {
        // given
        prepareErc20approve(-1, NEGATIVE_ALLOWANCE_AMOUNT);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(MessageFrame.State.REVERT, result.state(), AssertMessages.STATUS);
        assertEquals(ordinalRevertOutputFor(NEGATIVE_ALLOWANCE_AMOUNT), result.output(), AssertMessages.OUTPUT);
    }

    private void prepareErc721approve(
            final AccountID spenderId, final long serial, final ResponseCodeEnum... statuses) {
        subject = new ERCGrantApprovalCall(
                mockEnhancement(),
                systemContractGasCalculator,
                verificationStrategy,
                OWNER_ID,
                NON_FUNGIBLE_TOKEN_ID,
                spenderId,
                serial,
                TokenType.NON_FUNGIBLE_UNIQUE);
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(OWNER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        BDDMyOngoingStubbing<ResponseCodeEnum> given = given(recordBuilder.status());
        for (final ResponseCodeEnum status : statuses) {
            given = given.willReturn(status);
        }
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, serial)).willReturn(nft);
        if (Set.of(statuses).contains(SUCCESS)) {
            given(nativeOperations.readableAccountStore()).willReturn(accountStore);
            given(accountStore.getAccountById(any(AccountID.class))).willReturn(account);
            given(account.accountIdOrThrow())
                    .willReturn(AccountID.newBuilder().accountNum(1).build());
            given(account.alias()).willReturn(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[] {1, 2, 3}));
        }
    }

    @Test
    void prepareErc721approve() {
        // given
        prepareErc721approve(UNAUTHORIZED_SPENDER_ID, 100L, SUCCESS);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state(), AssertMessages.STATUS);
        assertEquals(
                asBytesResult(GrantApprovalTranslator.ERC_GRANT_APPROVAL_NFT
                        .getOutputs()
                        .encode(Tuple.EMPTY)),
                result.output(),
                AssertMessages.OUTPUT);
    }

    @Test
    void prepareErc721approveNegativeSerial() {
        // given
        prepareErc721approve(UNAUTHORIZED_SPENDER_ID, -1, INVALID_TOKEN_NFT_SERIAL_NUMBER);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(State.REVERT, result.state(), AssertMessages.STATUS);
        assertEquals(ordinalRevertOutputFor(INVALID_TOKEN_NFT_SERIAL_NUMBER), result.output(), AssertMessages.OUTPUT);
    }

    @Test
    void erc721approveFailsWithInvalidSpenderAllowance() {
        // given
        prepareErc721approve(UNAUTHORIZED_SPENDER_ID, 100L, INVALID_ALLOWANCE_SPENDER_ID);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(State.REVERT, result.state(), AssertMessages.STATUS);
        assertEquals(ordinalRevertOutputFor(INVALID_ALLOWANCE_SPENDER_ID), result.output(), AssertMessages.OUTPUT);
    }

    @Test
    void erc721approveFailsWithSenderDoesNotOwnNFTSerialNumber() {
        // given
        prepareErc721approve(
                UNAUTHORIZED_SPENDER_ID,
                100L,
                DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL,
                SENDER_DOES_NOT_OWN_NFT_SERIAL_NO);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(State.REVERT, result.state(), AssertMessages.STATUS);
        assertEquals(ordinalRevertOutputFor(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO), result.output(), AssertMessages.OUTPUT);
        verify(recordBuilder).status(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO);
    }

    @Test
    void erc721approveFailsWithInvalidTokenNFTSerialNumber() {
        // given
        prepareErc721approve(UNAUTHORIZED_SPENDER_ID, 100L, INVALID_TOKEN_NFT_SERIAL_NUMBER);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(State.REVERT, result.state(), AssertMessages.STATUS);
        assertEquals(ordinalRevertOutputFor(INVALID_TOKEN_NFT_SERIAL_NUMBER), result.output(), AssertMessages.OUTPUT);
    }

    @Test
    void erc721revoke() {
        // given
        prepareErc721approve(REVOKE_APPROVAL_SPENDER_ID, 100L, SUCCESS);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state(), AssertMessages.STATUS);
        assertEquals(
                asBytesResult(GrantApprovalTranslator.ERC_GRANT_APPROVAL_NFT
                        .getOutputs()
                        .encode(Tuple.EMPTY)),
                result.output(),
                AssertMessages.OUTPUT);
    }

    private void prepareErc721approveBy(final AccountID senderId, final AccountID spenderId, final Nft nft) {
        subject = new ERCGrantApprovalCall(
                mockEnhancement(),
                systemContractGasCalculator,
                verificationStrategy,
                senderId,
                NON_FUNGIBLE_TOKEN_ID,
                spenderId,
                NFT_SERIAL_NO,
                TokenType.NON_FUNGIBLE_UNIQUE);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, NFT_SERIAL_NO)).willReturn(nft);
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(senderId),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        given(nativeOperations.readableAccountStore()).willReturn(accountStore);
        // Lenient, as the sender account is not read when the sender is not the NFT owner
        lenient().when(accountStore.getAccountById(OPERATOR_ACCOUNT_ID)).thenReturn(OPERATOR);
    }

    @Test
    void erc721approveByOwnerLogsOwner() {
        // given
        prepareErc721approveBy(OWNER_ID, APPROVED_ID, OWNED_NFT);
        given(accountStore.getAccountById(OWNER_ID)).willReturn(NFT_OWNER);
        given(accountStore.getAccountById(APPROVED_ID)).willReturn(NFT_SPENDER);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state(), AssertMessages.STATUS);
        assertDispatchedOwner(OWNER_ID, OWNER_ID);
        assertLoggedApproval(OWNER_BESU_ADDRESS, asLongZeroAddress(APPROVED_ID.accountNumOrThrow()));
    }

    @Test
    void erc721approveByOperatorLogsNftOwner() {
        // given
        prepareErc721approveBy(OPERATOR_ACCOUNT_ID, APPROVED_ID, OWNED_NFT);
        given(accountStore.getAccountById(OWNER_ID)).willReturn(NFT_OWNER);
        given(accountStore.getAccountById(APPROVED_ID)).willReturn(NFT_SPENDER);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state(), AssertMessages.STATUS);
        assertDispatchedOwner(OPERATOR_ACCOUNT_ID, OWNER_ID);
        assertLoggedApproval(OWNER_BESU_ADDRESS, asLongZeroAddress(APPROVED_ID.accountNumOrThrow()));
    }

    @Test
    void erc721approveByOperatorOfTreasuryHeldNftLogsTreasury() {
        // given
        prepareErc721approveBy(OPERATOR_ACCOUNT_ID, APPROVED_ID, Nft.DEFAULT);
        given(nativeOperations.getToken(NON_FUNGIBLE_TOKEN_ID)).willReturn(NON_FUNGIBLE_TOKEN);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(TREASURY);
        given(accountStore.getAccountById(APPROVED_ID)).willReturn(NFT_SPENDER);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state(), AssertMessages.STATUS);
        assertDispatchedOwner(OPERATOR_ACCOUNT_ID, NON_SYSTEM_ACCOUNT_ID);
        assertLoggedApproval(
                asLongZeroAddress(NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow()),
                asLongZeroAddress(APPROVED_ID.accountNumOrThrow()));
    }

    @Test
    void erc721revokeByOperatorLogsNftOwner() {
        // given
        prepareErc721approveBy(OPERATOR_ACCOUNT_ID, REVOKE_APPROVAL_SPENDER_ID, OWNED_NFT);
        given(accountStore.getAccountById(OWNER_ID)).willReturn(NFT_OWNER);
        given(accountStore.getAccountById(REVOKE_APPROVAL_SPENDER_ID)).willReturn(null);
        // when
        final var result = subject.execute(frame).fullResult().result();
        // then
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state(), AssertMessages.STATUS);
        assertDispatchedOwner(OPERATOR_ACCOUNT_ID, OWNER_ID);
        assertLoggedApproval(OWNER_BESU_ADDRESS, Address.ZERO);
    }

    private void assertDispatchedOwner(final AccountID senderId, final AccountID ownerId) {
        final var captor = ArgumentCaptor.forClass(TransactionBody.class);
        verify(systemContractOperations)
                .dispatch(
                        captor.capture(), eq(verificationStrategy), eq(senderId), eq(ContractCallStreamBuilder.class));
        final var body = captor.getValue();
        assertEquals(
                ownerId,
                body.hasCryptoDeleteAllowance()
                        ? body.cryptoDeleteAllowanceOrThrow()
                                .nftAllowances()
                                .getFirst()
                                .owner()
                        : body.cryptoApproveAllowanceOrThrow()
                                .nftAllowances()
                                .getFirst()
                                .owner());
    }

    private void assertLoggedApproval(final Address owner, final Address spender) {
        final var captor = ArgumentCaptor.forClass(Log.class);
        verify(frame).addLog(captor.capture());
        final var topics = captor.getValue().getTopics();
        assertEquals(4, topics.size());
        assertEquals(LogTopic.wrap(Bytes32.leftPad(owner.getBytes())), topics.get(1));
        assertEquals(LogTopic.wrap(Bytes32.leftPad(spender.getBytes())), topics.get(2));
        assertEquals(LogTopic.wrap(Bytes32.leftPad(Bytes.ofUnsignedLong(NFT_SERIAL_NO))), topics.get(3));
    }
}
