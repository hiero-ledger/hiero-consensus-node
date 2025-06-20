// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenFreezeStatus;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenKycStatus;
import com.hedera.hapi.node.base.TokenRelationship;
import com.hedera.hapi.node.state.token.AccountApprovalForAllAllowance;
import com.hedera.hapi.node.state.token.AccountCryptoAllowance;
import com.hedera.hapi.node.state.token.AccountFungibleTokenAllowance;
import com.hedera.hapi.node.token.GetAccountDetailsQuery;
import com.hedera.hapi.node.token.GetAccountDetailsResponse;
import com.hedera.hapi.node.token.GetAccountDetailsResponse.AccountDetails;
import com.hedera.hapi.node.token.GrantedCryptoAllowance;
import com.hedera.hapi.node.token.GrantedNftAllowance;
import com.hedera.hapi.node.token.GrantedTokenAllowance;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkGetAccountDetailsHandler;
import com.hedera.node.app.service.networkadmin.impl.utils.NetworkAdminServiceUtil;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class NetworkGetAccountDetailsHandlerTest extends NetworkAdminHandlerTestBase {
    @Mock
    private QueryContext context;

    private NetworkGetAccountDetailsHandler networkGetAccountDetailsHandler;
    private CryptoOpsUsage cryptoOpsUsage;

    @BeforeEach
    void setUp() {
        this.cryptoOpsUsage = new CryptoOpsUsage();
        networkGetAccountDetailsHandler = new NetworkGetAccountDetailsHandler(cryptoOpsUsage);
        final var configuration = HederaTestConfigBuilder.createConfig();
        lenient().when(context.configuration()).thenReturn(configuration);
    }

    @Test
    void extractsHeader() {
        final var query = createGetAccountDetailsQuery(accountId);
        final var header = networkGetAccountDetailsHandler.extractHeader(query);
        final var op = query.accountDetailsOrThrow();
        assertEquals(op.header(), header);
    }

    @Test
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();
        final var response = networkGetAccountDetailsHandler.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .accountDetails(GetAccountDetailsResponse.newBuilder().header(responseHeader))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    void requiresPayment() {
        assertTrue(networkGetAccountDetailsHandler.requiresNodePayment(ResponseType.ANSWER_ONLY));
        assertTrue(networkGetAccountDetailsHandler.requiresNodePayment(ResponseType.ANSWER_STATE_PROOF));
        assertFalse(networkGetAccountDetailsHandler.requiresNodePayment(ResponseType.COST_ANSWER));
        assertFalse(networkGetAccountDetailsHandler.requiresNodePayment(ResponseType.COST_ANSWER_STATE_PROOF));
    }

    @Test
    void needsAnswerOnlyCostForCostAnswer() {
        assertFalse(networkGetAccountDetailsHandler.needsAnswerOnlyCost(ResponseType.ANSWER_ONLY));
        assertFalse(networkGetAccountDetailsHandler.needsAnswerOnlyCost(ResponseType.ANSWER_STATE_PROOF));
        assertTrue(networkGetAccountDetailsHandler.needsAnswerOnlyCost(ResponseType.COST_ANSWER));
        assertFalse(networkGetAccountDetailsHandler.needsAnswerOnlyCost(ResponseType.COST_ANSWER_STATE_PROOF));
    }

    @Test
    void validatesQueryWhenValidAccount() throws Throwable {

        final var query = createGetAccountDetailsQuery(accountId);
        given(context.query()).willReturn(query);
        given(context.createStore(ReadableAccountStore.class)).willReturn(readableAccountStore);

        assertThatCode(() -> networkGetAccountDetailsHandler.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void validatesQueryWhenNoAccount() throws Throwable {

        final var query = createEmptysQuery();
        given(context.query()).willReturn(query);

        assertThrowsPreCheck(() -> networkGetAccountDetailsHandler.validate(context), INVALID_ACCOUNT_ID);
    }

    @Test
    void validatesQueryWhenDeletedAccount() {
        givenValidAccount(true, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        refreshStoresWithEntitiesOnlyInReadable();
        final var data = GetAccountDetailsQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .accountId(accountId)
                .build();
        final var query = Query.newBuilder().accountDetails(data).build();
        given(context.query()).willReturn(query);
        given(context.createStore(ReadableAccountStore.class)).willReturn(readableAccountStore);

        assertDoesNotThrow(() -> networkGetAccountDetailsHandler.validate(context));
    }

    @Test
    void getsResponseIfFailedResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();

        final var query = createGetAccountDetailsQuery(accountId);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableAccountStore);

        final var response = networkGetAccountDetailsHandler.findResponse(context, responseHeader);
        final var op = response.accountDetailsOrThrow();
        assertEquals(ResponseCodeEnum.FAIL_FEE, op.header().nodeTransactionPrecheckCode());
        assertNull(op.accountDetails());
    }

    @Test
    void getsResponseIsEmptyWhenAccountNotExist() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();

        final var query = createGetAccountDetailsQuery(
                AccountID.newBuilder().accountNum(567L).build());
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableAccountStore);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(context.createStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);

        final var response = networkGetAccountDetailsHandler.findResponse(context, responseHeader);
        final var accountDetailsResponse = response.accountDetailsOrThrow();
        assertEquals(
                ResponseCodeEnum.FAIL_INVALID, accountDetailsResponse.header().nodeTransactionPrecheckCode());
        assertNull(accountDetailsResponse.accountDetails());
    }

    @Test
    void getsResponseIfAccountMarkDeletedOkResponse() {
        givenValidAccount(true, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        refreshStoresWithEntitiesOnlyInReadable();
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedInfo = getExpectedInfo(
                true,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());

        final var query = createGetAccountDetailsQuery(accountId);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableAccountStore);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(context.createStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);

        final var response = networkGetAccountDetailsHandler.findResponse(context, responseHeader);
        final var accountDetailsResponse = response.accountDetailsOrThrow();
        assertEquals(ResponseCodeEnum.OK, accountDetailsResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, accountDetailsResponse.accountDetails());
    }

    @Test
    void getsResponseIfOkResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedInfo = getExpectedInfo(
                false,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());

        final var query = createGetAccountDetailsQuery(accountId);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableAccountStore);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(context.createStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);

        final var response = networkGetAccountDetailsHandler.findResponse(context, responseHeader);
        final var accountDetailsResponse = response.accountDetailsOrThrow();
        assertEquals(ResponseCodeEnum.OK, accountDetailsResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, accountDetailsResponse.accountDetails());
    }

    @Test
    void getsResponseWithTokenRelations() {
        givenValidAccount(false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        givenValidFungibleToken();
        givenValidNonFungibleToken();
        givenFungibleTokenRelation();
        givenNonFungibleTokenRelation();
        refreshStoresWithEntitiesOnlyInReadable();
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        List<TokenRelationship> tokenRelationships = new ArrayList<>();
        var tokenRelation = TokenRelationship.newBuilder()
                .tokenId(nonFungibleTokenId)
                .balance(1000L)
                .decimals(1000)
                .symbol(tokenSymbol)
                .kycStatus(TokenKycStatus.KYC_NOT_APPLICABLE)
                .freezeStatus(TokenFreezeStatus.FREEZE_NOT_APPLICABLE)
                .automaticAssociation(true)
                .build();
        tokenRelationships.add(tokenRelation);
        final var expectedInfo = getExpectedInfo(
                false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), tokenRelationships);
        final var query = createGetAccountDetailsQuery(accountId);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableAccountStore);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(context.createStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);

        final var response = networkGetAccountDetailsHandler.findResponse(context, responseHeader);
        final var accountDetailsResponse = response.accountDetailsOrThrow();
        assertEquals(ResponseCodeEnum.OK, accountDetailsResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, accountDetailsResponse.accountDetails());
    }

    @Test
    void getsResponseIfOkResponseWhenAllowancesListAsExpected() {
        List<AccountCryptoAllowance> cryptoAllowancesList = new ArrayList<>();
        AccountCryptoAllowance cryptoAllowance = new AccountCryptoAllowance(
                AccountID.newBuilder().accountNum(123L).build(), 456L);
        cryptoAllowancesList.add(cryptoAllowance);
        List<AccountApprovalForAllAllowance> accountApprovalForAllAllowanceList = new ArrayList<>();
        AccountApprovalForAllAllowance accountApprovalForAllAllowance = new AccountApprovalForAllAllowance(
                TokenID.newBuilder().tokenNum(456L).build(),
                AccountID.newBuilder().accountNum(567L).build());
        accountApprovalForAllAllowanceList.add(accountApprovalForAllAllowance);
        List<AccountFungibleTokenAllowance> accountFungibleTokenAllowanceList = new ArrayList<>();
        AccountFungibleTokenAllowance accountFungibleTokenAllowance = new AccountFungibleTokenAllowance(
                TokenID.newBuilder().tokenNum(789L).build(),
                AccountID.newBuilder().accountNum(890L).build(),
                901L);
        accountFungibleTokenAllowanceList.add(accountFungibleTokenAllowance);
        givenValidAccount(
                false, cryptoAllowancesList, accountApprovalForAllAllowanceList, accountFungibleTokenAllowanceList);
        refreshStoresWithEntitiesOnlyInReadable();
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();

        List<GrantedCryptoAllowance> grantedCryptoAllowancesList = new ArrayList<>();
        GrantedCryptoAllowance grantedCryptoAllowance = new GrantedCryptoAllowance(
                AccountID.newBuilder().accountNum(123L).build(), 456L);
        grantedCryptoAllowancesList.add(grantedCryptoAllowance);
        List<GrantedNftAllowance> grantedNftAllowancesList = new ArrayList<>();
        GrantedNftAllowance grantedNftAllowance = new GrantedNftAllowance(
                TokenID.newBuilder().tokenNum(456L).build(),
                AccountID.newBuilder().accountNum(567L).build());
        grantedNftAllowancesList.add(grantedNftAllowance);
        List<GrantedTokenAllowance> grantedTokenAllowancesList = new ArrayList<>();
        GrantedTokenAllowance grantedTokenAllowance = new GrantedTokenAllowance(
                TokenID.newBuilder().tokenNum(789L).build(),
                AccountID.newBuilder().accountNum(890L).build(),
                901L);
        grantedTokenAllowancesList.add(grantedTokenAllowance);
        final var expectedInfo = getExpectedInfo(
                false,
                grantedCryptoAllowancesList,
                grantedNftAllowancesList,
                grantedTokenAllowancesList,
                Collections.emptyList());

        final var query = createGetAccountDetailsQuery(accountId);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableAccountStore);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(context.createStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);

        final var response = networkGetAccountDetailsHandler.findResponse(context, responseHeader);
        final var accountDetailsResponse = response.accountDetailsOrThrow();
        assertEquals(ResponseCodeEnum.OK, accountDetailsResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, accountDetailsResponse.accountDetails());
    }

    private AccountDetails getExpectedInfo(
            boolean deleted,
            List<GrantedCryptoAllowance> grantedCryptoAllowances,
            List<GrantedNftAllowance> grantedNftAllowances,
            List<GrantedTokenAllowance> grantedTokenAllowances,
            List<TokenRelationship> tokenRelationships) {
        return AccountDetails.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(accountNum).build())
                .contractAccountId(NetworkAdminServiceUtil.asHexedEvmAddress(
                        AccountID.newBuilder().accountNum(accountNum).build()))
                .deleted(deleted)
                .balance(payerBalance)
                .receiverSigRequired(true)
                .expirationTime(Timestamp.newBuilder().seconds(1_234_567L).build())
                .autoRenewPeriod(Duration.newBuilder().seconds(72000).build())
                .memo("testAccount")
                .ownedNfts(2)
                .maxAutomaticTokenAssociations(10)
                .alias(alias.alias())
                .ledgerId(ledgerId)
                .grantedCryptoAllowances(grantedCryptoAllowances)
                .grantedNftAllowances(grantedNftAllowances)
                .grantedTokenAllowances(grantedTokenAllowances)
                .tokenRelationships(tokenRelationships)
                .build();
    }

    private Query createGetAccountDetailsQuery(final AccountID id) {
        final var data = GetAccountDetailsQuery.newBuilder()
                .accountId(id)
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().accountDetails(data).build();
    }

    private Query createEmptysQuery() {
        final var data = GetAccountDetailsQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().accountDetails(data).build();
    }
}
