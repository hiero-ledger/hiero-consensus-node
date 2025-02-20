// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.token.impl.handlers.CryptoAddLiveHashHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoApproveAllowanceHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoCreateHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteAllowanceHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteLiveHashHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountBalanceHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountInfoHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountRecordsHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetLiveHashHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetStakersHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoUpdateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenAccountWipeHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenAirdropHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenAssociateToAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenBurnHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenCancelAirdropHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenClaimAirdropHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenCreateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenDeleteHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenDissociateFromAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenFeeScheduleUpdateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenFreezeAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGetAccountNftInfosHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGetInfoHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGetNftInfoHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGetNftInfosHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGrantKycToAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenHandlers;
import com.hedera.node.app.service.token.impl.handlers.TokenMintHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenPauseHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenRejectHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenRevokeKycFromAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUnfreezeAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUnpauseHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUpdateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUpdateNftsHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TokenHandlersTest {

    private CryptoCreateHandler cryptoCreateHandler;
    private CryptoUpdateHandler cryptoUpdateHandler;
    private CryptoTransferHandler cryptoTransferHandler;
    private CryptoDeleteHandler cryptoDeleteHandler;
    private CryptoApproveAllowanceHandler cryptoApproveAllowanceHandler;
    private CryptoDeleteAllowanceHandler cryptoDeleteAllowanceHandler;
    private CryptoAddLiveHashHandler cryptoAddLiveHashHandler;
    private CryptoDeleteLiveHashHandler cryptoDeleteLiveHashHandler;
    private TokenCreateHandler tokenCreateHandler;
    private TokenUpdateHandler tokenUpdateHandler;
    private TokenMintHandler tokenMintHandler;
    private TokenBurnHandler tokenBurnHandler;
    private TokenDeleteHandler tokenDeleteHandler;
    private TokenAccountWipeHandler tokenAccountWipeHandler;
    private TokenFreezeAccountHandler tokenFreezeAccountHandler;
    private TokenUnfreezeAccountHandler tokenUnfreezeAccountHandler;
    private TokenGrantKycToAccountHandler tokenGrantKycToAccountHandler;
    private TokenRevokeKycFromAccountHandler tokenRevokeKycFromAccountHandler;
    private TokenAssociateToAccountHandler tokenAssociateToAccountHandler;
    private TokenDissociateFromAccountHandler tokenDissociateFromAccountHandler;
    private TokenFeeScheduleUpdateHandler tokenFeeScheduleUpdateHandler;
    private TokenPauseHandler tokenPauseHandler;
    private TokenUnpauseHandler tokenUnpauseHandler;
    private CryptoGetAccountBalanceHandler cryptoGetAccountBalanceHandler;
    private CryptoGetAccountInfoHandler cryptoGetAccountInfoHandler;
    private CryptoGetAccountRecordsHandler cryptoGetAccountRecordsHandler;
    private CryptoGetLiveHashHandler cryptoGetLiveHashHandler;
    private CryptoGetStakersHandler cryptoGetStakersHandler;
    private TokenGetInfoHandler tokenGetInfoHandler;
    private TokenGetAccountNftInfosHandler tokenGetAccountNftInfosHandler;
    private TokenGetNftInfoHandler tokenGetNftInfoHandler;
    private TokenGetNftInfosHandler tokenGetNftInfosHandler;
    private TokenUpdateNftsHandler tokenUpdateNftsHandler;
    private TokenRejectHandler tokenRejectHandler;
    private TokenCancelAirdropHandler tokenCancelAirdropHandler;
    private TokenClaimAirdropHandler tokenClaimAirdropHandler;
    private TokenAirdropHandler tokenAirdropHandler;
    private TokenHandlers tokenHandlers;

    @BeforeEach
    public void setUp() {
        cryptoCreateHandler = mock(CryptoCreateHandler.class);
        cryptoUpdateHandler = mock(CryptoUpdateHandler.class);
        cryptoTransferHandler = mock(CryptoTransferHandler.class);
        cryptoDeleteHandler = mock(CryptoDeleteHandler.class);
        cryptoApproveAllowanceHandler = mock(CryptoApproveAllowanceHandler.class);
        cryptoDeleteAllowanceHandler = mock(CryptoDeleteAllowanceHandler.class);
        cryptoAddLiveHashHandler = mock(CryptoAddLiveHashHandler.class);
        cryptoDeleteLiveHashHandler = mock(CryptoDeleteLiveHashHandler.class);
        tokenCreateHandler = mock(TokenCreateHandler.class);
        tokenUpdateHandler = mock(TokenUpdateHandler.class);
        tokenMintHandler = mock(TokenMintHandler.class);
        tokenBurnHandler = mock(TokenBurnHandler.class);
        tokenDeleteHandler = mock(TokenDeleteHandler.class);
        tokenAccountWipeHandler = mock(TokenAccountWipeHandler.class);
        tokenFreezeAccountHandler = mock(TokenFreezeAccountHandler.class);
        tokenUnfreezeAccountHandler = mock(TokenUnfreezeAccountHandler.class);
        tokenGrantKycToAccountHandler = mock(TokenGrantKycToAccountHandler.class);
        tokenRevokeKycFromAccountHandler = mock(TokenRevokeKycFromAccountHandler.class);
        tokenAssociateToAccountHandler = mock(TokenAssociateToAccountHandler.class);
        tokenDissociateFromAccountHandler = mock(TokenDissociateFromAccountHandler.class);
        tokenFeeScheduleUpdateHandler = mock(TokenFeeScheduleUpdateHandler.class);
        tokenPauseHandler = mock(TokenPauseHandler.class);
        tokenUnpauseHandler = mock(TokenUnpauseHandler.class);
        cryptoGetAccountBalanceHandler = mock(CryptoGetAccountBalanceHandler.class);
        cryptoGetAccountInfoHandler = mock(CryptoGetAccountInfoHandler.class);
        cryptoGetAccountRecordsHandler = mock(CryptoGetAccountRecordsHandler.class);
        cryptoGetLiveHashHandler = mock(CryptoGetLiveHashHandler.class);
        cryptoGetStakersHandler = mock(CryptoGetStakersHandler.class);
        tokenGetInfoHandler = mock(TokenGetInfoHandler.class);
        tokenGetAccountNftInfosHandler = mock(TokenGetAccountNftInfosHandler.class);
        tokenGetNftInfoHandler = mock(TokenGetNftInfoHandler.class);
        tokenGetNftInfosHandler = mock(TokenGetNftInfosHandler.class);
        tokenUpdateNftsHandler = mock(TokenUpdateNftsHandler.class);
        tokenRejectHandler = mock(TokenRejectHandler.class);
        tokenCancelAirdropHandler = mock(TokenCancelAirdropHandler.class);
        tokenClaimAirdropHandler = mock(TokenClaimAirdropHandler.class);
        tokenAirdropHandler = mock(TokenAirdropHandler.class);

        tokenHandlers = new TokenHandlers(
                cryptoCreateHandler,
                cryptoUpdateHandler,
                cryptoTransferHandler,
                cryptoDeleteHandler,
                cryptoApproveAllowanceHandler,
                cryptoDeleteAllowanceHandler,
                cryptoAddLiveHashHandler,
                cryptoDeleteLiveHashHandler,
                tokenCreateHandler,
                tokenUpdateHandler,
                tokenMintHandler,
                tokenBurnHandler,
                tokenDeleteHandler,
                tokenAccountWipeHandler,
                tokenFreezeAccountHandler,
                tokenUnfreezeAccountHandler,
                tokenGrantKycToAccountHandler,
                tokenRevokeKycFromAccountHandler,
                tokenAssociateToAccountHandler,
                tokenDissociateFromAccountHandler,
                tokenFeeScheduleUpdateHandler,
                tokenPauseHandler,
                tokenUnpauseHandler,
                cryptoGetAccountBalanceHandler,
                cryptoGetAccountInfoHandler,
                cryptoGetAccountRecordsHandler,
                cryptoGetLiveHashHandler,
                cryptoGetStakersHandler,
                tokenGetInfoHandler,
                tokenGetAccountNftInfosHandler,
                tokenGetNftInfoHandler,
                tokenGetNftInfosHandler,
                tokenRejectHandler,
                tokenUpdateNftsHandler,
                tokenCancelAirdropHandler,
                tokenClaimAirdropHandler,
                tokenAirdropHandler);
    }

    @Test
    public void cryptoCreateHandlerReturnsCorrectInstance() {
        assertEquals(cryptoCreateHandler, tokenHandlers.cryptoCreateHandler());
    }

    @Test
    public void cryptoUpdateHandlerReturnsCorrectInstance() {
        assertEquals(cryptoUpdateHandler, tokenHandlers.cryptoUpdateHandler());
    }

    @Test
    public void cryptoTransferHandlerReturnsCorrectInstance() {
        assertEquals(cryptoTransferHandler, tokenHandlers.cryptoTransferHandler());
    }

    @Test
    public void cryptoDeleteHandlerReturnsCorrectInstance() {
        assertEquals(cryptoDeleteHandler, tokenHandlers.cryptoDeleteHandler());
    }

    @Test
    public void cryptoApproveAllowanceHandlerReturnsCorrectInstance() {
        assertEquals(cryptoApproveAllowanceHandler, tokenHandlers.cryptoApproveAllowanceHandler());
    }

    @Test
    public void cryptoDeleteAllowanceHandlerReturnsCorrectInstance() {
        assertEquals(cryptoDeleteAllowanceHandler, tokenHandlers.cryptoDeleteAllowanceHandler());
    }

    @Test
    public void cryptoAddLiveHashHandlerReturnsCorrectInstance() {
        assertEquals(cryptoAddLiveHashHandler, tokenHandlers.cryptoAddLiveHashHandler());
    }

    @Test
    public void cryptoDeleteLiveHashHandlerReturnsCorrectInstance() {
        assertEquals(cryptoDeleteLiveHashHandler, tokenHandlers.cryptoDeleteLiveHashHandler());
    }

    @Test
    public void tokenCreateHandlerReturnsCorrectInstance() {
        assertEquals(tokenCreateHandler, tokenHandlers.tokenCreateHandler());
    }

    @Test
    public void tokenUpdateHandlerReturnsCorrectInstance() {
        assertEquals(tokenUpdateHandler, tokenHandlers.tokenUpdateHandler());
    }

    @Test
    public void tokenMintHandlerReturnsCorrectInstance() {
        assertEquals(tokenMintHandler, tokenHandlers.tokenMintHandler());
    }

    @Test
    public void tokenBurnHandlerReturnsCorrectInstance() {
        assertEquals(tokenBurnHandler, tokenHandlers.tokenBurnHandler());
    }

    @Test
    public void tokenDeleteHandlerReturnsCorrectInstance() {
        assertEquals(tokenDeleteHandler, tokenHandlers.tokenDeleteHandler());
    }

    @Test
    public void tokenAccountWipeHandlerReturnsCorrectInstance() {
        assertEquals(tokenAccountWipeHandler, tokenHandlers.tokenAccountWipeHandler());
    }

    @Test
    public void tokenFreezeAccountHandlerReturnsCorrectInstance() {
        assertEquals(tokenFreezeAccountHandler, tokenHandlers.tokenFreezeAccountHandler());
    }

    @Test
    public void tokenUnfreezeAccountHandlerReturnsCorrectInstance() {
        assertEquals(tokenUnfreezeAccountHandler, tokenHandlers.tokenUnfreezeAccountHandler());
    }

    @Test
    public void tokenGrantKycToAccountHandlerReturnsCorrectInstance() {
        assertEquals(tokenGrantKycToAccountHandler, tokenHandlers.tokenGrantKycToAccountHandler());
    }

    @Test
    public void tokenRevokeKycFromAccountHandlerReturnsCorrectInstance() {
        assertEquals(tokenRevokeKycFromAccountHandler, tokenHandlers.tokenRevokeKycFromAccountHandler());
    }

    @Test
    public void tokenAssociateToAccountHandlerReturnsCorrectInstance() {
        assertEquals(tokenAssociateToAccountHandler, tokenHandlers.tokenAssociateToAccountHandler());
    }

    @Test
    public void tokenDissociateFromAccountHandlerReturnsCorrectInstance() {
        assertEquals(tokenDissociateFromAccountHandler, tokenHandlers.tokenDissociateFromAccountHandler());
    }

    @Test
    public void tokenFeeScheduleUpdateHandlerReturnsCorrectInstance() {
        assertEquals(tokenFeeScheduleUpdateHandler, tokenHandlers.tokenFeeScheduleUpdateHandler());
    }

    @Test
    public void tokenPauseHandlerReturnsCorrectInstance() {
        assertEquals(tokenPauseHandler, tokenHandlers.tokenPauseHandler());
    }

    @Test
    public void tokenUnpauseHandlerReturnsCorrectInstance() {
        assertEquals(tokenUnpauseHandler, tokenHandlers.tokenUnpauseHandler());
    }

    @Test
    public void cryptoGetAccountBalanceHandlerReturnsCorrectInstance() {
        assertEquals(cryptoGetAccountBalanceHandler, tokenHandlers.cryptoGetAccountBalanceHandler());
    }

    @Test
    public void cryptoGetAccountInfoHandlerReturnsCorrectInstance() {
        assertEquals(cryptoGetAccountInfoHandler, tokenHandlers.cryptoGetAccountInfoHandler());
    }

    @Test
    public void cryptoGetAccountRecordsHandlerReturnsCorrectInstance() {
        assertEquals(cryptoGetAccountRecordsHandler, tokenHandlers.cryptoGetAccountRecordsHandler());
    }

    @Test
    public void cryptoGetLiveHashHandlerReturnsCorrectInstance() {
        assertEquals(cryptoGetLiveHashHandler, tokenHandlers.cryptoGetLiveHashHandler());
    }

    @Test
    public void cryptoGetStakersHandlerReturnsCorrectInstance() {
        assertEquals(cryptoGetStakersHandler, tokenHandlers.cryptoGetStakersHandler());
    }

    @Test
    public void tokenGetInfoHandlerReturnsCorrectInstance() {
        assertEquals(tokenGetInfoHandler, tokenHandlers.tokenGetInfoHandler());
    }

    @Test
    public void tokenGetAccountNftInfosHandlerReturnsCorrectInstance() {
        assertEquals(tokenGetAccountNftInfosHandler, tokenHandlers.tokenGetAccountNftInfosHandler());
    }

    @Test
    public void tokenGetNftInfoHandlerReturnsCorrectInstance() {
        assertEquals(tokenGetNftInfoHandler, tokenHandlers.tokenGetNftInfoHandler());
    }

    @Test
    public void tokenGetNftInfosHandlerReturnsCorrectInstance() {
        assertEquals(tokenGetNftInfosHandler, tokenHandlers.tokenGetNftInfosHandler());
    }

    @Test
    public void tokenUpdateNftsHandlerReturnsCorrectInstance() {
        assertEquals(tokenUpdateNftsHandler, tokenHandlers.tokenUpdateNftsHandler());
    }

    @Test
    public void setTokenRejectHandlerReturnsCorrectInstance() {
        assertEquals(tokenRejectHandler, tokenHandlers.tokenRejectHandler());
    }

    @Test
    public void tokenAirdropsHandlerReturnsCorrectInstance() {
        assertEquals(tokenAirdropHandler, tokenHandlers.tokenAirdropsHandler());
    }
}
