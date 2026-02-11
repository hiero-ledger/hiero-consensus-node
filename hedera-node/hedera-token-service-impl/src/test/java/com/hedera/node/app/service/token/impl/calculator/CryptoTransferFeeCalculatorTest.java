// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.SimpleFeeContextImpl;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.List;
import java.util.Set;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CryptoTransferFeeCalculator}.
 *
 * <p>Fee tiers tested:
 * <ul>
 *   <li>HBAR-only: baseFee (1M tinycents)
 *   <li>Token transfer: TOKEN_TRANSFER_BASE (9M tinycents)
 *   <li>Token with custom fees: TOKEN_TRANSFER_BASE_CUSTOM_FEES (19M tinycents)
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CryptoTransferFeeCalculatorTest {
    private static final long TOKEN_TRANSFER_FEE = 9_000_000L;
    private static final long TOKEN_TRANSFER_CUSTOM_FEE = 19_000_000L;
    private static final long TOKEN_TYPES_EXTRA_FEE = 1_000_000L;
    private static final long HOOK_EXECUTION_FEE = 50_000_000L;

    @Mock
    private FeeContext feeContext;

    @Mock
    private ReadableTokenStore tokenStore;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        feeCalculator = new SimpleFeeCalculatorImpl(createTestFeeSchedule(), Set.of(new CryptoTransferFeeCalculator()));
        lenient().when(feeContext.functionality()).thenReturn(HederaFunctionality.CRYPTO_TRANSFER);
    }

    @Nested
    @DisplayName("Fee Tier Tests")
    class FeeTierTests {

        @Test
        @DisplayName("HBAR-only transfer has no service fee (node+network cover it)")
        void hbarOnlyTransfer() {
            setupMocks();
            final var body = buildHbarTransfer(1001L, 1002L, 100L);

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // HBAR-only: no service fee, total fee comes from node+network
            assertThat(result.getServiceTotalTinycents()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Fungible token transfer charges TOKEN_TRANSFER_BASE")
        void fungibleTokenTransfer() {
            setupMocksWithTokenStore();
            mockFungibleToken(2001L, false);
            final var body = buildFungibleTokenTransfer(2001L, 1001L, 1002L, 50L);

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            assertThat(result.getServiceTotalTinycents()).isEqualTo(TOKEN_TRANSFER_FEE);
        }

        @Test
        @DisplayName("NFT transfer charges TOKEN_TRANSFER_BASE (same as FT)")
        void nftTransfer() {
            setupMocksWithTokenStore();
            mockNftToken(3001L, false);
            final var body = buildNftTransfer(3001L, 1001L, 1002L, 1L);

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            assertThat(result.getServiceTotalTinycents()).isEqualTo(TOKEN_TRANSFER_FEE);
        }

        @Test
        @DisplayName("Token with custom fees charges TOKEN_TRANSFER_BASE_CUSTOM_FEES")
        void tokenWithCustomFees() {
            setupMocksWithTokenStore();
            mockFungibleToken(2001L, true);
            final var body = buildFungibleTokenTransfer(2001L, 1001L, 1002L, 50L);

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            assertThat(result.getServiceTotalTinycents()).isEqualTo(TOKEN_TRANSFER_CUSTOM_FEE);
        }

        @Test
        @DisplayName("Empty transfer has no service fee (treated as HBAR-only)")
        void emptyTransfer() {
            setupMocks();
            final var body = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder().build())
                    .build();

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Empty = HBAR-only = no service fee
            assertThat(result.getServiceTotalTinycents()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("Mixed Transfer Tests - Key Fix Verification")
    class MixedTransferTests {

        @Test
        @DisplayName("Mixed FT + NFT charges extra TOKEN_TYPES")
        void mixedFtAndNftChargesSingleBase() {
            // This is the KEY test verifying the consolidation fix
            setupMocksWithTokenStore();
            mockFungibleToken(2001L, false);
            mockNftToken(3001L, false);

            final var ftTransfer = buildTokenTransferList(2001L, 1001L, 1002L, 50L);
            final var nftTransfer = buildNftTransferList(3001L, 1003L, 1004L, 1L);

            final var body = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                            .tokenTransfers(ftTransfer, nftTransfer)
                            .build())
                    .build();

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Single charge, NOT 18M (double)
            assertThat(result.getServiceTotalTinycents()).isEqualTo(TOKEN_TRANSFER_FEE + TOKEN_TYPES_EXTRA_FEE);
        }

        @Test
        @DisplayName("Mixed FT + NFT with custom fees charges extra TOKEN_TYPES")
        void mixedWithCustomFeesChargesSingleBase() {
            setupMocksWithTokenStore();
            mockFungibleToken(2001L, true);
            mockNftToken(3001L, true);

            final var ftTransfer = buildTokenTransferList(2001L, 1001L, 1002L, 50L);
            final var nftTransfer = buildNftTransferList(3001L, 1003L, 1004L, 1L);

            final var body = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                            .tokenTransfers(ftTransfer, nftTransfer)
                            .build())
                    .build();

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Single charge for custom fees tier
            assertThat(result.getServiceTotalTinycents()).isEqualTo(TOKEN_TRANSFER_CUSTOM_FEE + TOKEN_TYPES_EXTRA_FEE);
        }

        @Test
        @DisplayName("Mix of standard and custom fee tokens uses custom fee tier")
        void mixOfStandardAndCustomFeeTokens() {
            setupMocksWithTokenStore();
            mockFungibleToken(2001L, false); // standard
            mockFungibleToken(2002L, true); // custom fees

            final var standardTransfer = buildTokenTransferList(2001L, 1001L, 1002L, 50L);
            final var customFeeTransfer = buildTokenTransferList(2002L, 1003L, 1004L, 100L);

            final var body = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                            .tokenTransfers(standardTransfer, customFeeTransfer)
                            .build())
                    .build();

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Custom fee tier + 1 extra fungible token
            assertThat(result.getServiceTotalTinycents()).isEqualTo(TOKEN_TRANSFER_CUSTOM_FEE + TOKEN_TYPES_EXTRA_FEE);
        }

        @Test
        @DisplayName("Token transfers when simpleFeeContext.feeContext() is null charges TOKEN_TYPES extra")
        void tokenTransferWhenFeeContextIsNull() {
            final var cryptoTransferFeeCalculator = new CryptoTransferFeeCalculator();
            final var txnBody = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                            .tokenTransfers(
                                    buildTokenTransferList(2001L, 1001L, 1002L, 50L),
                                    buildNftTransferList(3001L, 1003L, 1004L, 1L))
                            .build())
                    .build();

            final var mockSimpleFeeContext = org.mockito.Mockito.mock(SimpleFeeContext.class);
            when(mockSimpleFeeContext.feeContext()).thenReturn(null);

            final var feeResult = new FeeResult();
            final var feeSchedule = createTestFeeSchedule();

            cryptoTransferFeeCalculator.accumulateServiceFee(txnBody, mockSimpleFeeContext, feeResult, feeSchedule);

            assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(TOKEN_TYPES_EXTRA_FEE);
        }
    }

    @Nested
    @DisplayName("Extras Tests")
    class ExtrasTests {

        @Test
        @DisplayName("Multiple fungible tokens charge FUNGIBLE_TOKENS extra")
        void multipleFungibleTokensChargeExtra() {
            setupMocksWithTokenStore();
            mockFungibleToken(2001L, false);
            mockFungibleToken(2002L, false);

            final var transfer1 = buildTokenTransferList(2001L, 1001L, 1002L, 50L);
            final var transfer2 = buildTokenTransferList(2002L, 1001L, 1003L, 100L);

            final var body = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                            .tokenTransfers(transfer1, transfer2)
                            .build())
                    .build();

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // TOKEN_TRANSFER_BASE + 1 extra fungible (first included)
            assertThat(result.getServiceTotalTinycents()).isEqualTo(TOKEN_TRANSFER_FEE + TOKEN_TYPES_EXTRA_FEE);
        }

        @Test
        @DisplayName("Hook execution charges HOOK_EXECUTION extra")
        void hookExecutionChargesExtra() {
            setupMocksWithTokenStore();
            mockFungibleToken(2001L, false);

            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(TokenID.newBuilder().tokenNum(2001L).build())
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1001L)
                                            .build())
                                    .amount(-50L)
                                    .preTxAllowanceHook(HookCall.DEFAULT)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1002L)
                                            .build())
                                    .amount(50L)
                                    .build())
                    .build();

            final var body = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                            .tokenTransfers(tokenTransfers)
                            .build())
                    .build();

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // TOKEN_TRANSFER_BASE + 1 hook
            assertThat(result.getServiceTotalTinycents()).isEqualTo(TOKEN_TRANSFER_FEE + HOOK_EXECUTION_FEE);
        }
    }

    // ===== Helper Methods =====

    private void setupMocks() {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
    }

    private void setupMocksWithTokenStore() {
        setupMocks();
        lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
        lenient().when(feeContext.configuration()).thenReturn(HederaTestConfigBuilder.createConfig());
    }

    private void mockFungibleToken(long tokenNum, boolean hasCustomFees) {
        final var tokenId = TokenID.newBuilder().tokenNum(tokenNum).build();
        final var token = Token.newBuilder()
                .tokenId(tokenId)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .customFees(hasCustomFees ? List.of(mock(CustomFee.class)) : List.of())
                .build();
        when(tokenStore.get(tokenId)).thenReturn(token);
    }

    private void mockNftToken(long tokenNum, boolean hasCustomFees) {
        final var tokenId = TokenID.newBuilder().tokenNum(tokenNum).build();
        final var token = Token.newBuilder()
                .tokenId(tokenId)
                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                .customFees(hasCustomFees ? List.of(mock(CustomFee.class)) : List.of())
                .build();
        when(tokenStore.get(tokenId)).thenReturn(token);
    }

    private TransactionBody buildHbarTransfer(long senderNum, long receiverNum, long amount) {
        final var transfers = TransferList.newBuilder()
                .accountAmounts(
                        AccountAmount.newBuilder()
                                .accountID(AccountID.newBuilder()
                                        .accountNum(senderNum)
                                        .build())
                                .amount(-amount)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(AccountID.newBuilder()
                                        .accountNum(receiverNum)
                                        .build())
                                .amount(amount)
                                .build())
                .build();

        return TransactionBody.newBuilder()
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .transfers(transfers)
                        .build())
                .build();
    }

    private TransactionBody buildFungibleTokenTransfer(long tokenNum, long senderNum, long receiverNum, long amount) {
        final var tokenTransfers = buildTokenTransferList(tokenNum, senderNum, receiverNum, amount);
        return TransactionBody.newBuilder()
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .tokenTransfers(tokenTransfers)
                        .build())
                .build();
    }

    private TokenTransferList buildTokenTransferList(long tokenNum, long senderNum, long receiverNum, long amount) {
        return TokenTransferList.newBuilder()
                .token(TokenID.newBuilder().tokenNum(tokenNum).build())
                .transfers(
                        AccountAmount.newBuilder()
                                .accountID(AccountID.newBuilder()
                                        .accountNum(senderNum)
                                        .build())
                                .amount(-amount)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(AccountID.newBuilder()
                                        .accountNum(receiverNum)
                                        .build())
                                .amount(amount)
                                .build())
                .build();
    }

    private TransactionBody buildNftTransfer(long tokenNum, long senderNum, long receiverNum, long serialNumber) {
        final var nftTransfers = buildNftTransferList(tokenNum, senderNum, receiverNum, serialNumber);
        return TransactionBody.newBuilder()
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .tokenTransfers(nftTransfers)
                        .build())
                .build();
    }

    private TokenTransferList buildNftTransferList(long tokenNum, long senderNum, long receiverNum, long serialNumber) {
        return TokenTransferList.newBuilder()
                .token(TokenID.newBuilder().tokenNum(tokenNum).build())
                .nftTransfers(NftTransfer.newBuilder()
                        .senderAccountID(
                                AccountID.newBuilder().accountNum(senderNum).build())
                        .receiverAccountID(
                                AccountID.newBuilder().accountNum(receiverNum).build())
                        .serialNumber(serialNumber)
                        .build())
                .build();
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 1)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(9).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000L),
                        makeExtraDef(Extra.KEYS, 100000000L),
                        makeExtraDef(Extra.STATE_BYTES, 110L),
                        makeExtraDef(Extra.ACCOUNTS, 0L),
                        makeExtraDef(Extra.TOKEN_TYPES, TOKEN_TYPES_EXTRA_FEE),
                        makeExtraDef(Extra.TOKEN_TRANSFER_BASE, TOKEN_TRANSFER_FEE),
                        makeExtraDef(Extra.TOKEN_TRANSFER_BASE_CUSTOM_FEES, TOKEN_TRANSFER_CUSTOM_FEE),
                        makeExtraDef(Extra.HOOK_EXECUTION, HOOK_EXECUTION_FEE))
                .services(makeService(
                        "CryptoTransfer",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_TRANSFER,
                                0L, // HBAR-only transfers have no service fee
                                makeExtraIncluded(Extra.TOKEN_TRANSFER_BASE, 0),
                                makeExtraIncluded(Extra.TOKEN_TRANSFER_BASE_CUSTOM_FEES, 0),
                                makeExtraIncluded(Extra.HOOK_EXECUTION, 0),
                                makeExtraIncluded(Extra.ACCOUNTS, 2),
                                makeExtraIncluded(Extra.TOKEN_TYPES, 1))))
                .build();
    }
}
