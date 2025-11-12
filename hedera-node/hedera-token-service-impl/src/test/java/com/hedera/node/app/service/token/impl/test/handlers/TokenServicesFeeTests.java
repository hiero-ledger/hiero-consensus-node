// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_BURN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_PAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNPAUSE;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.token.TokenBurnTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenDeleteTransactionBody;
import com.hedera.hapi.node.token.TokenFreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.token.TokenPauseTransactionBody;
import com.hedera.hapi.node.token.TokenUnfreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenUnpauseTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.impl.handlers.TokenBurnHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenCreateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenDeleteHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenFreezeAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenMintHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenPauseHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUnfreezeAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUnpauseHandler;
import com.hedera.node.app.service.token.impl.validators.CustomFeesValidator;
import com.hedera.node.app.service.token.impl.validators.TokenCreateValidator;
import com.hedera.node.app.service.token.impl.validators.TokenSupplyChangeOpsValidator;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Token Handler Fee Tests")
public class TokenServicesFeeTests {
    private static final long TOKEN_CREATE_BASE_FEE = 15;
    private static final long TOKEN_MINT_BASE_FEE = 20;
    private static final long TOKEN_FREEZE_BASE_FEE = 25;
    private static final long TOKEN_UNFREEZE_BASE_FEE = 30;
    private static final long TOKEN_PAUSE_BASE_FEE = 35;
    private static final long TOKEN_UNPAUSE_BASE_FEE = 40;
    private static final long TOKEN_BURN_BASE_FEE = 40;
    private static final long TOKEN_DELETE_BASE_FEE = 45;

    private static final long COMMON_TOKEN_FEE = 5;
    private static final long UNIQUE_TOKEN_FEE = 10;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private CustomFeesValidator customFeesValidator;

    @Mock
    private TokenCreateValidator tokenCreateValidator;

    @Mock
    private TokenSupplyChangeOpsValidator tokenSupplyChangeOpsValidator;

    private TokenCreateHandler createHandler;
    private TokenMintHandler mintHandler;
    private TokenFreezeAccountHandler freezeAccountHandler;
    private TokenUnfreezeAccountHandler unfreezeAccountHandler;
    private TokenPauseHandler pauseHandler;
    private TokenUnpauseHandler unpauseHandler;
    private TokenBurnHandler burnHandler;
    private TokenDeleteHandler deleteHandler;

    @BeforeEach
    void beforeEach() {
        createHandler = new TokenCreateHandler(entityIdFactory, customFeesValidator, tokenCreateValidator);
        mintHandler = new TokenMintHandler(tokenSupplyChangeOpsValidator);
        freezeAccountHandler = new TokenFreezeAccountHandler();
        unfreezeAccountHandler = new TokenUnfreezeAccountHandler();
        pauseHandler = new TokenPauseHandler();
        unpauseHandler = new TokenUnpauseHandler();
        burnHandler = new TokenBurnHandler(tokenSupplyChangeOpsValidator);
        deleteHandler = new TokenDeleteHandler();
    }

