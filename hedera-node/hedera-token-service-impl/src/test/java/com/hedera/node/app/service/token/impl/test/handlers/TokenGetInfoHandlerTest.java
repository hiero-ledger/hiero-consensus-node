// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.hapi.node.base.TokenFreezeStatus.FREEZE_NOT_APPLICABLE;
import static com.hedera.hapi.node.base.TokenFreezeStatus.FROZEN;
import static com.hedera.hapi.node.base.TokenFreezeStatus.UNFROZEN;
import static com.hedera.hapi.node.base.TokenKycStatus.GRANTED;
import static com.hedera.hapi.node.base.TokenKycStatus.KYC_NOT_APPLICABLE;
import static com.hedera.hapi.node.base.TokenKycStatus.REVOKED;
import static com.hedera.hapi.node.base.TokenPauseStatus.PAUSED;
import static com.hedera.hapi.node.base.TokenPauseStatus.PAUSE_NOT_APPLICABLE;
import static com.hedera.hapi.node.base.TokenPauseStatus.UNPAUSED;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_KEY;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenGetInfoQuery;
import com.hedera.hapi.node.token.TokenGetInfoResponse;
import com.hedera.hapi.node.token.TokenInfo;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.service.token.impl.handlers.TokenGetInfoHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenGetInfoHandlerTest extends CryptoTokenHandlerTestBase {

    @Mock(strictness = LENIENT)
    private QueryContext context;

    private TokenGetInfoHandler subject;

    @BeforeEach
    public void setUp() {
        super.setUp();
        subject = new TokenGetInfoHandler();
    }

    @Test
    void extractsHeader() {
        final var query = createTokenGetInfoQuery(fungibleTokenId);
        final var header = subject.extractHeader(query);
        final var op = query.tokenGetInfoOrThrow();
        assertEquals(op.header(), header);
    }

    @Test
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();
        final var response = subject.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .tokenGetInfo(TokenGetInfoResponse.newBuilder().header(responseHeader))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    void validatesQueryWhenValidToken() {
        final var query = createTokenGetInfoQuery(fungibleTokenId);
        given(context.query()).willReturn(query);
        given(context.createStore(ReadableTokenStore.class)).willReturn(readableTokenStore);

        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void validatesQueryIfInvalidToken() {
        final var state = MapReadableKVState.<TokenID, Token>builder(TokenService.NAME, TOKENS_KEY)
                .build();
        given(readableStates.<TokenID, Token>get(TOKENS_KEY)).willReturn(state);
        final var store = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);

        final var query = createTokenGetInfoQuery(fungibleTokenId);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOKEN_ID));
    }

    @Test
    void validatesQueryIfInvalidTokenInTrans() {
        final var state = MapReadableKVState.<TokenID, Token>builder(TokenService.NAME, TOKENS_KEY)
                .build();
        given(readableStates.<TokenID, Token>get(TOKENS_KEY)).willReturn(state);
        final var store = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);

        final var query = createEmptyTokenGetInfoQuery();
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOKEN_ID));
    }

    @Test
    void getsResponseIfFailedResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();

        final var query = createTokenGetInfoQuery(fungibleTokenId);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);

        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.maxRelsPerInfoQuery", 1000)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        final var op = response.tokenGetInfoOrThrow();
        assertEquals(ResponseCodeEnum.FAIL_FEE, op.header().nodeTransactionPrecheckCode());
    }

    @Test
    void getsResponseIfInvalidToken() {
        final var state = MapReadableKVState.<TokenID, Token>builder(TokenService.NAME, TOKENS_KEY)
                .build();
        given(readableStates.<TokenID, Token>get(TOKENS_KEY)).willReturn(state);
        final var store = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);

        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();

        final var query = createTokenGetInfoQuery(fungibleTokenId);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(store);

        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.maxRelsPerInfoQuery", 1000)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        final var op = response.tokenGetInfoOrThrow();
        assertNull(op.tokenInfo());
        assertEquals(ResponseCodeEnum.INVALID_TOKEN_ID, op.header().nodeTransactionPrecheckCode());
    }

    @Test
    void getsResponseIfOkResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedInfo = getExpectedInfo();

        checkResponse(responseHeader, expectedInfo, readableTokenStore);
    }

    @Test
    void getsResponseIfOkWithAnswerOnlyHead() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .responseType(ANSWER_ONLY)
                .build();
        final var expectedInfo = getExpectedInfo();

        checkResponse(responseHeader, expectedInfo, readableTokenStore);
    }

    @Test
    void getsResponseIfOkWithDefaultKey() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedInfo = getExpectInfoDefaultKeys();

        fungibleToken = setFungibleTokenKeys();
        final var state = MapReadableKVState.<TokenID, Token>builder(TokenService.NAME, TOKENS_KEY)
                .value(fungibleTokenId, fungibleToken)
                .build();
        given(readableStates.<TokenID, Token>get(TOKENS_KEY)).willReturn(state);
        final var store = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);

        checkResponse(responseHeader, expectedInfo, store);
    }

    @Test
    void getsResponseIfOkWithDefaultStatus() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedInfo = getExpectInfoDefaultStatus();

        fungibleToken = setFungibleTokenDefaultStatus();
        final var state = MapReadableKVState.<TokenID, Token>builder(TokenService.NAME, TOKENS_KEY)
                .value(fungibleTokenId, fungibleToken)
                .build();
        given(readableStates.<TokenID, Token>get(TOKENS_KEY)).willReturn(state);
        final var store = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);

        checkResponse(responseHeader, expectedInfo, store);
    }

    private void checkResponse(
            final ResponseHeader responseHeader, final TokenInfo expectedInfo, ReadableTokenStore readableTokenStore) {
        final var query = createTokenGetInfoQuery(fungibleTokenId);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);

        final var config =
                HederaTestConfigBuilder.create().withValue("ledger.id", "0x03").getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        final var tokenInfoResponse = response.tokenGetInfoOrThrow();
        assertEquals(ResponseCodeEnum.OK, tokenInfoResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, tokenInfoResponse.tokenInfo());
    }

    private TokenInfo getExpectedInfo() {
        return TokenInfo.newBuilder()
                .ledgerId(new BytesConverter().convert("0x03"))
                .tokenType(fungibleToken.tokenType())
                .supplyType(fungibleToken.supplyType())
                .tokenId(fungibleTokenId)
                .deleted(fungibleToken.deleted())
                .symbol(fungibleToken.symbol())
                .name(fungibleToken.name())
                .memo(fungibleToken.memo())
                .treasury(fungibleToken.treasuryAccountId())
                .totalSupply(fungibleToken.totalSupply())
                .maxSupply(fungibleToken.maxSupply())
                .decimals(fungibleToken.decimals())
                .expiry(Timestamp.newBuilder().seconds(fungibleToken.expirationSecond()))
                .adminKey(fungibleToken.adminKey())
                .kycKey(fungibleToken.kycKey())
                .freezeKey(fungibleToken.freezeKey())
                .wipeKey(fungibleToken.wipeKey())
                .supplyKey(fungibleToken.supplyKey())
                .feeScheduleKey(fungibleToken.feeScheduleKey())
                .pauseKey(fungibleToken.pauseKey())
                .autoRenewPeriod(Duration.newBuilder().seconds(fungibleToken.autoRenewSeconds()))
                .autoRenewAccount(fungibleToken.autoRenewAccountId())
                .defaultFreezeStatus(fungibleToken.accountsFrozenByDefault() ? FROZEN : UNFROZEN)
                .defaultKycStatus(fungibleToken.accountsKycGrantedByDefault() ? GRANTED : REVOKED)
                .pauseStatus(fungibleToken.paused() ? PAUSED : UNPAUSED)
                .customFees(fungibleToken.customFees())
                .metadata(fungibleToken.metadata())
                .metadataKey(fungibleToken.metadataKey())
                .build();
    }

    private TokenInfo getExpectInfoDefaultKeys() {
        final var info = getExpectedInfo();
        return info.copyBuilder()
                .supplyKey((Key) null)
                .wipeKey((Key) null)
                .freezeKey((Key) null)
                .kycKey((Key) null)
                .adminKey((Key) null)
                .feeScheduleKey((Key) null)
                .pauseKey((Key) null)
                .metadataKey((Key) null)
                .defaultFreezeStatus(FREEZE_NOT_APPLICABLE)
                .defaultKycStatus(KYC_NOT_APPLICABLE)
                .pauseStatus(PAUSE_NOT_APPLICABLE)
                .build();
    }

    private TokenInfo getExpectInfoDefaultStatus() {
        final var info = getExpectedInfo();
        return info.copyBuilder()
                .defaultFreezeStatus(FROZEN)
                .defaultKycStatus(GRANTED)
                .pauseStatus(PAUSED)
                .build();
    }

    private Token setFungibleTokenKeys() {
        return fungibleToken
                .copyBuilder()
                .supplyKey(Key.DEFAULT)
                .wipeKey(Key.DEFAULT)
                .freezeKey(Key.DEFAULT)
                .kycKey(Key.DEFAULT)
                .adminKey(Key.DEFAULT)
                .feeScheduleKey(Key.DEFAULT)
                .pauseKey(Key.DEFAULT)
                .metadataKey(Key.DEFAULT)
                .build();
    }

    private Token setFungibleTokenDefaultStatus() {
        return fungibleToken
                .copyBuilder()
                .accountsFrozenByDefault(true)
                .accountsKycGrantedByDefault(true)
                .paused(true)
                .build();
    }

    private Query createTokenGetInfoQuery(final TokenID tokenId) {
        final var data = TokenGetInfoQuery.newBuilder()
                .token(tokenId)
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().tokenGetInfo(data).build();
    }

    private Query createEmptyTokenGetInfoQuery() {
        final var data = TokenGetInfoQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().tokenGetInfo(data).build();
    }
}
