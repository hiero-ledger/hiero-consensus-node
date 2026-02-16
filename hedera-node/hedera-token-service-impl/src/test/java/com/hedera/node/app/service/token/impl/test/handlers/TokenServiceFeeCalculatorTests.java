// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ACCOUNT_WIPE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_BURN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GET_NFT_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GRANT_KYC_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_PAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_REJECT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_REVOKE_KYC_FROM_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNPAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UPDATE_NFTS;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.token.TokenAssociateTransactionBody;
import com.hedera.hapi.node.token.TokenBurnTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenDeleteTransactionBody;
import com.hedera.hapi.node.token.TokenDissociateTransactionBody;
import com.hedera.hapi.node.token.TokenFeeScheduleUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenFreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenGetInfoQuery;
import com.hedera.hapi.node.token.TokenGetNftInfoQuery;
import com.hedera.hapi.node.token.TokenGrantKycTransactionBody;
import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.token.TokenPauseTransactionBody;
import com.hedera.hapi.node.token.TokenRejectTransactionBody;
import com.hedera.hapi.node.token.TokenRevokeKycTransactionBody;
import com.hedera.hapi.node.token.TokenUnfreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenUnpauseTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateNftsTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenWipeAccountTransactionBody;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.SimpleFeeContextImpl;
import com.hedera.node.app.service.token.impl.calculator.CryptoCreateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.CryptoDeleteFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenAssociateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenBurnFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenCreateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenDeleteFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenDissociateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenFeeScheduleUpdateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenFreezeAccountFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenGetInfoFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenGetNftInfoFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenGrantKycFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenMintFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenPauseFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenRejectFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenRevokeKycFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenUnfreezeAccountFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenUnpauseFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenUpdateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenUpdateNftsFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenWipeFeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.EntitiesConfig;
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
    private static final long TOKEN_ASSOCIATE_BASE_FEE = 45;
    private static final long TOKEN_BURN_BASE_FEE = 40;
    private static final long TOKEN_CREATE_BASE_FEE = 15;
    private static final long TOKEN_DELETE_BASE_FEE = 45;
    private static final long TOKEN_DISSOCIATE_BASE_FEE = 45;
    private static final long TOKEN_FEE_SCHEDULE_UPDATE_BASE_FEE = 45;
    private static final long TOKEN_FREEZE_BASE_FEE = 25;
    private static final long TOKEN_GRANT_KYC_BASE_FEE = 45;
    private static final long TOKEN_MINT_BASE_FEE = 20;
    private static final long TOKEN_PAUSE_BASE_FEE = 35;
    private static final long TOKEN_REJECT_BASE_FEE = 45;
    private static final long TOKEN_REVOKE_KYC_BASE_FEE = 45;
    private static final long TOKEN_UNPAUSE_BASE_FEE = 40;
    private static final long TOKEN_UNFREEZE_BASE_FEE = 30;
    private static final long TOKEN_UPDATE_BASE_FEE = 45;
    private static final long TOKEN_UPDATE_NFTS_BASE_FEE = 45;
    private static final long TOKEN_WIPE_BASE_FEE = 45;
    private static final short TOKEN_GET_INFO_BASE_FEE = 101;
    private static final short TOKEN_GET_NFT_INFO_BASE_FEE = 102;

    private static final long COMMON_TOKEN_FEE = 5;
    private static final long UNIQUE_TOKEN_FEE = 10;

    @Mock
    private FeeContext feeContext;

    @Mock
    private QueryContext queryContext;

    @Mock
    private EntitiesConfig entitiesConfig;

    @Mock
    private VersionedConfiguration configuration;

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
                        new TokenAssociateFeeCalculator(),
                        new TokenBurnFeeCalculator(),
                        new TokenDeleteFeeCalculator(),
                        new TokenDissociateFeeCalculator(),
                        new TokenCreateFeeCalculator(),
                        new TokenFeeScheduleUpdateFeeCalculator(),
                        new TokenFreezeAccountFeeCalculator(),
                        new TokenGrantKycFeeCalculator(),
                        new TokenMintFeeCalculator(),
                        new TokenPauseFeeCalculator(),
                        new TokenRejectFeeCalculator(),
                        new TokenRevokeKycFeeCalculator(),
                        new TokenUnfreezeAccountFeeCalculator(),
                        new TokenUnpauseFeeCalculator(),
                        new TokenUpdateFeeCalculator(),
                        new TokenUpdateNftsFeeCalculator(),
                        new TokenWipeFeeCalculator()),
                Set.of(new TokenGetInfoFeeCalculator(), new TokenGetNftInfoFeeCalculator()));
    }

    @Test
    void createCommonToken() {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
        final var body = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder()
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .build())
                .build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_CREATE);
        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_CREATE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void createUniqueToken() {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
        final var txBody2 = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder().tokenType(TokenType.NON_FUNGIBLE_UNIQUE))
                .build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_CREATE);
        final var result = feeCalculator.calculateTxFee(txBody2, new SimpleFeeContextImpl(feeContext, null));

        assertNotNull(result);
        assertEquals(TOKEN_CREATE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void updateCommonToken() {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
        final var body = TransactionBody.newBuilder()
                .tokenUpdate(TokenUpdateTransactionBody.newBuilder().build())
                .build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_UPDATE);
        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_UPDATE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void mintCommonToken() {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
        final var commonToken = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenMint(TokenMintTransactionBody.newBuilder()
                        .token(commonToken)
                        .amount(10)
                        .build())
                .build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_MINT);
        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_MINT_BASE_FEE, result.totalTinycents());
    }

    @Test
    void mintUniqueToken() {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
        final var uniqueToken = TokenID.newBuilder().tokenNum(1234).build();
        final var txBody2 = TransactionBody.newBuilder()
                .tokenMint(TokenMintTransactionBody.newBuilder()
                        .token(uniqueToken)
                        .metadata(List.of(Bytes.wrap("Bart Simpson")))
                        .build())
                .build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_MINT);
        final var result = feeCalculator.calculateTxFee(txBody2, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_MINT_BASE_FEE + UNIQUE_TOKEN_FEE, result.totalTinycents());
    }

    @Test
    void freezeToken() {
        final var commonToken = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenFreeze(TokenFreezeAccountTransactionBody.newBuilder()
                        .token(commonToken)
                        .build())
                .build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_FREEZE_ACCOUNT);
        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_FREEZE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void unfreezeToken() {
        final var commonToken = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenUnfreeze(TokenUnfreezeAccountTransactionBody.newBuilder()
                        .token(commonToken)
                        .build())
                .build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT);
        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_UNFREEZE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void pauseCommonToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenPause(
                        TokenPauseTransactionBody.newBuilder().token(tokenId).build())
                .build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_PAUSE);
        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_PAUSE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void unpauseCommonToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenUnpause(
                        TokenUnpauseTransactionBody.newBuilder().token(tokenId).build())
                .build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_UNPAUSE);
        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_UNPAUSE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void burnCommonToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenBurn(TokenBurnTransactionBody.newBuilder().token(tokenId).build())
                .build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_BURN);
        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_BURN_BASE_FEE, result.totalTinycents());
    }

    @Test
    void burnUniqueToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var body = TransactionBody.newBuilder()
                .tokenBurn(TokenBurnTransactionBody.newBuilder().token(tokenId).build())
                .build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_BURN);
        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_BURN_BASE_FEE, result.totalTinycents());
    }

    @Test
    void deleteToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var deleteBody =
                TokenDeleteTransactionBody.newBuilder().token(tokenId).build();
        final var body = TransactionBody.newBuilder().tokenDeletion(deleteBody).build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_DELETE);
        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_DELETE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void associateToken() {
        feeContext = mock(FeeContext.class);

        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var accountId = AccountID.newBuilder().accountNum(12345).build();
        final var opBody = TokenAssociateTransactionBody.newBuilder()
                .tokens(tokenId)
                .account(accountId)
                .build();
        final var txnBody = TransactionBody.newBuilder().tokenAssociate(opBody).build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT);
        final var result = feeCalculator.calculateTxFee(txnBody, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_ASSOCIATE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void disassociateToken() {
        final var tokenId = TokenID.newBuilder().tokenNum(1234).build();
        final var accountId = AccountID.newBuilder().accountNum(12345).build();
        final var opBody = TokenDissociateTransactionBody.newBuilder()
                .tokens(tokenId)
                .account(accountId)
                .build();
        final var txnBody = TransactionBody.newBuilder().tokenDissociate(opBody).build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT);
        final var result = feeCalculator.calculateTxFee(txnBody, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_DISSOCIATE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void grantKyc() {
        final var opBody = TokenGrantKycTransactionBody.newBuilder().build();
        final var txnBody = TransactionBody.newBuilder().tokenGrantKyc(opBody).build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_GRANT_KYC_TO_ACCOUNT);
        final var result = feeCalculator.calculateTxFee(txnBody, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_GRANT_KYC_BASE_FEE, result.totalTinycents());
    }

    @Test
    void revokeKyc() {
        final var opBody = TokenRevokeKycTransactionBody.newBuilder().build();
        final var txnBody = TransactionBody.newBuilder().tokenRevokeKyc(opBody).build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_REVOKE_KYC_FROM_ACCOUNT);
        final var result = feeCalculator.calculateTxFee(txnBody, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_REVOKE_KYC_BASE_FEE, result.totalTinycents());
    }

    @Test
    void reject() {
        final var opBody = TokenRejectTransactionBody.newBuilder().build();
        final var txnBody = TransactionBody.newBuilder().tokenReject(opBody).build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_REJECT);
        final var result = feeCalculator.calculateTxFee(txnBody, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_REJECT_BASE_FEE, result.totalTinycents());
    }

    @Test
    void tokenWipeAccount() {
        final var opBody = TokenWipeAccountTransactionBody.newBuilder().build();
        final var txnBody = TransactionBody.newBuilder().tokenWipe(opBody).build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_ACCOUNT_WIPE);
        final var result = feeCalculator.calculateTxFee(txnBody, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_WIPE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void tokenFeeScheduleUpdate() {
        final var opBody = TokenFeeScheduleUpdateTransactionBody.newBuilder().build();
        final var txnBody =
                TransactionBody.newBuilder().tokenFeeScheduleUpdate(opBody).build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE);
        final var result = feeCalculator.calculateTxFee(txnBody, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_FEE_SCHEDULE_UPDATE_BASE_FEE, result.totalTinycents());
    }

    @Test
    void tokenUpdateNft() {
        final var opBody = TokenUpdateNftsTransactionBody.newBuilder().build();
        final var txnBody = TransactionBody.newBuilder().tokenUpdateNfts(opBody).build();
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_UPDATE_NFTS);
        final var result = feeCalculator.calculateTxFee(txnBody, new SimpleFeeContextImpl(feeContext, null));
        assertNotNull(result);
        assertEquals(TOKEN_UPDATE_NFTS_BASE_FEE, result.totalTinycents());
    }

    @Test
    void tokenGetInfo() {
        final var opBody = TokenGetInfoQuery.newBuilder().build();
        final var queryBody = Query.newBuilder().tokenGetInfo(opBody).build();
        final var result = feeCalculator.calculateQueryFee(queryBody, new SimpleFeeContextImpl(null, queryContext));
        ;
        assertEquals(TOKEN_GET_INFO_BASE_FEE, result.getServiceTotalTinycents());
    }

    @Test
    void tokenGetNftInfo() {
        final var opBody = TokenGetNftInfoQuery.newBuilder().build();
        final var queryBody = Query.newBuilder().tokenGetNftInfo(opBody).build();
        final var result = feeCalculator.calculateQueryFee(queryBody, new SimpleFeeContextImpl(null, queryContext));
        ;
        assertEquals(TOKEN_GET_NFT_INFO_BASE_FEE, result.getServiceTotalTinycents());
    }

    private FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.DEFAULT.copyBuilder().build())
                .extras(
                        makeExtraDef(Extra.STATE_BYTES, 1),
                        makeExtraDef(Extra.KEYS, 2),
                        makeExtraDef(Extra.SIGNATURES, 3),
                        makeExtraDef(Extra.TOKEN_MINT_NFT, UNIQUE_TOKEN_FEE))
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(2).build())
                .services(makeService(
                        "Token",
                        makeServiceFee(TOKEN_ACCOUNT_WIPE, TOKEN_WIPE_BASE_FEE),
                        makeServiceFee(TOKEN_ASSOCIATE_TO_ACCOUNT, TOKEN_ASSOCIATE_BASE_FEE),
                        makeServiceFee(TOKEN_BURN, TOKEN_BURN_BASE_FEE),
                        makeServiceFee(TOKEN_CREATE, TOKEN_CREATE_BASE_FEE, makeExtraIncluded(Extra.KEYS, 1)),
                        makeServiceFee(TOKEN_DELETE, TOKEN_DELETE_BASE_FEE),
                        makeServiceFee(TOKEN_DISSOCIATE_FROM_ACCOUNT, TOKEN_DISSOCIATE_BASE_FEE),
                        makeServiceFee(TOKEN_FEE_SCHEDULE_UPDATE, TOKEN_FEE_SCHEDULE_UPDATE_BASE_FEE),
                        makeServiceFee(TOKEN_FREEZE_ACCOUNT, TOKEN_FREEZE_BASE_FEE),
                        makeServiceFee(TOKEN_GET_INFO, TOKEN_GET_INFO_BASE_FEE),
                        makeServiceFee(TOKEN_GET_NFT_INFO, TOKEN_GET_NFT_INFO_BASE_FEE),
                        makeServiceFee(TOKEN_GRANT_KYC_TO_ACCOUNT, TOKEN_GRANT_KYC_BASE_FEE),
                        makeServiceFee(
                                TOKEN_MINT,
                                TOKEN_MINT_BASE_FEE,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.TOKEN_MINT_NFT, 0)),
                        makeServiceFee(TOKEN_PAUSE, TOKEN_PAUSE_BASE_FEE),
                        makeServiceFee(TOKEN_REJECT, TOKEN_REJECT_BASE_FEE),
                        makeServiceFee(TOKEN_REVOKE_KYC_FROM_ACCOUNT, TOKEN_REVOKE_KYC_BASE_FEE),
                        makeServiceFee(TOKEN_UNFREEZE_ACCOUNT, TOKEN_UNFREEZE_BASE_FEE),
                        makeServiceFee(TOKEN_UNPAUSE, TOKEN_UNPAUSE_BASE_FEE),
                        makeServiceFee(TOKEN_UPDATE, TOKEN_UPDATE_BASE_FEE, makeExtraIncluded(Extra.KEYS, 1)),
                        makeServiceFee(TOKEN_UPDATE_NFTS, TOKEN_UPDATE_NFTS_BASE_FEE)))
                .build();
    }
}