    @Test
    void createCommonToken() {
        final var txBody2 = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder()
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .build())
                .build();
        final var feeContext = createMockFeeContext(txBody2, 1);
        final var result = createHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_CREATE_BASE_FEE, result.total());
    }

    @Test
    void createUniqueToken() {
        final var txBody = TokenCreateTransactionBody.newBuilder()
                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                .build();
        final var txBody2 = TransactionBody.newBuilder().tokenCreation(txBody).build();
        final var feeContext = createMockFeeContext(txBody2, 1);
        final var result = createHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_CREATE_BASE_FEE, result.total());
    }

    @Test
    void mintCommonToken() {
        final var commonToken = TokenID.newBuilder().tokenNum(1234).build();
        final var txBody2 = TransactionBody.newBuilder()
                .tokenMint(TokenMintTransactionBody.newBuilder()
                        .token(commonToken)
                        .amount(10)
                        .build())
                .build();
        final var feeContext = createMockFeeContext(txBody2, 1);
        final var result = mintHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_MINT_BASE_FEE + COMMON_TOKEN_FEE * 10, result.total());
    }

    @Test
    void mintUniqueToken() {
        final var uniqueToken = TokenID.newBuilder().tokenNum(1234).build();
        final var mintBody = TransactionBody.newBuilder()
                .tokenMint(TokenMintTransactionBody.newBuilder()
                        .token(uniqueToken)
                        .metadata(List.of(Bytes.wrap("Bart Simpson")))
                        .build())
                .build();
        final var feeContext = createMockFeeContext(mintBody, 1);
        final var result = mintHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_MINT_BASE_FEE + UNIQUE_TOKEN_FEE, result.total());
    }

    @Test
    void freezeToken() {
        final var commonToken = TokenID.newBuilder().tokenNum(1234).build();
        final var txBody2 = TransactionBody.newBuilder()
                .tokenFreeze(TokenFreezeAccountTransactionBody.newBuilder()
                        .token(commonToken)
                        .build()
                ).build();
        final var feeContext = createMockFeeContext(txBody2, 1);
        final var result = freezeAccountHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_FREEZE_BASE_FEE, result.total());
    }

    @Test
    void unfreezeToken() {
        final var commonToken = TokenID.newBuilder().tokenNum(1234).build();
        final var txBody2 = TransactionBody.newBuilder()
                .tokenUnfreeze(TokenUnfreezeAccountTransactionBody.newBuilder()
                        .token(commonToken)
                        .build()
                ).build();
        final var feeContext = createMockFeeContext(txBody2, 1);
        final var result = unfreezeAccountHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_UNFREEZE_BASE_FEE, result.total());
    }

    @Test
    void pauseCommonToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var txBody2 = TransactionBody.newBuilder()
                .tokenPause(TokenPauseTransactionBody.newBuilder()
                        .token(tokenId)
                        .build()
                ).build();
        final var feeContext = createMockFeeContext(txBody2, 1);
        final var result = pauseHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_PAUSE_BASE_FEE, result.total());
    }

    @Test
    void unpauseCommonToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var txBody2 = TransactionBody.newBuilder()
                .tokenUnpause(TokenUnpauseTransactionBody.newBuilder()
                        .token(tokenId)
                        .build()
                ).build();
        final var feeContext = createMockFeeContext(txBody2, 1);
        final var result = unpauseHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_UNPAUSE_BASE_FEE, result.total());
    }

    @Test
    void burnCommonToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenBurn(TokenBurnTransactionBody.newBuilder()
                        .token(tokenId)
                        .build()
                ).build();
        final var feeContext = createMockFeeContext(body, 1);
        final var result = burnHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_BURN_BASE_FEE, result.total());
    }

    @Test
    void burnUniqueToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenBurn(TokenBurnTransactionBody.newBuilder()
                        .token(tokenId)
                        .build()
                ).build();
        final var feeContext = createMockFeeContext(body, 1);
        final var result = burnHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_BURN_BASE_FEE, result.total());
    }


    @Test
    void deleteToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var deleteBody = TokenDeleteTransactionBody.newBuilder()
                .token(tokenId)
                .build();
        final var txnBody = TransactionBody.newBuilder()
                .tokenDeletion(deleteBody)
                .build();
        final var feeContext = createMockFeeContext(txnBody, 1);
        final var result = deleteHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_DELETE_BASE_FEE, result.total());
    }


    /*
       // TODO
       create FT with custom fees
       create NFT with custom fees
       mint 10 NTF tokens??
       grant and revoke KYC
       update common token
       update NFT token
       update multiple NFT tokens
       token associate dissociate
       get info for common token
       get info for NFT token
    */

    private FeeContext createMockFeeContext(TransactionBody txBody, int numSignatures) {
        final var feeContext = mock(FeeContext.class);
        final var feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        final var feeCalculator = mock(FeeCalculator.class);
        lenient().when(feeContext.numTxnSignatures()).thenReturn(numSignatures);
        lenient().when(feeContext.body()).thenReturn(txBody);
        lenient().when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
        lenient().when(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).thenReturn(feeCalculator);
        lenient().when(feeCalculator.getSimpleFeesSchedule()).thenReturn(createTestFeeSchedule());
        return feeContext;
    }

    private FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.DEFAULT.copyBuilder().build())
                .extras(
                        makeExtraDef(Extra.BYTES, 1),
                        makeExtraDef(Extra.KEYS, 2),
                        makeExtraDef(Extra.SIGNATURES, 3),
                        makeExtraDef(Extra.STANDARD_FUNGIBLE_TOKENS, COMMON_TOKEN_FEE),
                        makeExtraDef(Extra.STANDARD_NON_FUNGIBLE_TOKENS, UNIQUE_TOKEN_FEE),
                        makeExtraDef(Extra.CUSTOM_FEE, 500))
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(2).build())
                .services(makeService(
                        "Token",
                        makeServiceFee(TOKEN_CREATE, TOKEN_CREATE_BASE_FEE, makeExtraIncluded(Extra.KEYS, 1)),
                        makeServiceFee(
                                TOKEN_MINT,
                                TOKEN_MINT_BASE_FEE,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.STANDARD_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0)),
                        makeServiceFee(TOKEN_BURN, TOKEN_BURN_BASE_FEE),
                        makeServiceFee(TOKEN_DELETE, TOKEN_DELETE_BASE_FEE),
                        makeServiceFee(TOKEN_PAUSE,TOKEN_PAUSE_BASE_FEE),
                        makeServiceFee(TOKEN_UNPAUSE, TOKEN_UNPAUSE_BASE_FEE),
                        makeServiceFee(TOKEN_FREEZE_ACCOUNT, TOKEN_FREEZE_BASE_FEE),
                        makeServiceFee(TOKEN_UNFREEZE_ACCOUNT, TOKEN_UNFREEZE_BASE_FEE)
                        ))
                .build();
    }
}
