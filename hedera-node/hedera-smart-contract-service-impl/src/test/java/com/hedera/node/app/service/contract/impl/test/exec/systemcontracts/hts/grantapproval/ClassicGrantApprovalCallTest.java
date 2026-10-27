// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.grantapproval;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NFT_SERIAL_NO;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OPERATOR_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.REVOKE_APPROVAL_SPENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNAUTHORIZED_SPENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asBytesResult;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.ClassicGrantApprovalCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.GrantApprovalTranslator;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * Unit tests for {@link ClassicGrantApprovalCall}.
 */
public class ClassicGrantApprovalCallTest extends CallTestBase {
    private static final Nft OWNED_NFT = Nft.newBuilder().ownerId(OWNER_ID).build();

    private ClassicGrantApprovalCall subject;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private Nft nft;

    @Mock
    private Token token;

    @Mock
    private Account account;

    @Mock
    private ProxyWorldUpdater updater;

    @Test
    void fungibleApprove() {
        subject = new ClassicGrantApprovalCall(
                systemContractGasCalculator,
                mockEnhancement(),
                verificationStrategy,
                OWNER_ID,
                FUNGIBLE_TOKEN_ID,
                UNAUTHORIZED_SPENDER_ID,
                100L,
                TokenType.FUNGIBLE_COMMON);
        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                asBytesResult(GrantApprovalTranslator.GRANT_APPROVAL
                        .getOutputs()
                        .encode(Tuple.of(ResponseCodeEnum.SUCCESS.protoOrdinal(), true))),
                result.output());
    }

    @Test
    void nftApprove() {
        subject = new ClassicGrantApprovalCall(
                systemContractGasCalculator,
                mockEnhancement(),
                verificationStrategy,
                OWNER_ID,
                NON_FUNGIBLE_TOKEN_ID,
                UNAUTHORIZED_SPENDER_ID,
                100L,
                TokenType.NON_FUNGIBLE_UNIQUE);
        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, 100L)).willReturn(nft);
        given(nativeOperations.getToken(NON_FUNGIBLE_TOKEN_ID)).willReturn(token);
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                asBytesResult(
                        GrantApprovalTranslator.GRANT_APPROVAL_NFT.getOutputs().encode(Tuple.singleton((long)
                                ResponseCodeEnum.SUCCESS.protoOrdinal()))),
                result.output());
    }

    @Test
    void nftApproveByOwnerLogsOwner() {
        givenNftApproveBy(OWNER_ID, APPROVED_ID, OWNED_NFT);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertDispatchedOwner(OWNER_ID, OWNER_ID);
        assertLoggedApproval(
                asLongZeroAddress(OWNER_ID.accountNumOrThrow()), asLongZeroAddress(APPROVED_ID.accountNumOrThrow()));
    }

    @Test
    void nftApproveByOperatorLogsNftOwner() {
        givenNftApproveBy(OPERATOR_ACCOUNT_ID, APPROVED_ID, OWNED_NFT);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertDispatchedOwner(OPERATOR_ACCOUNT_ID, OWNER_ID);
        assertLoggedApproval(
                asLongZeroAddress(OWNER_ID.accountNumOrThrow()), asLongZeroAddress(APPROVED_ID.accountNumOrThrow()));
    }

    @Test
    void nftApproveByOperatorOfTreasuryHeldNftLogsTreasury() {
        givenNftApproveBy(OPERATOR_ACCOUNT_ID, APPROVED_ID, Nft.DEFAULT);
        given(nativeOperations.getToken(NON_FUNGIBLE_TOKEN_ID)).willReturn(NON_FUNGIBLE_TOKEN);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertDispatchedOwner(OPERATOR_ACCOUNT_ID, NON_SYSTEM_ACCOUNT_ID);
        assertLoggedApproval(
                asLongZeroAddress(NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow()),
                asLongZeroAddress(APPROVED_ID.accountNumOrThrow()));
    }

    @Test
    void nftRevokeByOperatorLogsNftOwner() {
        givenNftApproveBy(OPERATOR_ACCOUNT_ID, REVOKE_APPROVAL_SPENDER_ID, OWNED_NFT);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertDispatchedOwner(OPERATOR_ACCOUNT_ID, OWNER_ID);
        assertLoggedApproval(asLongZeroAddress(OWNER_ID.accountNumOrThrow()), Address.ZERO);
    }

    private void givenNftApproveBy(final AccountID senderId, final AccountID spenderId, final Nft nft) {
        subject = new ClassicGrantApprovalCall(
                systemContractGasCalculator,
                mockEnhancement(),
                verificationStrategy,
                senderId,
                NON_FUNGIBLE_TOKEN_ID,
                spenderId,
                NFT_SERIAL_NO,
                TokenType.NON_FUNGIBLE_UNIQUE);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, NFT_SERIAL_NO)).willReturn(nft);
        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);
    }

    private void assertDispatchedOwner(final AccountID senderId, final AccountID ownerId) {
        final var captor = ArgumentCaptor.forClass(TransactionBody.class);
        verify(systemContractOperations).dispatch(captor.capture(), any(), eq(senderId), any());
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
