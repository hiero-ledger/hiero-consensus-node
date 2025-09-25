// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_STATE_LABEL;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.TokenBalance;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.CryptoGetAccountBalanceQuery;
import com.hedera.hapi.node.token.CryptoGetAccountBalanceResponse;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenRelationStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountBalanceHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoGetAccountBalanceHandlerTest extends CryptoHandlerTestBase {

    @Mock(strictness = LENIENT)
    private QueryContext context;

    @Mock(strictness = LENIENT)
    private Metrics metrics;

    @Mock
    private SpeedometerMetric balanceSpeedometer;

    @Mock
    private Token token1, token2, token3;
    @Mock
    private ReadableStates readableStates1, readableStates2, readableStates3;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private Configuration configuration;

    private CryptoGetAccountBalanceHandler subject;

    @BeforeEach
    public void setUp() {
        super.setUp();
        final var config = HederaTestConfigBuilder.createConfig();
        final var versionedConfig = new VersionedConfigImpl(config, 1);
        when(configProvider.getConfiguration()).thenReturn(versionedConfig);
        given(metrics.getOrCreate(any())).willReturn(balanceSpeedometer);
        subject = new CryptoGetAccountBalanceHandler(metrics, configProvider);
    }

    @Test
    @DisplayName("Query header is extracted correctly")
    void extractsHeader() {
        final var query = createGetAccountBalanceQuery(accountNum);
        final var header = subject.extractHeader(query);
        final var op = query.cryptogetAccountBalance();
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
                .cryptogetAccountBalance(
                        CryptoGetAccountBalanceResponse.newBuilder().header(responseHeader))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    @DisplayName("Validate query is successful with valid account")
    void validatesQueryWhenValidAccount() {
        givenValidAccount(accountNum);
        readableAccounts = emptyReadableAccountStateBuilder().value(id, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        ;

        final var query = createGetAccountBalanceQuery(accountNum);
        given(context.query()).willReturn(query);
        given(context.createStore(ReadableAccountStore.class)).willReturn(readableStore);

        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Validate query is successful with valid contract")
    void validatesQueryWhenValidContract() {
        givenValidContract();
        readableAccounts = emptyReadableAccountStateBuilder().value(id, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var query = createGetAccountBalanceQueryWithContract(accountNum);
        given(context.query()).willReturn(query);
        given(context.createStore(ReadableAccountStore.class)).willReturn(readableStore);

        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Empty account failed during validate")
    void validatesQueryIfEmptyAccount() throws Throwable {
        final var state = MapReadableKVState.<AccountID, Account>builder(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(state);
        final var store = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var query = createEmptyGetAccountBalanceQuery();

        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    @DisplayName("Account Id is needed during validate")
    void validatesQueryIfInvalidAccount() throws Throwable {
        final var state = MapReadableKVState.<AccountID, Account>builder(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(state);
        final var store = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var query = createGetAccountBalanceQuery(accountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    @DisplayName("Account Id with valid header is needed during validate")
    void validatesQueryIfInvalidAccountHeader() throws Throwable {
        final var state = MapReadableKVState.<AccountID, Account>builder(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(state);
        final var store = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        final AccountID invalidRealmAccountId =
                AccountID.newBuilder().accountNum(5).realmNum(-1L).build();

        final var query = createGetAccountBalanceQueryWithInvalidHeader(invalidRealmAccountId.accountNumOrThrow());
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    @DisplayName("Contract Id is needed during validate")
    void validatesQueryIfInvalidContract() throws Throwable {
        final var state = MapReadableKVState.<AccountID, Account>builder(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(state);
        final var store = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var query = createGetAccountBalanceQueryWithContract(accountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_CONTRACT_ID));
    }

    @Test
    @DisplayName("deleted account is not valid")
    void validatesQueryIfDeletedAccount() throws Throwable {
        deleteAccount = deleteAccount.copyBuilder().deleted(true).build();
        readableAccounts = emptyReadableAccountStateBuilder()
                .value(deleteAccountId, deleteAccount)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        final var query = createGetAccountBalanceQuery(deleteAccountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableStore);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.ACCOUNT_DELETED));
    }

    @Test
    @DisplayName("deleted contract is not valid")
    void validatesQueryIfDeletedContract() throws Throwable {
        deleteAccount =
                deleteAccount.copyBuilder().deleted(true).smartContract(true).build();
        readableAccounts = emptyReadableAccountStateBuilder()
                .value(deleteAccountId, deleteAccount)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var query = createGetAccountBalanceQueryWithContract(deleteAccountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableStore);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.CONTRACT_DELETED));
    }

    @Test
    @DisplayName("failed response is correctly handled in findResponse")
    void getsResponseIfFailedResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.INVALID_ACCOUNT_ID)
                .build();

        final var query = createGetAccountBalanceQuery(accountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableStore);

        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.maxRelsPerInfoQuery", 1000)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        final var op = response.cryptogetAccountBalance();
        assertEquals(ResponseCodeEnum.INVALID_ACCOUNT_ID, op.header().nodeTransactionPrecheckCode());
        assertNull(op.accountID());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("OK response is correctly handled in findResponse")
    void getsResponseIfOkResponse(boolean balancesInQueriesEnabled) {
        givenValidAccount(accountNum);
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedInfo = getExpectedInfo();

        final var readableAccounts = MapReadableKVState.<AccountID, Account>builder(
                        ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL)
                .value(id, account)
                .build();
        given(readableStates1.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        ReadableAccountStore ReadableAccountStore =
                new ReadableAccountStoreImpl(readableStates1, readableEntityCounters);

        lenient().when(token1.decimals()).thenReturn(100); // only needed when balancesInQueriesEnabled is true
        final var readableToken = MapReadableKVState.<TokenID, Token>builder(TOKENS_STATE_ID, TOKENS_STATE_LABEL)
                .value(asToken(3L), token1)
                .build();
        given(readableStates2.<TokenID, Token>get(TOKENS_STATE_ID)).willReturn(readableToken);
        final var readableTokenStore = new ReadableTokenStoreImpl(readableStates2, readableEntityCounters);

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
        final var readableTokenRel = MapReadableKVState.<EntityIDPair, TokenRelation>builder(
                        TOKEN_RELS_STATE_ID, TOKEN_RELS_STATE_LABEL)
                .value(
                        EntityIDPair.newBuilder()
                                .accountId(id)
                                .tokenId(asToken(3L))
                                .build(),
                        tokenRelation)
                .build();
        given(readableStates3.<EntityIDPair, TokenRelation>get(TOKEN_RELS_STATE_ID))
                .willReturn(readableTokenRel);
        final var readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates3, readableEntityCounters);

        final var query = createGetAccountBalanceQuery(accountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(ReadableAccountStore);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(context.createStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);

        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.maxRelsPerInfoQuery", 2)
                .withValue("tokens.balancesInQueries.enabled", balancesInQueriesEnabled)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        final var accountBalanceResponse = response.cryptogetAccountBalance();
        assertEquals(ResponseCodeEnum.OK, accountBalanceResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo.tinybarBalance(), accountBalanceResponse.balance());
        if (balancesInQueriesEnabled) {
            assertIterableEquals(getExpectedTokenBalance(3L), accountBalanceResponse.tokenBalances());
            verify(balanceSpeedometer).update(1);
        } else {
            assertThat(accountBalanceResponse.tokenBalances()).isEmpty();
            verify(balanceSpeedometer, never()).update(anyDouble());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("check maxRelsPerInfoQuery in TokenConfig is correctly handled")
    void checkConfigmaxRelsPerInfoQuery(boolean balancesInQueriesEnabled) {
        givenValidAccount(accountNum);
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedInfo = getExpectedInfo();

        final var readableAccounts = MapReadableKVState.<AccountID, Account>builder(
                        ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL)
                .value(id, account)
                .build();
        given(readableStates1.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        ReadableAccountStore ReadableAccountStore =
                new ReadableAccountStoreImpl(readableStates1, readableEntityCounters);

        lenient().when(token1.decimals()).thenReturn(100); // only needed when balancesInQueriesEnabled is true
        lenient().when(token2.decimals()).thenReturn(50); // only needed when balancesInQueriesEnabled is true
        final var readableToken = MapReadableKVState.<TokenID, Token>builder(TOKENS_STATE_ID, TOKENS_STATE_LABEL)
                .value(asToken(3L), token1)
                .value(asToken(4L), token2)
                .value(asToken(5L), token3)
                .build();
        given(readableStates2.<TokenID, Token>get(TOKENS_STATE_ID)).willReturn(readableToken);
        final var readableTokenStore = new ReadableTokenStoreImpl(readableStates2, readableEntityCounters);

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
        final var readableTokenRel = MapReadableKVState.<EntityIDPair, TokenRelation>builder(
                        TOKEN_RELS_STATE_ID, TOKEN_RELS_STATE_LABEL)
                .value(
                        EntityIDPair.newBuilder()
                                .accountId(id)
                                .tokenId(asToken(3L))
                                .build(),
                        tokenRelation1)
                .value(
                        EntityIDPair.newBuilder()
                                .accountId(id)
                                .tokenId(asToken(4L))
                                .build(),
                        tokenRelation2)
                .value(
                        EntityIDPair.newBuilder()
                                .accountId(id)
                                .tokenId(asToken(5L))
                                .build(),
                        tokenRelation3)
                .build();
        given(readableStates3.<EntityIDPair, TokenRelation>get(TOKEN_RELS_STATE_ID))
                .willReturn(readableTokenRel);
        final var readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates3, readableEntityCounters);

        final var query = createGetAccountBalanceQuery(accountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(ReadableAccountStore);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(context.createStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);

        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.maxRelsPerInfoQuery", 2)
                .withValue("tokens.balancesInQueries.enabled", balancesInQueriesEnabled)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        final var accountBalanceResponse = response.cryptogetAccountBalance();
        assertEquals(ResponseCodeEnum.OK, accountBalanceResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo.tinybarBalance(), accountBalanceResponse.balance());
        if (balancesInQueriesEnabled) {
            assertIterableEquals(getExpectedTokenBalances(), accountBalanceResponse.tokenBalances());
            assertEquals(2, accountBalanceResponse.tokenBalances().size());
            verify(balanceSpeedometer).update(2);
        } else {
            assertThat(accountBalanceResponse.tokenBalances()).isEmpty();
            verify(balanceSpeedometer, never()).update(anyDouble());
        }
    }

    private Account getExpectedInfo() {
        return Account.newBuilder().accountId(id).tinybarBalance(payerBalance).build();
    }

    private List<TokenBalance> getExpectedTokenBalance(long tokenNum) {
        var ret = new ArrayList<TokenBalance>();
        final var tokenBalance = TokenBalance.newBuilder()
                .tokenId(TokenID.newBuilder().tokenNum(tokenNum).build())
                .balance(1000)
                .decimals(100)
                .build();
        ret.add(tokenBalance);
        return ret;
    }

    private List<TokenBalance> getExpectedTokenBalances() {
        var ret = new ArrayList<TokenBalance>();
        final var tokenBalance1 = TokenBalance.newBuilder()
                .tokenId(TokenID.newBuilder().tokenNum(3L).build())
                .balance(1000)
                .decimals(100)
                .build();
        final var tokenBalance2 = TokenBalance.newBuilder()
                .tokenId(TokenID.newBuilder().tokenNum(4L).build())
                .balance(100)
                .decimals(50)
                .build();
        ret.add(tokenBalance1);
        ret.add(tokenBalance2);
        return ret;
    }

    private Query createGetAccountBalanceQuery(final long accountId) {
        final var data = CryptoGetAccountBalanceQuery.newBuilder()
                .accountID(idFactory.newAccountId(accountId))
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().cryptogetAccountBalance(data).build();
    }

    private Query createGetAccountBalanceQueryWithInvalidHeader(final long accountId) {
        final var data = CryptoGetAccountBalanceQuery.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(accountId).build())
                .header((QueryHeader) null)
                .build();

        return Query.newBuilder().cryptogetAccountBalance(data).build();
    }

    private Query createGetAccountBalanceQueryWithContract(final long contractId) {
        final var data = CryptoGetAccountBalanceQuery.newBuilder()
                .contractID(idFactory.newContractId(contractId))
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().cryptogetAccountBalance(data).build();
    }

    private Query createEmptyGetAccountBalanceQuery() {
        final var data = CryptoGetAccountBalanceQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().cryptogetAccountBalance(data).build();
    }
}
