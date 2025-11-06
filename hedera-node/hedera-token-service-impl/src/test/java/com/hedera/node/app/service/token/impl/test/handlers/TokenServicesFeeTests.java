package com.hedera.node.app.service.token.impl.test.handlers;

import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.impl.handlers.TokenCreateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenMintHandler;
import com.hedera.node.app.service.token.impl.validators.CustomFeesValidator;
import com.hedera.node.app.service.token.impl.validators.TokenCreateValidator;
import com.hedera.node.app.service.token.impl.validators.TokenSupplyChangeOpsValidator;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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

import java.util.List;

import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("Token Handler Fee Tests")
public class TokenServicesFeeTests {
    private static final long TOKEN_CREATE_BASE_FEE = 15;
    private static final long TOKEN_MINT_BASE_FEE = 20;
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

    @BeforeEach
    void beforeEach() {
        createHandler = new TokenCreateHandler(entityIdFactory, customFeesValidator,tokenCreateValidator);
        mintHandler = new TokenMintHandler(tokenSupplyChangeOpsValidator);
    }

    @Test
    void createCommonToken() {
        final var txBody2 = TransactionBody.newBuilder().tokenCreation(
                TokenCreateTransactionBody.newBuilder()
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .build()).build();
        final var feeContext = createMockFeeContext(txBody2,1);
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
        final var feeContext = createMockFeeContext(txBody2,1);
        final var result = createHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_CREATE_BASE_FEE, result.total());
    }

    @Test
    void mintCommonToken() {
        final var commonToken = TokenID.newBuilder().tokenNum(1234).build();
        final var txBody2 = TransactionBody.newBuilder().tokenMint(
                TokenMintTransactionBody.newBuilder()
                        .token(commonToken)
                        .amount(10)
                        .build()).build();
        final var feeContext = createMockFeeContext(txBody2,1);
        final var result = mintHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_MINT_BASE_FEE + COMMON_TOKEN_FEE*10, result.total());
    }

    @Test
    void mintUniqueToken() {
        final var uniqueToken = TokenID.newBuilder().tokenNum(1234).build();
        final var mintBody = TransactionBody.newBuilder()
                .tokenMint(
                        TokenMintTransactionBody.newBuilder()
                                .token(uniqueToken)
                                .metadata(List.of(Bytes.wrap("Bart Simpson")))
                                .build()).build();
        final var feeContext = createMockFeeContext(mintBody,1);
        final var result = mintHandler.calculateFeeResult(feeContext);
        assertNotNull(result);
        assertEquals(TOKEN_MINT_BASE_FEE + UNIQUE_TOKEN_FEE, result.total());
    }

    /*
        // TODO
        create FT with custom fees
        create NFT with custom fees
        mint 10 NTF tokens??
        burn an NFT
        grant and revoke KYC
        freeze and unfreeze NFT
        freeze and unfreeze common
        pause and unpause
        update common token
        update NFT token
        update multiple NFT tokens
        delete token
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
        return FeeSchedule.DEFAULT.copyBuilder()
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .build())
                .extras(
                        makeExtraDef(Extra.BYTES, 1),
                        makeExtraDef(Extra.KEYS, 2),
                        makeExtraDef(Extra.SIGNATURES, 3),
                        makeExtraDef(Extra.STANDARD_FUNGIBLE_TOKENS,COMMON_TOKEN_FEE),
                        makeExtraDef(Extra.STANDARD_NON_FUNGIBLE_TOKENS,UNIQUE_TOKEN_FEE),
                        makeExtraDef(Extra.CUSTOM_FEE, 500)
                )
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(2).build())
                                    .services(makeService(
                                            "Token",
                                            makeServiceFee(TOKEN_CREATE, TOKEN_CREATE_BASE_FEE,
                                                    makeExtraIncluded(Extra.KEYS,   1)),
                                            makeServiceFee(TOKEN_MINT, TOKEN_MINT_BASE_FEE,
                                                    makeExtraIncluded(Extra.KEYS, 1),
                                                    makeExtraIncluded(Extra.STANDARD_FUNGIBLE_TOKENS,0),
                                                    makeExtraIncluded(Extra.STANDARD_NON_FUNGIBLE_TOKENS,0)
                                            )
                                    ))

                .build();
    }
}
