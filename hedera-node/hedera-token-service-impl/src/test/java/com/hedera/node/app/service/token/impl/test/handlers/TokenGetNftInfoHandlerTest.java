// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.NFTS_KEY;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.token.TokenGetNftInfoQuery;
import com.hedera.hapi.node.token.TokenGetNftInfoResponse;
import com.hedera.hapi.node.token.TokenNftInfo;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.ReadableNftStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.service.token.impl.handlers.TokenGetNftInfoHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenGetNftInfoHandlerTest extends CryptoTokenHandlerTestBase {

    @Mock(strictness = LENIENT)
    private QueryContext context;

    private TokenGetNftInfoHandler subject;

    @BeforeEach
    public void setUp() {
        super.setUp();
        subject = new TokenGetNftInfoHandler();
    }

    @Test
    void extractsHeader() {
        final var query = createTokenGetNftInfoQuery(nftIdSl1);
        final var header = subject.extractHeader(query);
        final var op = query.tokenGetNftInfoOrThrow();
        assertEquals(op.header(), header);
    }

    @Test
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();
        final var response = subject.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .tokenGetNftInfo(TokenGetNftInfoResponse.newBuilder().header(responseHeader))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    void validatesQueryWhenValidNft() {
        final var query = createTokenGetNftInfoQuery(nftIdSl1);
        given(context.query()).willReturn(query);
        given(context.createStore(ReadableNftStore.class)).willReturn(readableNftStore);

        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void validatesQueryIfInvalidNft() {
        final var state = MapReadableKVState.<NftID, Nft>builder(TokenService.NAME, NFTS_KEY)
                .build();
        given(readableStates.<NftID, Nft>get(NFTS_KEY)).willReturn(state);
        final var store = new ReadableNftStoreImpl(readableStates, readableEntityCounters);

        final var query = createTokenGetNftInfoQuery(nftIdSl1);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableNftStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_NFT_ID));
    }

    @Test
    void validatesQueryIfInvalidNftTokenId() {
        final var query = createTokenGetNftInfoQueryInvalidTokenId(nftIdSl1);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableNftStore.class)).thenReturn(readableNftStore);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOKEN_ID));
    }

    @Test
    void validatesQueryIfInvalidNftSerialNumb() {
        final var query = createTokenGetNftInfoQueryInvalidSerialNum(nftIdSl1);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableNftStore.class)).thenReturn(readableNftStore);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER));
    }

    @Test
    void validatesQueryIfInvalidNftInTrans() {
        final var state = MapReadableKVState.<NftID, Nft>builder(TokenService.NAME, NFTS_KEY)
                .build();
        given(readableStates.<NftID, Nft>get(NFTS_KEY)).willReturn(state);
        final var store = new ReadableNftStoreImpl(readableStates, readableEntityCounters);
        ;

        final var query = createEmptyTokenGetNftInfoQuery();
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableNftStore.class)).thenReturn(store);

        assertThrows(NullPointerException.class, () -> subject.validate(context));
    }

    @Test
    void getsResponseIfFailedResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();

        final var query = createTokenGetNftInfoQuery(nftIdSl1);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableNftStore.class)).thenReturn(readableNftStore);

        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.maxRelsPerInfoQuery", 1000)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        final var op = response.tokenGetNftInfoOrThrow();
        assertEquals(ResponseCodeEnum.FAIL_FEE, op.header().nodeTransactionPrecheckCode());
    }

    @Test
    void getsResponseIfInvalidNft() {
        final var state = MapReadableKVState.<NftID, Nft>builder(TokenService.NAME, NFTS_KEY)
                .build();
        given(readableStates.<NftID, Nft>get(NFTS_KEY)).willReturn(state);
        final var store = new ReadableNftStoreImpl(readableStates, readableEntityCounters);
        ;
        final var tokenStore = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);

        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();

        final var query = createTokenGetNftInfoQuery(nftIdSl1);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableNftStore.class)).thenReturn(store);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(tokenStore);

        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.maxRelsPerInfoQuery", 1000)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        final var op = response.tokenGetNftInfoOrThrow();
        assertNull(op.nft());
        assertEquals(ResponseCodeEnum.INVALID_NFT_ID, op.header().nodeTransactionPrecheckCode());
    }

    @Test
    void getsResponseIfOkResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedInfo = getExpectedInfo();

        nftSl1 = nftSl1.copyBuilder()
                .spenderId(spenderId)
                .mintTime(consensusTimestamp)
                .metadata(Bytes.wrap(evmAddress))
                .build();
        final var state = MapReadableKVState.<NftID, Nft>builder(TokenService.NAME, NFTS_KEY)
                .value(nftIdSl1, nftSl1)
                .build();
        given(readableStates.<NftID, Nft>get(NFTS_KEY)).willReturn(state);
        final var store = new ReadableNftStoreImpl(readableStates, readableEntityCounters);
        ;
        final var tokenStore = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);

        checkResponse(responseHeader, expectedInfo, store, tokenStore);
    }

    @Test
    void getsResponseIfOkWithAnswerOnlyHead() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .responseType(ANSWER_ONLY)
                .build();
        final var expectedInfo = getExpectedInfo();
        nftSl1 = nftSl1.copyBuilder()
                .spenderId(spenderId)
                .mintTime(consensusTimestamp)
                .metadata(Bytes.wrap(evmAddress))
                .build();
        final var state = MapReadableKVState.<NftID, Nft>builder(TokenService.NAME, NFTS_KEY)
                .value(nftIdSl1, nftSl1)
                .build();
        given(readableStates.<NftID, Nft>get(NFTS_KEY)).willReturn(state);
        final var store = new ReadableNftStoreImpl(readableStates, readableEntityCounters);
        ;
        final var tokenStore = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);

        checkResponse(responseHeader, expectedInfo, store, tokenStore);
    }

    private void checkResponse(
            final ResponseHeader responseHeader,
            final TokenNftInfo expectedInfo,
            ReadableNftStore ReadableNftStore,
            ReadableTokenStore ReadableTokenStore) {
        final var query = createTokenGetNftInfoQuery(nftIdSl1);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableNftStore.class)).thenReturn(ReadableNftStore);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(ReadableTokenStore);
        final var config =
                HederaTestConfigBuilder.create().withValue("ledger.id", "0x03").getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        final var nftInfoResponse = response.tokenGetNftInfoOrThrow();
        assertEquals(ResponseCodeEnum.OK, nftInfoResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, nftInfoResponse.nft());
    }

    private TokenNftInfo getExpectedInfo() {
        return TokenNftInfo.newBuilder()
                .ledgerId(new BytesConverter().convert("0x03"))
                .nftID(NftID.newBuilder()
                        .tokenId(
                                TokenID.newBuilder().tokenNum(nftIdSl1.tokenId().tokenNum()))
                        .serialNumber(nftIdSl1.serialNumber()))
                .accountID(ownerId)
                .creationTime(consensusTimestamp)
                .metadata(Bytes.wrap(evmAddress))
                .spenderId(spenderId)
                .build();
    }

    private Query createTokenGetNftInfoQuery(final NftID tokenID) {
        final var data = TokenGetNftInfoQuery.newBuilder()
                .nftID(NftID.newBuilder().tokenId(tokenID.tokenId()).serialNumber(tokenID.serialNumber()))
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().tokenGetNftInfo(data).build();
    }

    private Query createTokenGetNftInfoQueryInvalidTokenId(final NftID tokenID) {
        final var data = TokenGetNftInfoQuery.newBuilder()
                .nftID(NftID.newBuilder().serialNumber(tokenID.serialNumber()))
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().tokenGetNftInfo(data).build();
    }

    private Query createTokenGetNftInfoQueryInvalidSerialNum(final NftID tokenID) {
        final var data = TokenGetNftInfoQuery.newBuilder()
                .nftID(NftID.newBuilder().tokenId(tokenID.tokenId()).serialNumber(-1L))
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().tokenGetNftInfo(data).build();
    }

    private Query createEmptyTokenGetNftInfoQuery() {
        final var data = TokenGetNftInfoQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().tokenGetNftInfo(data).build();
    }
}
