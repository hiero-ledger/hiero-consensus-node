// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.grantapproval;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_SPENDER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.REVOKE_APPROVAL_SPENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNAUTHORIZED_SPENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asBytesResult;
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
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.ERCGrantApprovalCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.GrantApprovalTranslator;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.service.token.ReadableAccountStore;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ERCGrantApprovalCallTest extends CallTestBase {
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
    private Token token;

    @Mock
    private Account account;

    @Mock
    private MessageFrame frame;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ProxyWorldUpdater updater;

    @Test
    void erc20approve() {
        subject = new ERCGrantApprovalCall(
                mockEnhancement(),
                systemContractGasCalculator,
                verificationStrategy,
                OWNER_ID,
                FUNGIBLE_TOKEN_ID,
                UNAUTHORIZED_SPENDER_ID,
                100L,
                TokenType.FUNGIBLE_COMMON);
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(OWNER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);
        given(nativeOperations.readableAccountStore()).willReturn(accountStore);
        given(accountStore.getAccountById(any(AccountID.class))).willReturn(account);
        given(account.accountIdOrThrow())
                .willReturn(AccountID.newBuilder().accountNum(1).build());
        given(account.alias()).willReturn(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[] {1, 2, 3}));
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                asBytesResult(
                        GrantApprovalTranslator.ERC_GRANT_APPROVAL.getOutputs().encode(Tuple.singleton(true))),
                result.getOutput());
    }

    @Test
    void erc721approve() {
        subject = new ERCGrantApprovalCall(
                mockEnhancement(),
                systemContractGasCalculator,
                verificationStrategy,
                OWNER_ID,
                NON_FUNGIBLE_TOKEN_ID,
                UNAUTHORIZED_SPENDER_ID,
                100L,
                TokenType.NON_FUNGIBLE_UNIQUE);
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(OWNER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, 100L)).willReturn(nft);
        given(nativeOperations.getToken(NON_FUNGIBLE_TOKEN_ID)).willReturn(token);
        given(nativeOperations.readableAccountStore()).willReturn(accountStore);
        given(accountStore.getAccountById(any(AccountID.class))).willReturn(account);
        given(account.accountIdOrThrow())
                .willReturn(AccountID.newBuilder().accountNum(1).build());
        given(account.alias()).willReturn(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[] {1, 2, 3}));
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                asBytesResult(GrantApprovalTranslator.ERC_GRANT_APPROVAL_NFT
                        .getOutputs()
                        .encode(Tuple.EMPTY)),
                result.getOutput());
    }

    @Test
    void erc721approveFailsWithInvalidSpenderAllowance() {
        subject = new ERCGrantApprovalCall(
                mockEnhancement(),
                systemContractGasCalculator,
                verificationStrategy,
                OWNER_ID,
                NON_FUNGIBLE_TOKEN_ID,
                UNAUTHORIZED_SPENDER_ID,
                100L,
                TokenType.NON_FUNGIBLE_UNIQUE);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, 100L)).willReturn(nft);
        given(nativeOperations.getToken(NON_FUNGIBLE_TOKEN_ID)).willReturn(token);
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(OWNER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(INVALID_ALLOWANCE_SPENDER_ID);
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.REVERT, result.getState());
        assertEquals(UInt256.valueOf(INVALID_ALLOWANCE_SPENDER_ID.protoOrdinal()), result.getOutput());
    }

    @Test
    void erc721approveFailsWithSenderDoesNotOwnNFTSerialNumber() {
        subject = new ERCGrantApprovalCall(
                mockEnhancement(),
                systemContractGasCalculator,
                verificationStrategy,
                OWNER_ID,
                NON_FUNGIBLE_TOKEN_ID,
                UNAUTHORIZED_SPENDER_ID,
                100L,
                TokenType.NON_FUNGIBLE_UNIQUE);
        // make sure nft is found
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, 100L)).willReturn(nft);
        given(nativeOperations.getToken(NON_FUNGIBLE_TOKEN_ID)).willReturn(token);
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(OWNER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status())
                .willReturn(DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL)
                .willReturn(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.REVERT, result.getState());
        assertEquals(UInt256.valueOf(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO.protoOrdinal()), result.getOutput());
        verify(recordBuilder).status(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO);
    }

    @Test
    void erc721approveFailsWithInvalidTokenNFTSerialNumber() {
        subject = new ERCGrantApprovalCall(
                mockEnhancement(),
                systemContractGasCalculator,
                verificationStrategy,
                OWNER_ID,
                NON_FUNGIBLE_TOKEN_ID,
                UNAUTHORIZED_SPENDER_ID,
                100L,
                TokenType.NON_FUNGIBLE_UNIQUE);
        // make sure nft is found
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, 100L)).willReturn(null);
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(OWNER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(INVALID_TOKEN_NFT_SERIAL_NUMBER);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.REVERT, result.getState());
        assertEquals(UInt256.valueOf(INVALID_TOKEN_NFT_SERIAL_NUMBER.protoOrdinal()), result.getOutput());
    }

    @Test
    void erc721revoke() {
        subject = new ERCGrantApprovalCall(
                mockEnhancement(),
                systemContractGasCalculator,
                verificationStrategy,
                OWNER_ID,
                NON_FUNGIBLE_TOKEN_ID,
                REVOKE_APPROVAL_SPENDER_ID,
                100L,
                TokenType.NON_FUNGIBLE_UNIQUE);
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        eq(verificationStrategy),
                        eq(OWNER_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, 100L)).willReturn(nft);
        given(nativeOperations.getToken(NON_FUNGIBLE_TOKEN_ID)).willReturn(token);
        given(nativeOperations.readableAccountStore()).willReturn(accountStore);
        given(accountStore.getAccountById(any(AccountID.class))).willReturn(account);
        given(account.accountIdOrThrow())
                .willReturn(AccountID.newBuilder().accountNum(1).build());
        given(account.alias()).willReturn(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[] {1, 2, 3}));
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                asBytesResult(GrantApprovalTranslator.ERC_GRANT_APPROVAL_NFT
                        .getOutputs()
                        .encode(Tuple.EMPTY)),
                result.getOutput());
    }
}
