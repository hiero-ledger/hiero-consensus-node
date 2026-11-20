// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.AN_ED25519_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_SECP256K1_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.TokenBurnTransactionBody;
import com.hedera.hapi.node.token.TokenDeleteTransactionBody;
import com.hedera.hapi.node.token.TokenFeeScheduleUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenFreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenGrantKycTransactionBody;
import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.token.TokenPauseTransactionBody;
import com.hedera.hapi.node.token.TokenRevokeKycTransactionBody;
import com.hedera.hapi.node.token.TokenUnfreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenUnpauseTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateNftsTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenWipeAccountTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.AccountAuthorizingVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleSystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.utils.SystemContractUtils;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.key.KeyRequirement;
import com.hedera.node.app.spi.key.KeyRequirement.TokenRoleKey;
import com.hedera.node.app.spi.key.KeyVerifier;
import com.hedera.node.app.spi.key.RequirementVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleSystemContractOperationsTest {

    private static final TokenID LEGACY_TOKEN = new TokenID(0, 0, 10_285_486);
    private static final ContractID ACTIVE_CONTRACT =
            ContractID.newBuilder().contractNum(20_000_000).build();
    private static final AccountID CALLER =
            AccountID.newBuilder().accountNum(20_000_000).build();
    private static final Key CONTRACT_KEY =
            Key.newBuilder().contractID(ACTIVE_CONTRACT).build();

    @Mock
    private HandleContext context;

    @Mock
    private ContractCallStreamBuilder streamBuilder;

    @Mock
    private ExchangeRateInfo exchangeRateInfo;

    @Mock
    private VerificationStrategy strategy;

    @Mock
    private Predicate<Key> callback;

    @Mock
    private SignatureVerification passed;

    @Mock
    private SignatureVerification failed;

    @Mock
    private KeyVerifier keyVerifier;

    @Mock
    private HandleContext.SavepointStack savepointStack;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ReadableAccountStore accountStore;

    private HandleSystemContractOperations subject;

    @BeforeEach
    void setUp() {
        subject = new HandleSystemContractOperations(context, A_SECP256K1_KEY);
    }

    @Test
    void returnsExpectedRequirementTest() {
        final var callback = mock(RequirementVerifier.class);
        given(strategy.asRequirementTestIn(context, A_SECP256K1_KEY)).willReturn(callback);
        assertSame(callback, subject.requirementTestWith(strategy));
    }

    @Test
    void dispatchesWithEmptySetOfAuthorizingKeysByDefault() {
        final var mockSubject = mock(HandleSystemContractOperations.class);
        doCallRealMethod().when(mockSubject).dispatch(any(), any(), any(), any());

        mockSubject.dispatch(TransactionBody.DEFAULT, strategy, A_NEW_ACCOUNT_ID, CryptoTransferStreamBuilder.class);

        verify(mockSubject)
                .dispatch(
                        TransactionBody.DEFAULT,
                        strategy,
                        A_NEW_ACCOUNT_ID,
                        CryptoTransferStreamBuilder.class,
                        Set.of(),
                        DispatchOptions.UsePresetTxnId.NO);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchesWithCompleteRequirementVerifier() {
        final var captor = forClass(DispatchOptions.class);
        final var requirementVerifier = mock(RequirementVerifier.class);
        given(strategy.asRequirementTestIn(context, A_SECP256K1_KEY)).willReturn(requirementVerifier);

        subject.dispatch(
                TransactionBody.DEFAULT,
                strategy,
                A_NEW_ACCOUNT_ID,
                CryptoTransferStreamBuilder.class,
                Set.of(AN_ED25519_KEY),
                DispatchOptions.UsePresetTxnId.NO);

        verify(context).dispatch(captor.capture());
        final var options = captor.getValue();
        assertSame(requirementVerifier, options.requirementVerifier());
        assertThat(options.authorizingKeys()).containsExactly(AN_ED25519_KEY);
    }

    private static Stream<TransactionBody> legacyTokenRoleOperations() {
        return Stream.of(
                TransactionBody.newBuilder()
                        .tokenMint(TokenMintTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenBurn(TokenBurnTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenWipe(TokenWipeAccountTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenFreeze(
                                TokenFreezeAccountTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenUnfreeze(
                                TokenUnfreezeAccountTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenPause(TokenPauseTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenUnpause(TokenUnpauseTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenGrantKyc(TokenGrantKycTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenRevokeKyc(
                                TokenRevokeKycTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenFeeScheduleUpdate(TokenFeeScheduleUpdateTransactionBody.newBuilder()
                                .tokenId(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenDeletion(TokenDeleteTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenUpdate(TokenUpdateTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build(),
                TransactionBody.newBuilder()
                        .tokenUpdateNfts(
                                TokenUpdateNftsTransactionBody.newBuilder().token(LEGACY_TOKEN))
                        .build());
    }

    @ParameterizedTest
    @MethodSource("legacyTokenRoleOperations")
    void authorizesOnlyTheTargetTokensCompleteContractKey(TransactionBody body) {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        givenCallerKey(CALLER, CONTRACT_KEY);
        final var verifier = delegatedVerifier(body, CALLER);
        assertTrue(verifier.test(new TokenRoleKey(LEGACY_TOKEN, CONTRACT_KEY)));
        assertFalse(verifier.test(new TokenRoleKey(new TokenID(0, 0, 1234), CONTRACT_KEY)));
        assertFalse(verifier.test(new TokenRoleKey(
                LEGACY_TOKEN,
                Key.newBuilder()
                        .contractID(ACTIVE_CONTRACT.copyBuilder().realmNum(1))
                        .build())));
        assertFalse(verifier.test(new TokenRoleKey(LEGACY_TOKEN, AN_ED25519_KEY)));
        assertFalse(verifier.test(new TokenRoleKey(
                LEGACY_TOKEN,
                Key.newBuilder().keyList(new KeyList(List.of(CONTRACT_KEY))).build())));
        assertFalse(verifier.test(new TokenRoleKey(
                LEGACY_TOKEN,
                Key.newBuilder()
                        .thresholdKey(ThresholdKey.newBuilder()
                                .threshold(1)
                                .keys(new KeyList(List.of(CONTRACT_KEY, AN_ED25519_KEY))))
                        .build())));
        assertFalse(verifier.test(KeyRequirement.forAccount(A_NEW_ACCOUNT_ID, CONTRACT_KEY)));
        assertFalse(verifier.test(KeyRequirement.forKey(CONTRACT_KEY)));
        assertTrue(verifier.test(KeyRequirement.forAccount(CALLER, AN_ED25519_KEY)));
    }

    @Test
    void roleKeysInsideGroupsKeepTheStandardRules() {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        givenCallerKey(CALLER, CONTRACT_KEY);
        final var body = TransactionBody.newBuilder()
                .tokenUpdate(TokenUpdateTransactionBody.newBuilder().token(LEGACY_TOKEN))
                .build();
        final var verifier = delegatedVerifier(body, CALLER);
        final var selfRole = new TokenRoleKey(LEGACY_TOKEN, CONTRACT_KEY);
        final var otherRole = new TokenRoleKey(LEGACY_TOKEN, AN_ED25519_KEY);
        final var delegatableKey =
                Key.newBuilder().delegatableContractId(ACTIVE_CONTRACT).build();

        // A whole stored self key qualifies, as when it equaled the caller's account key before 0.79
        assertTrue(verifier.test(selfRole));
        // Before 0.79 a compound requirement never equaled that key, so grouped self keys don't qualify,
        // whether as the admin alternative or as the current role in a rotation
        assertFalse(verifier.test(KeyRequirement.anyOf(List.of(otherRole, selfRole))));
        assertFalse(verifier.test(KeyRequirement.anyOf(List.of(
                new KeyRequirement.AllOf(List.of(selfRole, KeyRequirement.forKey(delegatableKey))), otherRole))));
        // A group still passes when the standard rules satisfy it
        assertTrue(verifier.test(
                KeyRequirement.anyOf(List.of(otherRole, new TokenRoleKey(LEGACY_TOKEN, delegatableKey)))));
    }

    @ParameterizedTest
    @ValueSource(longs = {10_285_486, 10_485_149, 10_882_398, 10_961_495})
    void authorizesTheSelfKeyForEveryTokenBelowCutoff(long tokenNum) {
        final var tokenId = new TokenID(0, 0, tokenNum);
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        givenCallerKey(CALLER, CONTRACT_KEY);
        assertTrue(delegatedVerifier(mintOf(tokenId), CALLER).test(new TokenRoleKey(tokenId, CONTRACT_KEY)));
    }

    @ParameterizedTest
    @ValueSource(longs = {10_961_496, 10_961_497})
    void requiresDelegatableKeyAtOrAboveCutoff(long tokenNum) {
        final var tokenId = new TokenID(0, 0, tokenNum);
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        final var verifier = delegatedVerifier(mintOf(tokenId), CALLER);
        assertFalse(verifier.test(new TokenRoleKey(tokenId, CONTRACT_KEY)));
        assertTrue(verifier.test(new TokenRoleKey(
                tokenId, Key.newBuilder().delegatableContractId(ACTIVE_CONTRACT).build())));
    }

    @Test
    void zeroCutoffDisablesTheException() {
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("contracts.grandfatherTokenNum", "0")
                        .getOrCreateConfig());
        assertFalse(delegatedVerifier(mintOf(LEGACY_TOKEN), CALLER).test(new TokenRoleKey(LEGACY_TOKEN, CONTRACT_KEY)));
    }

    private static Stream<Key> callerKeysOtherThanTheSelfKey() {
        return Stream.of(
                AN_ED25519_KEY,
                Key.newBuilder().keyList(new KeyList(List.of(CONTRACT_KEY))).build(),
                Key.newBuilder().delegatableContractId(ACTIVE_CONTRACT).build());
    }

    @ParameterizedTest
    @MethodSource("callerKeysOtherThanTheSelfKey")
    void requiresTheCallerToBeSelfManaged(Key callerKey) {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        givenCallerKey(CALLER, callerKey);
        assertFalse(delegatedVerifier(mintOf(LEGACY_TOKEN), CALLER).test(new TokenRoleKey(LEGACY_TOKEN, CONTRACT_KEY)));
    }

    @Test
    void requiresTheCallingContractToExist() {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        givenCallerKey(CALLER, null);
        assertFalse(delegatedVerifier(mintOf(LEGACY_TOKEN), CALLER).test(new TokenRoleKey(LEGACY_TOKEN, CONTRACT_KEY)));
    }

    @Test
    void directCallsKeepStandardRulesForEveryToken() {
        // Neither the cutoff nor the caller's account is consulted without delegate permission
        final var tokenId = new TokenID(0, 0, 20_000_001);
        assertTrue(verifierFor(mintOf(tokenId), CALLER, false).test(new TokenRoleKey(tokenId, CONTRACT_KEY)));
    }

    @Test
    void otherDispatchesCannotAuthorizeLegacyTokenRoleKeys() {
        assertFalse(
                delegatedVerifier(TransactionBody.DEFAULT, CALLER).test(new TokenRoleKey(LEGACY_TOKEN, CONTRACT_KEY)));
    }

    @Test
    void aDifferentCallersAccountAuthorityCannotActivateTheLegacyKey() {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        // An account whose key names the active contract is still not that contract
        givenCallerKey(A_NEW_ACCOUNT_ID, CONTRACT_KEY);
        assertFalse(delegatedVerifier(mintOf(LEGACY_TOKEN), A_NEW_ACCOUNT_ID)
                .test(new TokenRoleKey(LEGACY_TOKEN, CONTRACT_KEY)));
    }

    private static TransactionBody mintOf(final TokenID tokenId) {
        return TransactionBody.newBuilder()
                .tokenMint(TokenMintTransactionBody.newBuilder().token(tokenId))
                .build();
    }

    private void givenCallerKey(final AccountID callerId, final Key key) {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(callerId))
                .willReturn(
                        key == null
                                ? null
                                : Account.newBuilder()
                                        .accountId(callerId)
                                        .key(key)
                                        .build());
    }

    private RequirementVerifier delegatedVerifier(TransactionBody body, AccountID caller) {
        return verifierFor(body, caller, true);
    }

    @SuppressWarnings("unchecked")
    private RequirementVerifier verifierFor(
            TransactionBody body, AccountID caller, boolean requiresDelegatePermission) {
        final var active = new ActiveContractVerificationStrategy(
                ACTIVE_CONTRACT,
                com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY,
                requiresDelegatePermission,
                ActiveContractVerificationStrategy.UseTopLevelSigs.NO);
        subject.dispatch(
                body,
                new AccountAuthorizingVerificationStrategy(caller, active),
                caller,
                ContractCallStreamBuilder.class);
        final var captor = forClass(DispatchOptions.class);
        verify(context).dispatch(captor.capture());
        return captor.getValue().requirementVerifier();
    }

    @Test
    void externalizesPreemptedAsExpected() {
        given(context.savepointStack()).willReturn(savepointStack);
        given(savepointStack.addChildRecordBuilder(ContractCallStreamBuilder.class, CRYPTO_TRANSFER))
                .willReturn(streamBuilder);
        given(streamBuilder.signedTx(any())).willReturn(streamBuilder);
        given(streamBuilder.status(any())).willReturn(streamBuilder);

        final var preemptedBuilder =
                subject.externalizePreemptedDispatch(TransactionBody.DEFAULT, ACCOUNT_DELETED, CRYPTO_TRANSFER);

        assertSame(streamBuilder, preemptedBuilder);
        verify(streamBuilder).status(ACCOUNT_DELETED);
    }

    @Test
    void externalizeSuccessfulResultWithTransactionBodyTest() {
        var transaction = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(TransactionBody.newBuilder()
                        .transactionID(TransactionID.DEFAULT)
                        .build()))
                .build();
        var contractFunctionResult = SystemContractUtils.successResultOfZeroValueTraceable(
                0,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                100L,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                AccountID.newBuilder().build());
        var txResult = SystemContractUtils.txSuccessResultOfZeroValueTraceable(
                0,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                100L,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                AccountID.newBuilder().build());

        // given
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("blockStream.streamMode", "BOTH")
                        .getOrCreateConfig());
        given(context.savepointStack()).willReturn(savepointStack);
        given(savepointStack.addChildRecordBuilder(ContractCallStreamBuilder.class, CONTRACT_CALL))
                .willReturn(streamBuilder);
        given(streamBuilder.signedTx(transaction)).willReturn(streamBuilder);
        given(streamBuilder.status(ResponseCodeEnum.SUCCESS)).willReturn(streamBuilder);
        given(streamBuilder.contractCallResult(contractFunctionResult)).willReturn(streamBuilder);

        // when
        subject.externalizeResult(contractFunctionResult, ResponseCodeEnum.SUCCESS, transaction, txResult);

        // then
        verify(streamBuilder).status(ResponseCodeEnum.SUCCESS);
        verify(streamBuilder).contractCallResult(contractFunctionResult);
    }

    @Test
    void externalizeResultSkipsLegacyContractCallResultWhenStreamModeIsBlocks() {
        final var transaction = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(TransactionBody.newBuilder()
                        .transactionID(TransactionID.DEFAULT)
                        .build()))
                .build();
        final var contractFunctionResult = SystemContractUtils.successResultOfZeroValueTraceable(
                0,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                100L,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                AccountID.newBuilder().build());
        final var txResult = SystemContractUtils.txSuccessResultOfZeroValueTraceable(
                0,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                100L,
                org.apache.tuweni.bytes.Bytes.EMPTY,
                AccountID.newBuilder().build());

        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("blockStream.streamMode", "BLOCKS")
                        .getOrCreateConfig());
        given(context.savepointStack()).willReturn(savepointStack);
        given(savepointStack.addChildRecordBuilder(ContractCallStreamBuilder.class, CONTRACT_CALL))
                .willReturn(streamBuilder);
        given(streamBuilder.signedTx(transaction)).willReturn(streamBuilder);
        given(streamBuilder.status(ResponseCodeEnum.SUCCESS)).willReturn(streamBuilder);

        // when
        subject.externalizeResult(contractFunctionResult, ResponseCodeEnum.SUCCESS, transaction, txResult);

        // then
        verify(streamBuilder, never())
                .contractCallResult(any(com.hedera.hapi.node.contract.ContractFunctionResult.class));
        verify(streamBuilder).evmCallTransactionResult(txResult);
    }

    @Test
    void syntheticTransactionForHtsCallTest() {
        assertNotNull(subject.syntheticSignedTxForNativeCall(Bytes.EMPTY, ContractID.DEFAULT, true));
    }

    @Test
    void currentExchangeRateTest() {
        given(context.exchangeRateInfo()).willReturn(exchangeRateInfo);
        subject.currentExchangeRate();
        verify(context).exchangeRateInfo();
        verify(exchangeRateInfo).activeRate(any());
    }

    @Test
    void maybeEthSenderKeyTest() {
        assertSame(A_SECP256K1_KEY, subject.maybeEthSenderKey());
    }
}
