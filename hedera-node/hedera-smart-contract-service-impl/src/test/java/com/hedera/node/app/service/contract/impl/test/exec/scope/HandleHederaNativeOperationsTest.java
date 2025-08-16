// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.HAPI_RECORD_BUILDER_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_FUNGIBLE_RELATION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_SECP256K1_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CANONICAL_ALIAS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CIVILIAN_OWNED_NFT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_HEDERA_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NFT_SERIAL_NO;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.PARANOID_SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOMEBODY;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthHollowAccountCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.key.KeyVerifier;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.SortedSet;
import java.util.function.Predicate;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleHederaNativeOperationsTest {
    @Mock
    private HandleContext context;

    @Mock
    private MessageFrame frame;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ReadableTokenStore tokenStore;

    @Mock
    private CryptoCreateStreamBuilder cryptoCreateRecordBuilder;

    @Mock
    private ReadableTokenRelationStore relationStore;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private Predicate<Key> signatureTest;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private TokenServiceApi tokenServiceApi;

    @Mock
    private ReadableNftStore nftStore;

    @Mock
    private KeyVerifier keyVerifier;

    @Mock
    private SortedSet<Key> keys;

    @Mock
    EntityIdFactory entityIdFactory;

    private final Deque<MessageFrame> stack = new ArrayDeque<>();

    private HandleHederaNativeOperations subject;

    private AccountID deletedAccount;

    private AccountID beneficiaryAccount;

    @BeforeEach
    void setUp() {
        subject = new HandleHederaNativeOperations(context, A_SECP256K1_KEY, entityIdFactory);
        deletedAccount = AccountID.newBuilder().accountNum(1L).build();
        beneficiaryAccount = AccountID.newBuilder().accountNum(2L).build();
    }

    @Test
    void getAccountUsesContextReadableStore() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(Account.DEFAULT);
        assertSame(Account.DEFAULT, subject.getAccount(NON_SYSTEM_ACCOUNT_ID));
    }

    @Test
    void getAccountKeyUsesContextReadableStore() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(SOMEBODY);
        assertSame(SOMEBODY.keyOrThrow(), subject.getAccountKey(NON_SYSTEM_ACCOUNT_ID));
    }

    @Test
    void getAccountKeyReturnsNullForMissing() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        assertNull(subject.getAccountKey(NON_SYSTEM_ACCOUNT_ID));
    }

    @Test
    void resolveAliasReturnsMissingNumIfNotPresent() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        assertEquals(MISSING_ENTITY_NUMBER, subject.resolveAlias(0, 0, tuweniToPbjBytes(EIP_1014_ADDRESS)));
    }

    @Test
    void resolveAliasReturnsNumIfPresent() {
        final var alias = tuweniToPbjBytes(EIP_1014_ADDRESS);
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountIDByAlias(0, 0, alias)).willReturn(NON_SYSTEM_ACCOUNT_ID);
        assertEquals(NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow(), subject.resolveAlias(0, 0, alias));
    }

    @Test
    void getTokenUsesStore() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(tokenStore);
        given(tokenStore.get(FUNGIBLE_TOKEN_ID)).willReturn(FUNGIBLE_TOKEN);
        assertSame(FUNGIBLE_TOKEN, subject.getToken(FUNGIBLE_TOKEN_ID));
    }

    @Test
    void createsHollowAccountByDispatching() {
        final var synthLazyCreate = TransactionBody.newBuilder()
                .cryptoCreateAccount(synthHollowAccountCreation(CANONICAL_ALIAS))
                .build();
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        when(context.dispatch(any())).thenReturn(cryptoCreateRecordBuilder);

        given(cryptoCreateRecordBuilder.status()).willReturn(OK);

        final var status = subject.createHollowAccount(CANONICAL_ALIAS);
        assertEquals(OK, status);

        verify(cryptoCreateRecordBuilder, never()).memo(any());
    }

    @Test
    void createsHollowAccountByDispatchingDoesNotThrowErrors() {
        final var synthLazyCreate = TransactionBody.newBuilder()
                .cryptoCreateAccount(synthHollowAccountCreation(CANONICAL_ALIAS))
                .build();
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(context.dispatch(assertArg(options -> assertEquals(synthLazyCreate, options.body()))))
                .willReturn(cryptoCreateRecordBuilder);
        given(cryptoCreateRecordBuilder.status()).willReturn(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);

        final var status = assertDoesNotThrow(() -> subject.createHollowAccount(CANONICAL_ALIAS));
        assertThat(status).isEqualTo(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);

        verify(cryptoCreateRecordBuilder, never()).memo(any());
    }

    @Test
    void finalizeHollowAccountAsContractUsesApiAndStore() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountIDByAlias(
                        DEFAULT_HEDERA_CONFIG.shard(), DEFAULT_HEDERA_CONFIG.realm(), CANONICAL_ALIAS))
                .willReturn(A_NEW_ACCOUNT_ID);
        given(context.configuration()).willReturn(DEFAULT_CONFIG);

        subject.finalizeHollowAccountAsContract(CANONICAL_ALIAS);

        verify(tokenServiceApi).finalizeHollowAccountAsContract(A_NEW_ACCOUNT_ID);
    }

    @Test
    void transferWithReceiverSigCheckUsesApi() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        final var contractAccountId = AccountID.newBuilder()
                .accountNum(NON_SYSTEM_CONTRACT_ID.contractNumOrThrow())
                .build();
        given(accountStore.getAccountById(contractAccountId)).willReturn(PARANOID_SOMEBODY);
        given(verificationStrategy.asSignatureTestIn(context, A_SECP256K1_KEY)).willReturn(signatureTest);
        given(signatureTest.test(PARANOID_SOMEBODY.keyOrThrow())).willReturn(true);

        final var result = subject.transferWithReceiverSigCheck(
                1L,
                NON_SYSTEM_ACCOUNT_ID,
                AccountID.newBuilder()
                        .accountNum(NON_SYSTEM_CONTRACT_ID.contractNumOrThrow())
                        .build(),
                verificationStrategy);
        assertEquals(OK, result);
        verify(tokenServiceApi).transferFromTo(NON_SYSTEM_ACCOUNT_ID, contractAccountId, 1L);
    }

    @Test
    void transferWithReceiverSigCheckReturnsInvalidSigIfAppropriate() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        final var contractAccountId = AccountID.newBuilder()
                .accountNum(NON_SYSTEM_CONTRACT_ID.contractNumOrThrow())
                .build();
        given(accountStore.getAccountById(contractAccountId)).willReturn(PARANOID_SOMEBODY);
        given(verificationStrategy.asSignatureTestIn(context, A_SECP256K1_KEY)).willReturn(signatureTest);
        given(signatureTest.test(PARANOID_SOMEBODY.keyOrThrow())).willReturn(false);

        final var result = subject.transferWithReceiverSigCheck(
                1L,
                NON_SYSTEM_ACCOUNT_ID,
                AccountID.newBuilder()
                        .accountNum(NON_SYSTEM_CONTRACT_ID.contractNumOrThrow())
                        .build(),
                verificationStrategy);
        assertEquals(INVALID_SIGNATURE, result);
        verify(tokenServiceApi, never()).transferFromTo(NON_SYSTEM_ACCOUNT_ID, contractAccountId, 1L);
    }

    @Test
    void transferWithReceiverSigCheckSkipsCheckWithoutRequirement() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        final var contractAccountId = AccountID.newBuilder()
                .accountNum(NON_SYSTEM_CONTRACT_ID.contractNumOrThrow())
                .build();
        given(accountStore.getAccountById(contractAccountId)).willReturn(SOMEBODY);

        final var result = subject.transferWithReceiverSigCheck(
                1L,
                NON_SYSTEM_ACCOUNT_ID,
                AccountID.newBuilder()
                        .accountNum(NON_SYSTEM_CONTRACT_ID.contractNumOrThrow())
                        .build(),
                verificationStrategy);
        assertEquals(OK, result);
        verify(tokenServiceApi).transferFromTo(NON_SYSTEM_ACCOUNT_ID, contractAccountId, 1L);
    }

    @Test
    void trackDeletionUpdatesMap() {
        final DeleteCapableTransactionStreamBuilder beneficiaries = mock(DeleteCapableTransactionStreamBuilder.class);
        given(frame.getMessageFrameStack()).willReturn(stack);
        stack.push(frame);
        given(frame.getContextVariable(HAPI_RECORD_BUILDER_CONTEXT_VARIABLE)).willReturn(beneficiaries);
        subject.trackSelfDestructBeneficiary(deletedAccount, beneficiaryAccount, frame);
        verify(beneficiaries)
                .addBeneficiaryForDeletedAccount(
                        AccountID.newBuilder().accountNum(1L).build(),
                        AccountID.newBuilder().accountNum(2L).build());
    }

    @Test
    void settingNonceUsesApi() {
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("hedera.shard", 0)
                .withValue("hedera.realm", 0)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(configuration);
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);

        subject.setNonce(123L, 456L);

        verify(tokenServiceApi).setNonce(AccountID.newBuilder().accountNum(123L).build(), 456L);
    }

    @Test
    void getRelationshipUsesStore() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(relationStore);
        given(relationStore.get(A_NEW_ACCOUNT_ID, FUNGIBLE_TOKEN_ID)).willReturn(A_FUNGIBLE_RELATION);
        assertSame(A_FUNGIBLE_RELATION, subject.getTokenRelation(A_NEW_ACCOUNT_ID, FUNGIBLE_TOKEN_ID));
    }

    @Test
    void getNftUsesStore() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableNftStore.class)).willReturn(nftStore);
        given(nftStore.get(CIVILIAN_OWNED_NFT.nftIdOrThrow())).willReturn(CIVILIAN_OWNED_NFT);
        assertSame(CIVILIAN_OWNED_NFT, subject.getNft(NON_FUNGIBLE_TOKEN_ID, NFT_SERIAL_NO));
    }

    @Test
    void customFeesCheckUsesApi() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(tokenServiceApi.checkForCustomFees(CryptoTransferTransactionBody.DEFAULT))
                .willReturn(true);
        final var result = subject.checkForCustomFees(CryptoTransferTransactionBody.DEFAULT);
        assertTrue(result);
    }

    @Test
    void authorizingSimpleKeysTest() {
        given(context.keyVerifier()).willReturn(keyVerifier);
        given(keyVerifier.authorizingSimpleKeys()).willReturn(keys);
        assertSame(keys, subject.authorizingSimpleKeys());
    }
}
