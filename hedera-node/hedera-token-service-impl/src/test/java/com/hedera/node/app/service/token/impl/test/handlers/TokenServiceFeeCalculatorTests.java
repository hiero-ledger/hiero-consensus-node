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
import com.hedera.node.app.service.token.impl.calculator.CryptoCreateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.CryptoDeleteFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenBurnFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenCreateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenDeleteFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenFreezeAccountFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenMintFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenPauseFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenUnfreezeAccountFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenUnpauseFeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Set;
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

/*
 * Unit tests for {@link TokenCreateFeeCalculator} and {@link TokenMintFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Token Handler Fee Tests")
public class TokenServiceFeeCalculatorTests {
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
    private FeeContext calculatorState;

    private SimpleFeeCalculatorImpl feeCalculator;
    private FeeSchedule testSchedule;

    @BeforeEach
    void beforeEach() {
        testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(
                testSchedule,
                Set.of(
                        new CryptoCreateFeeCalculator(),
                        new CryptoDeleteFeeCalculator(),
                        new TokenCreateFeeCalculator(),
                        new TokenMintFeeCalculator(),
                        new TokenPauseFeeCalculator(),
                        new TokenUnpauseFeeCalculator(),
                        new TokenFreezeAccountFeeCalculator(),
                        new TokenUnfreezeAccountFeeCalculator(),
                        new TokenBurnFeeCalculator(),
                        new TokenDeleteFeeCalculator()));
    }

    @Test
    void createCommonToken() {
        lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);
        final var body = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder()
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .build())
                .build();
        final var result = feeCalculator.calculateTxFee(body, calculatorState);
        assertNotNull(result);
        assertEquals(TOKEN_CREATE_BASE_FEE, result.total());
    }

    @Test
    void createUniqueToken() {
        lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);
        final var txBody2 = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder().tokenType(TokenType.NON_FUNGIBLE_UNIQUE))
                .build();
        final var result = feeCalculator.calculateTxFee(txBody2, calculatorState);

        assertNotNull(result);
        assertEquals(TOKEN_CREATE_BASE_FEE, result.total());
    }

    @Test
    void mintCommonToken() {
        lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);
        final var commonToken = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenMint(TokenMintTransactionBody.newBuilder()
                        .token(commonToken)
                        .amount(10)
                        .build())
                .build();
        final var result = feeCalculator.calculateTxFee(body, calculatorState);
        assertNotNull(result);
        assertEquals(TOKEN_MINT_BASE_FEE, result.total());
    }

    @Test
    void mintUniqueToken() {
        lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);
        final var uniqueToken = TokenID.newBuilder().tokenNum(1234).build();
        final var txBody2 = TransactionBody.newBuilder()
                .tokenMint(TokenMintTransactionBody.newBuilder()
                        .token(uniqueToken)
                        .metadata(List.of(Bytes.wrap("Bart Simpson")))
                        .build())
                .build();
        final var result = feeCalculator.calculateTxFee(txBody2, calculatorState);
        assertNotNull(result);
        assertEquals(TOKEN_MINT_BASE_FEE + UNIQUE_TOKEN_FEE, result.total());
    }

    @Test
    void freezeToken() {
        final var commonToken = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenFreeze(TokenFreezeAccountTransactionBody.newBuilder()
                        .token(commonToken)
                        .build())
                .build();
        final var result = feeCalculator.calculateTxFee(body, calculatorState);
        assertNotNull(result);
        assertEquals(TOKEN_FREEZE_BASE_FEE, result.total());
    }

    @Test
    void unfreezeToken() {
        final var commonToken = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenUnfreeze(TokenUnfreezeAccountTransactionBody.newBuilder()
                        .token(commonToken)
                        .build())
                .build();
        final var result = feeCalculator.calculateTxFee(body, calculatorState);
        assertNotNull(result);
        assertEquals(TOKEN_UNFREEZE_BASE_FEE, result.total());
    }

    @Test
    void pauseCommonToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenPause(
                        TokenPauseTransactionBody.newBuilder().token(tokenId).build())
                .build();
        final var result = feeCalculator.calculateTxFee(body, calculatorState);
        assertNotNull(result);
        assertEquals(TOKEN_PAUSE_BASE_FEE, result.total());
    }

    @Test
    void unpauseCommonToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenUnpause(
                        TokenUnpauseTransactionBody.newBuilder().token(tokenId).build())
                .build();
        final var result = feeCalculator.calculateTxFee(body, calculatorState);
        assertNotNull(result);
        assertEquals(TOKEN_UNPAUSE_BASE_FEE, result.total());
    }

    @Test
    void burnCommonToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenBurn(TokenBurnTransactionBody.newBuilder().token(tokenId).build())
                .build();
        final var result = feeCalculator.calculateTxFee(body, calculatorState);
        assertNotNull(result);
        assertEquals(TOKEN_BURN_BASE_FEE, result.total());
    }

    @Test
    void burnUniqueToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenBurn(TokenBurnTransactionBody.newBuilder().token(tokenId).build())
                .build();
        final var result = feeCalculator.calculateTxFee(body, calculatorState);
        assertNotNull(result);
        assertEquals(TOKEN_BURN_BASE_FEE, result.total());
    }

    @Test
    void deleteToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var deleteBody =
                TokenDeleteTransactionBody.newBuilder().token(tokenId).build();
        final var body = TransactionBody.newBuilder().tokenDeletion(deleteBody).build();
        final var result = feeCalculator.calculateTxFee(body, calculatorState);
        assertNotNull(result);
        assertEquals(TOKEN_DELETE_BASE_FEE, result.total());
    }

    private FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.DEFAULT.copyBuilder().build())
                .extras(
                        makeExtraDef(Extra.BYTES, 1),
                        makeExtraDef(Extra.KEYS, 2),
                        makeExtraDef(Extra.SIGNATURES, 3),
                        makeExtraDef(Extra.TOKEN_MINT_NFT, UNIQUE_TOKEN_FEE),
                        makeExtraDef(Extra.CUSTOM_FEE, 500))
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(2).build())
                .services(makeService(
                        "Token",
                        makeServiceFee(TOKEN_CREATE, TOKEN_CREATE_BASE_FEE, makeExtraIncluded(Extra.KEYS, 1)),
                        makeServiceFee(
                                TOKEN_MINT,
                                TOKEN_MINT_BASE_FEE,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.TOKEN_MINT_NFT, 0)),
                        makeServiceFee(TOKEN_BURN, TOKEN_BURN_BASE_FEE),
                        makeServiceFee(TOKEN_DELETE, TOKEN_DELETE_BASE_FEE),
                        makeServiceFee(TOKEN_PAUSE, TOKEN_PAUSE_BASE_FEE),
                        makeServiceFee(TOKEN_UNPAUSE, TOKEN_UNPAUSE_BASE_FEE),
                        makeServiceFee(TOKEN_FREEZE_ACCOUNT, TOKEN_FREEZE_BASE_FEE),
                        makeServiceFee(TOKEN_UNFREEZE_ACCOUNT, TOKEN_UNFREEZE_BASE_FEE)))
                .build();
    }
}
