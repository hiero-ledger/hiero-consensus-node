// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.hapi.node.base.TokenFreezeStatus.FREEZE_NOT_APPLICABLE;
import static com.hedera.hapi.node.base.TokenKycStatus.KYC_NOT_APPLICABLE;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFO_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_KEY;
import static com.hedera.node.app.service.token.impl.test.handlers.util.StateBuilderUtil.NETWORK_REWARDS;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.StakingInfo;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenRelationship;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.CryptoGetInfoQuery;
import com.hedera.hapi.node.token.CryptoGetInfoResponse;
import com.hedera.hapi.node.token.CryptoGetInfoResponse.AccountInfo;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.api.AccountSummariesApi;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableNetworkStakingRewardsStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableStakingInfoStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenRelationStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountInfoHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.CommonUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableSingletonStateBase;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoGetAccountInfoHandlerTest extends CryptoHandlerTestBase {

    @Mock(strictness = LENIENT)
    private QueryContext context;

    @Mock
    private Token token1, token2;
    @Mock
    private ReadableStates readableStates1, readableStates2, readableStates3, readableStates4;

    @Mock
    private Configuration configuration;

    private CryptoOpsUsage cryptoOpsUsage;
    private final InstantSource instantSource = InstantSource.system();

    private CryptoGetAccountInfoHandler subject;

    @Mock
    private StakingNodeInfo stakingNodeInfo;

    @BeforeEach
    public void setUp() {
        super.setUp();
        cryptoOpsUsage = new CryptoOpsUsage();
        subject = new CryptoGetAccountInfoHandler(cryptoOpsUsage, instantSource);
    }

    @Test
    @DisplayName("Query header is extracted correctly")
    void extractsHeader() {
        final var query = createCryptoGetInfoQuery(accountNum);
        final var header = subject.extractHeader(query);
        final var op = query.cryptoGetInfo();
        assertEquals(op.header(), header);
    }

    @Test
    @DisplayName("Check empty query response is created correctly")
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();
        final var response = subject.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .cryptoGetInfo(CryptoGetInfoResponse.newBuilder().header(responseHeader))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    @DisplayName("Validate query is successful with valid account")
    void validatesQueryWhenValidAccount() {
        readableAccounts = emptyReadableAccountStateBuilder().value(id, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(readableAccounts);
        readableStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var query = createCryptoGetInfoQuery(accountNum);
        given(context.query()).willReturn(query);
        given(context.createStore(ReadableAccountStore.class)).willReturn(readableStore);

        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Empty account failed during validate")
    void validatesQueryIfEmptyAccount() {
        final var state =
                MapReadableKVState.<AccountID, Account>builder(ACCOUNTS_KEY).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(state);
        final var store = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var query = createEmptyCryptoGetInfoQuery();

        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    @DisplayName("Account Id is needed during validate")
    void validatesQueryIfInvalidAccount() {
        final var state =
                MapReadableKVState.<AccountID, Account>builder(ACCOUNTS_KEY).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(state);
        final var store = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    @DisplayName("deleted account is not valid")
    void validatesQueryIfDeletedAccount() {
        deleteAccount = deleteAccount.copyBuilder().deleted(true).build();
        readableAccounts = emptyReadableAccountStateBuilder()
                .value(deleteAccountId, deleteAccount)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(readableAccounts);
        readableStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var query = createCryptoGetInfoQuery(deleteAccountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableStore);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.ACCOUNT_DELETED));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("failed response is correctly handled in findResponse")
    void getsResponseIfFailedResponse(boolean balancesInQueriesEnabled) {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.INVALID_ACCOUNT_ID)
                .build();

        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableStore);

        setupConfig(balancesInQueriesEnabled);

        final var response = subject.findResponse(context, responseHeader);
        final var op = response.cryptoGetInfo();
        assertEquals(ResponseCodeEnum.INVALID_ACCOUNT_ID, op.header().nodeTransactionPrecheckCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("fail FAIL_INVALID test")
    void getsCorrectResponseHeadIfAccountInfoNotFound(boolean balancesInQueriesEnabled) {
        final var responseHeader = getOkResponse();

        setupAccountStore();
        setupTokenStore();
        setupTokenRelationStore();
        setupStakingInfoStore();
        setupConfig(balancesInQueriesEnabled);

        final var query = createCryptoGetInfoQuery(4);
        when(context.query()).thenReturn(query);

        final var response = subject.findResponse(context, responseHeader);
        final var cryptoGetInfoResponse = response.cryptoGetInfo();
        assertEquals(getFailInvalidResponse(), cryptoGetInfoResponse.header());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("OK response is correctly handled in findResponse")
    void getsResponseIfOkResponse(boolean balancesInQueriesEnabled) {
        final var responseHeader = getOkResponse();
        final var expectedInfo = getExpectedAccountInfo(balancesInQueriesEnabled);

        account = account.copyBuilder().stakedNodeId(0).declineReward(false).build();
        setupAccountStore();

        lenient().when(token1.decimals()).thenReturn(100); // only needed when balancesInQueriesEnabled is true
        lenient().when(token1.symbol()).thenReturn("FOO"); // only needed when balancesInQueriesEnabled is true
        when(token1.tokenId()).thenReturn(asToken(3L));
        setupTokenStore(token1);

        final var tokenRelation = TokenRelation.newBuilder()
                .tokenId(asToken(3L))
                .accountId(id)
                .balance(1000L)
                .frozen(false)
                .kycGranted(false)
                .automaticAssociation(true)
                .nextToken(asToken(4L))
                .previousToken(asToken(2L))
                .build();
        setupTokenRelationStore(tokenRelation);
        setupStakingInfoStore();
        setupStakingRewardsStore();
        setupConfig(balancesInQueriesEnabled);
        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);

        final var response = subject.findResponse(context, responseHeader);
        final var cryptoGetInfoResponse = response.cryptoGetInfo();
        assertEquals(ResponseCodeEnum.OK, cryptoGetInfoResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, cryptoGetInfoResponse.accountInfo());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("check multiple token relations list")
    void checkMulitpleTokenRelations(boolean balancesInQueriesEnabled) {
        final var responseHeader = getOkResponse();
        final var expectedInfo = getExpectedAccountInfos(balancesInQueriesEnabled);

        account = account.copyBuilder().stakedNodeId(0).declineReward(false).build();
        setupAccountStore();

        lenient().when(token1.decimals()).thenReturn(100); // only needed when balancesInQueriesEnabled is true
        lenient().when(token2.decimals()).thenReturn(50); // only needed when balancesInQueriesEnabled is true
        lenient().when(token1.symbol()).thenReturn("FOO"); // only needed when balancesInQueriesEnabled is true
        lenient().when(token2.symbol()).thenReturn("BAR"); // only needed when balancesInQueriesEnabled is true
        when(token1.tokenId()).thenReturn(asToken(3L));
        when(token2.tokenId()).thenReturn(asToken(4L));
        setupTokenStore(token1, token2);

        final var tokenRelation1 = TokenRelation.newBuilder()
                .tokenId(asToken(3L))
                .accountId(id)
                .balance(1000L)
                .frozen(false)
                .kycGranted(false)
                .automaticAssociation(true)
                .nextToken(asToken(4L))
                .previousToken(asToken(2L))
                .build();
        final var tokenRelation2 = TokenRelation.newBuilder()
                .tokenId(asToken(4L))
                .accountId(id)
                .balance(100L)
                .frozen(false)
                .kycGranted(false)
                .automaticAssociation(true)
                .nextToken(asToken(5L))
                .previousToken(asToken(3L))
                .build();
        final var tokenRelation3 = TokenRelation.newBuilder()
                .tokenId(asToken(5L))
                .accountId(id)
                .balance(10L)
                .frozen(false)
                .kycGranted(false)
                .automaticAssociation(true)
                .nextToken(asToken(6L))
                .previousToken(asToken(4L))
                .build();
        setupTokenRelationStore(tokenRelation1, tokenRelation2, tokenRelation3);
        setupStakingInfoStore();
        setupStakingRewardsStore();
        setupConfig(balancesInQueriesEnabled);

        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);

        final var response = subject.findResponse(context, responseHeader);
        final var cryptoGetInfoResponse = response.cryptoGetInfo();

        assertEquals(ResponseCodeEnum.OK, cryptoGetInfoResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, cryptoGetInfoResponse.accountInfo());
        assertEquals(
                balancesInQueriesEnabled ? 2 : 0,
                cryptoGetInfoResponse.accountInfo().tokenRelationships().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testStakeNumber(boolean balancesInQueriesEnabled) {
        final var responseHeader = getOkResponse();
        final var expectedInfo = getExpectedAccountInfo2(balancesInQueriesEnabled);

        account = account.copyBuilder()
                .stakedAccountId(AccountID.newBuilder().accountNum(1).build())
                .declineReward(false)
                .build();
        setupAccountStore();

        lenient().when(token1.decimals()).thenReturn(100); // only needed when balancesInQueriesEnabled is true
        lenient().when(token1.symbol()).thenReturn("FOO"); // only needed when balancesInQueriesEnabled is true
        when(token1.tokenId()).thenReturn(asToken(3L));
        setupTokenStore(token1);

        final var tokenRelation = TokenRelation.newBuilder()
                .tokenId(asToken(3L))
                .accountId(id)
                .balance(1000L)
                .frozen(false)
                .kycGranted(false)
                .automaticAssociation(true)
                .nextToken(asToken(4L))
                .previousToken(asToken(2L))
                .build();
        setupTokenRelationStore(tokenRelation);
        setupStakingInfoStore();
        setupStakingRewardsStore();
        setupConfig(balancesInQueriesEnabled);
        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);

        final var response = subject.findResponse(context, responseHeader);
        final var cryptoGetInfoResponse = response.cryptoGetInfo();
        assertEquals(ResponseCodeEnum.OK, cryptoGetInfoResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, cryptoGetInfoResponse.accountInfo());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testEvmAddressAlias(boolean balancesInQueriesEnabled) {
        final Bytes evmAddress = Bytes.wrap(CommonUtils.unhex("6aeb3773ea468a814d954e6dec795bfee7d76e26"));
        final var responseHeader = getOkResponse();
        final var expectedInfo = getExpectedAccountInfoEvm(balancesInQueriesEnabled);

        account = account.copyBuilder()
                .stakedNodeId(0)
                .declineReward(false)
                .alias(evmAddress)
                .build();
        setupAccountStore();

        lenient().when(token1.decimals()).thenReturn(100); // only needed when balancesInQueriesEnabled is true
        lenient().when(token1.symbol()).thenReturn("FOO"); // only needed when balancesInQueriesEnabled is true
        when(token1.tokenId()).thenReturn(asToken(3L));
        setupTokenStore(token1);

        final var tokenRelation = TokenRelation.newBuilder()
                .tokenId(asToken(3L))
                .accountId(id)
                .balance(1000L)
                .frozen(false)
                .kycGranted(false)
                .automaticAssociation(true)
                .nextToken(asToken(4L))
                .previousToken(asToken(2L))
                .build();
        setupTokenRelationStore(tokenRelation);
        setupStakingInfoStore();
        setupStakingRewardsStore();
        setupConfig(balancesInQueriesEnabled);
        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);

        final var response = subject.findResponse(context, responseHeader);
        final var cryptoGetInfoResponse = response.cryptoGetInfo();
        assertEquals(ResponseCodeEnum.OK, cryptoGetInfoResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, cryptoGetInfoResponse.accountInfo());
    }

    private void setupAccountStore() {
        final var readableAccounts = MapReadableKVState.<AccountID, Account>builder(ACCOUNTS_KEY)
                .value(id, account)
                .build();
        given(readableStates1.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(readableAccounts);
        ReadableAccountStore ReadableAccountStore =
                new ReadableAccountStoreImpl(readableStates1, readableEntityCounters);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(ReadableAccountStore);
    }

    private void setupTokenStore(Token... tokens) {
        final var readableToken = MapReadableKVState.<TokenID, Token>builder(TOKENS_KEY);
        for (Token token : tokens) {
            readableToken.value(token.tokenId(), token);
        }
        given(readableStates2.<TokenID, Token>get(TOKENS_KEY)).willReturn(readableToken.build());
        final var readableTokenStore = new ReadableTokenStoreImpl(readableStates2, readableEntityCounters);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
    }

    private void setupTokenRelationStore(TokenRelation... tokenRelations) {
        final var readableTokenRel = MapReadableKVState.<EntityIDPair, TokenRelation>builder(TOKEN_RELS_KEY);
        for (TokenRelation tokenRelation : tokenRelations) {
            readableTokenRel.value(
                    EntityIDPair.newBuilder()
                            .accountId(id)
                            .tokenId(tokenRelation.tokenId())
                            .build(),
                    tokenRelation);
        }
        given(readableStates3.<EntityIDPair, TokenRelation>get(TOKEN_RELS_KEY)).willReturn(readableTokenRel.build());
        final var readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates3, readableEntityCounters);
        when(context.createStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);
    }

    private void setupStakingInfoStore() {
        final var readableStakingNodes = MapReadableKVState.<AccountID, StakingNodeInfo>builder(STAKING_INFO_KEY)
                .value(id, stakingNodeInfo)
                .build();
        given(readableStates4.<AccountID, StakingNodeInfo>get(STAKING_INFO_KEY)).willReturn(readableStakingNodes);
        final var readableStakingInfoStore = new ReadableStakingInfoStoreImpl(readableStates4);
        when(context.createStore(ReadableStakingInfoStore.class)).thenReturn(readableStakingInfoStore);
    }

    private void setupStakingRewardsStore() {
        final AtomicReference<NetworkStakingRewards> backingValue =
                new AtomicReference<>(new NetworkStakingRewards(true, 100000L, 50000L, 1000L));
        final var stakingRewardsState = new ReadableSingletonStateBase<>(NETWORK_REWARDS, backingValue::get);
        given(readableStates.getSingleton(NETWORK_REWARDS)).willReturn((ReadableSingletonState) stakingRewardsState);
        final var readableRewardsStore = new ReadableNetworkStakingRewardsStoreImpl(readableStates);
        when(context.createStore(ReadableNetworkStakingRewardsStore.class)).thenReturn(readableRewardsStore);
    }

    private void setupConfig(boolean balancesInQueriesEnabled) {
        final var configBuilder = HederaTestConfigBuilder.create()
                .withValue("tokens.maxRelsPerInfoQuery", 2)
                .withValue("ledger.id", "0x03")
                .withValue("tokens.balancesInQueries.enabled", balancesInQueriesEnabled);
        given(context.configuration()).willReturn(configBuilder.getOrCreateConfig());
    }

    private AccountInfo getExpectedAccountInfo(boolean balancesInQueriesEnabled) {
        final var builder = AccountInfo.newBuilder()
                .key(key)
                .accountID(id)
                .receiverSigRequired(true)
                .ledgerId(new BytesConverter().convert("0x03"))
                .deleted(false)
                .memo("testAccount")
                .autoRenewPeriod(Duration.newBuilder().seconds(72000))
                .balance(payerBalance)
                .expirationTime(Timestamp.newBuilder().seconds(1_234_567L))
                .ownedNfts(2)
                .maxAutomaticTokenAssociations(10)
                .ethereumNonce(0)
                .alias(alias.alias())
                .contractAccountID(AccountSummariesApi.hexedEvmAddressOf(account))
                .stakingInfo(getExpectedStakingInfo());
        if (balancesInQueriesEnabled) {
            builder.tokenRelationships(getExpectedTokenRelationship());
        }
        return builder.build();
    }

    private AccountInfo getExpectedAccountInfo2(boolean balancesInQueriesEnabled) {
        final var builder = AccountInfo.newBuilder()
                .key(key)
                .accountID(id)
                .receiverSigRequired(true)
                .ledgerId(new BytesConverter().convert("0x03"))
                .deleted(false)
                .memo("testAccount")
                .autoRenewPeriod(Duration.newBuilder().seconds(72000))
                .balance(payerBalance)
                .expirationTime(Timestamp.newBuilder().seconds(1_234_567L))
                .ownedNfts(2)
                .maxAutomaticTokenAssociations(10)
                .ethereumNonce(0)
                .alias(alias.alias())
                .contractAccountID(AccountSummariesApi.hexedEvmAddressOf(account))
                .stakingInfo(getExpectedStakingInfo2());
        if (balancesInQueriesEnabled) {
            builder.tokenRelationships(getExpectedTokenRelationship());
        }
        return builder.build();
    }

    private AccountInfo getExpectedAccountInfoEvm(boolean balancesInQueriesEnabled) {
        final var builder = AccountInfo.newBuilder()
                .key(key)
                .accountID(id)
                .receiverSigRequired(true)
                .ledgerId(new BytesConverter().convert("0x03"))
                .deleted(false)
                .memo("testAccount")
                .autoRenewPeriod(Duration.newBuilder().seconds(72000))
                .balance(payerBalance)
                .expirationTime(Timestamp.newBuilder().seconds(1_234_567L))
                .ownedNfts(2)
                .maxAutomaticTokenAssociations(10)
                .ethereumNonce(0)
                .contractAccountID("6aeb3773ea468a814d954e6dec795bfee7d76e26")
                .stakingInfo(getExpectedStakingInfo());
        if (balancesInQueriesEnabled) {
            builder.tokenRelationships(getExpectedTokenRelationship());
        }
        return builder.build();
    }

    private AccountInfo getExpectedAccountInfos(boolean balancesInQueriesEnabled) {
        final var builder = AccountInfo.newBuilder()
                .key(key)
                .accountID(id)
                .receiverSigRequired(true)
                .ledgerId(new BytesConverter().convert("0x03"))
                .deleted(false)
                .memo("testAccount")
                .autoRenewPeriod(Duration.newBuilder().seconds(72000))
                .balance(payerBalance)
                .expirationTime(Timestamp.newBuilder().seconds(1_234_567L))
                .ownedNfts(2)
                .maxAutomaticTokenAssociations(10)
                .ethereumNonce(0)
                .alias(alias.alias())
                .contractAccountID(
                        AccountSummariesApi.hexedEvmAddressOf(account)) // "00000005000000000000000a0000000000000003")
                .stakingInfo(getExpectedStakingInfo());
        if (balancesInQueriesEnabled) {
            builder.tokenRelationships(getExpectedTokenRelationships());
        }
        return builder.build();
    }

    private List<TokenRelationship> getExpectedTokenRelationship() {
        var ret = new ArrayList<TokenRelationship>();
        final var tokenRelationship1 = TokenRelationship.newBuilder()
                .tokenId(TokenID.newBuilder().tokenNum(3L).build())
                .symbol("FOO")
                .balance(1000)
                .decimals(100)
                .kycStatus(KYC_NOT_APPLICABLE)
                .freezeStatus(FREEZE_NOT_APPLICABLE)
                .automaticAssociation(true)
                .build();
        ret.add(tokenRelationship1);
        return ret;
    }

    private List<TokenRelationship> getExpectedTokenRelationships() {
        var ret = new ArrayList<TokenRelationship>();
        final var tokenRelationship1 = TokenRelationship.newBuilder()
                .tokenId(TokenID.newBuilder().tokenNum(3L).build())
                .symbol("FOO")
                .balance(1000)
                .decimals(100)
                .kycStatus(KYC_NOT_APPLICABLE)
                .freezeStatus(FREEZE_NOT_APPLICABLE)
                .automaticAssociation(true)
                .build();
        final var tokenRelationship2 = TokenRelationship.newBuilder()
                .tokenId(TokenID.newBuilder().tokenNum(4L).build())
                .symbol("BAR")
                .balance(100)
                .decimals(50)
                .kycStatus(KYC_NOT_APPLICABLE)
                .freezeStatus(FREEZE_NOT_APPLICABLE)
                .automaticAssociation(true)
                .build();

        ret.add(tokenRelationship1);
        ret.add(tokenRelationship2);
        return ret;
    }

    private StakingInfo getExpectedStakingInfo() {
        return StakingInfo.newBuilder()
                .declineReward(false)
                .stakedToMe(1_234L)
                .stakedNodeId(0)
                .stakePeriodStart(Timestamp.newBuilder().seconds(106666675200L).build())
                .build();
    }

    private StakingInfo getExpectedStakingInfo2() {
        return StakingInfo.newBuilder()
                .declineReward(false)
                .stakedToMe(1_234L)
                .stakedAccountId(AccountID.newBuilder().accountNum(1).build())
                .build();
    }

    private ResponseHeader getFailInvalidResponse() {
        return ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(FAIL_INVALID)
                .responseType(ANSWER_ONLY)
                .cost(0)
                .build();
    }

    private Query createCryptoGetInfoQuery(final long accountId) {
        final var data = CryptoGetInfoQuery.newBuilder()
                .accountID(idFactory.newAccountId(accountId))
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().cryptoGetInfo(data).build();
    }

    private Query createEmptyCryptoGetInfoQuery() {
        final var data = CryptoGetInfoQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().cryptoGetInfo(data).build();
    }

    private ResponseHeader getOkResponse() {
        return ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
    }
}
