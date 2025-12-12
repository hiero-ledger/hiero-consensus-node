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
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl;
import java.util.List;
import java.util.Set;
import org.hiero.hapi.support.fees.*;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CryptoTransferFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class CryptoTransferFeeCalculatorTest {

    @Mock
    private FeeContext feeContext;

    @Mock
    private ReadableTokenStore tokenStore;

    private SimpleFeeCalculatorImpl feeCalculator;
    private FeeSchedule testSchedule;

    @BeforeEach
    void setUp() {
        testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(testSchedule, Set.of(new CryptoTransferFeeCalculator()));
    }

    @Nested
    @DisplayName("Basic HBAR Transfer Tests")
    class BasicHbarTransferTests {
        @Test
        @DisplayName("Simple HBAR transfer between 2 accounts")
        void simpleHbarTransfer() {
            // Given: 2 accounts (sender + receiver)
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);

            final var sender = AccountID.newBuilder().accountNum(1001L).build();
            final var receiver = AccountID.newBuilder().accountNum(1002L).build();

            final var hbarTransfers = TransferList.newBuilder()
                    .accountAmounts(
                            AccountAmount.newBuilder()
                                    .accountID(sender)
                                    .amount(-100L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(receiver)
                                    .amount(100L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .transfers(hbarTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: node=100000, network=900000 (100000*9), service=1000000 (baseFee for HBAR-only)
            // ACCOUNTS extra: 2 accounts, includedCount=2, so 0 extra charge
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.network).isEqualTo(900000L);
            assertThat(result.service).isEqualTo(1000000L);
        }
    }

    @Nested
    @DisplayName("Fungible Token Transfer Tests")
    class FungibleTokenTransferTests {
        @Test
        @DisplayName("Standard fungible token transfer (no custom fees)")
        void standardFungibleTokenTransfer() {
            // Given: 1 unique fungible token (with 2 account adjustments: sender + receiver)
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);

            final var tokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(tokenId)).thenReturn(token);
            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1001L)
                                            .build())
                                    .amount(-50L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1002L)
                                            .build())
                                    .amount(50L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=9000000 (TOKEN_TRANSFER_BASE fee)
            // + 0 for FUNGIBLE_TOKENS (1 token with includedCount=1)
            assertThat(result.service).isEqualTo(9000000L);
        }

        @Test
        @DisplayName("Multiple fungible token transfers")
        void multipleFungibleTokenTransfers() {
            // Given: 2 unique fungible tokens (total 4 account adjustments across both)
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);

            final var token1 = TokenID.newBuilder().tokenNum(2001L).build();
            final var token2 = TokenID.newBuilder().tokenNum(2002L).build();

            final var tokenObj1 = Token.newBuilder()
                    .tokenId(token1)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of())
                    .build();
            final var tokenObj2 = Token.newBuilder()
                    .tokenId(token2)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(token1)).thenReturn(tokenObj1);
            when(tokenStore.get(token2)).thenReturn(tokenObj2);

            final var tokenTransfer1 = TokenTransferList.newBuilder()
                    .token(token1)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1001L)
                                            .build())
                                    .amount(-50L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1002L)
                                            .build())
                                    .amount(50L)
                                    .build())
                    .build();

            final var tokenTransfer2 = TokenTransferList.newBuilder()
                    .token(token2)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1001L)
                                            .build())
                                    .amount(-100L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1003L)
                                            .build())
                                    .amount(100L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfer1, tokenTransfer2)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=9000000 (TOKEN_TRANSFER_BASE fee)
            // + 1000000 * 1 (2 unique fungible tokens, first included in base, second charged)
            assertThat(result.service).isEqualTo(10000000L);
        }
    }

    @Nested
    @DisplayName("Mixed Transfer Tests")
    class MixedTransferTests {
        @Test
        @DisplayName("Mixed HBAR + fungible token transfer")
        void mixedHbarAndFungible() {
            // Given: HBAR + fungible token
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);

            final var hbarTransfers = TransferList.newBuilder()
                    .accountAmounts(
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1001L)
                                            .build())
                                    .amount(-100L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1002L)
                                            .build())
                                    .amount(100L)
                                    .build())
                    .build();

            final var fungibleToken = TokenID.newBuilder().tokenNum(2001L).build();
            final var fungibleTokenObj = Token.newBuilder()
                    .tokenId(fungibleToken)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(fungibleToken)).thenReturn(fungibleTokenObj);

            final var fungibleTransfers = TokenTransferList.newBuilder()
                    .token(fungibleToken)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1003L)
                                            .build())
                                    .amount(-50L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1004L)
                                            .build())
                                    .amount(50L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .transfers(hbarTransfers)
                    .tokenTransfers(fungibleTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=9000000 (TOKEN_TRANSFER_BASE fee)
            // + 0 (1 fungible token, includedCount=1)
            assertThat(result.service).isEqualTo(9000000L);
        }
    }

    @Nested
    @DisplayName("NFT Transfer Tests")
    class NftTransferTests {
        @Test
        @DisplayName("Standard NFT transfer (no custom fees)")
        void standardNftTransfer() {
            // Given: 1 NFT transfer
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);

            final var tokenId = TokenID.newBuilder().tokenNum(3001L).build();
            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(tokenId)).thenReturn(token);

            final var nftTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .nftTransfers(com.hedera.hapi.node.base.NftTransfer.newBuilder()
                            .senderAccountID(AccountID.newBuilder().accountNum(1001L).build())
                            .receiverAccountID(AccountID.newBuilder().accountNum(1002L).build())
                            .serialNumber(1L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(nftTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=9000000 (TOKEN_TRANSFER_BASE fee - same as FT!)
            assertThat(result.service).isEqualTo(9000000L);
        }

        @Test
        @DisplayName("NFT transfer with custom fees")
        void nftTransferWithCustomFees() {
            // Given: 1 NFT with custom fees
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);

            final var tokenId = TokenID.newBuilder().tokenNum(3001L).build();
            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .customFees(List.of(mock(CustomFee.class)))
                    .build();
            when(tokenStore.get(tokenId)).thenReturn(token);

            final var nftTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .nftTransfers(com.hedera.hapi.node.base.NftTransfer.newBuilder()
                            .senderAccountID(AccountID.newBuilder().accountNum(1001L).build())
                            .receiverAccountID(AccountID.newBuilder().accountNum(1002L).build())
                            .serialNumber(1L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(nftTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=19000000 (TOKEN_TRANSFER_BASE_CUSTOM_FEES fee)
            assertThat(result.service).isEqualTo(19000000L);
        }

        @Test
        @DisplayName("Mixed FT + NFT transfer charges single TOKEN_TRANSFER_BASE (not double)")
        void mixedFtAndNftTransferChargesSingleBase() {
            // This is the key test - verifies we don't double-charge for mixed FT+NFT transfers!
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);

            // Fungible token
            final var ftTokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var ftToken = Token.newBuilder()
                    .tokenId(ftTokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(ftTokenId)).thenReturn(ftToken);

            // NFT token
            final var nftTokenId = TokenID.newBuilder().tokenNum(3001L).build();
            final var nftToken = Token.newBuilder()
                    .tokenId(nftTokenId)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(nftTokenId)).thenReturn(nftToken);

            final var ftTransfers = TokenTransferList.newBuilder()
                    .token(ftTokenId)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder().accountNum(1001L).build())
                                    .amount(-50L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder().accountNum(1002L).build())
                                    .amount(50L)
                                    .build())
                    .build();

            final var nftTransfers = TokenTransferList.newBuilder()
                    .token(nftTokenId)
                    .nftTransfers(com.hedera.hapi.node.base.NftTransfer.newBuilder()
                            .senderAccountID(AccountID.newBuilder().accountNum(1003L).build())
                            .receiverAccountID(AccountID.newBuilder().accountNum(1004L).build())
                            .serialNumber(1L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(ftTransfers, nftTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=9000000 (TOKEN_TRANSFER_BASE - SINGLE charge, not 18M!)
            // This verifies the consolidation fix - FT+NFT should not be double-charged
            assertThat(result.service).isEqualTo(9000000L);
        }

        @Test
        @DisplayName("Mixed FT + NFT with custom fees charges single TOKEN_TRANSFER_BASE_CUSTOM_FEES")
        void mixedFtAndNftWithCustomFeesChargesSingleBase() {
            // Verify custom fees also only charge once for mixed transfers
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);

            // Fungible token with custom fees
            final var ftTokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var ftToken = Token.newBuilder()
                    .tokenId(ftTokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of(mock(CustomFee.class)))
                    .build();
            when(tokenStore.get(ftTokenId)).thenReturn(ftToken);

            // NFT token with custom fees
            final var nftTokenId = TokenID.newBuilder().tokenNum(3001L).build();
            final var nftToken = Token.newBuilder()
                    .tokenId(nftTokenId)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .customFees(List.of(mock(CustomFee.class)))
                    .build();
            when(tokenStore.get(nftTokenId)).thenReturn(nftToken);

            final var ftTransfers = TokenTransferList.newBuilder()
                    .token(ftTokenId)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder().accountNum(1001L).build())
                                    .amount(-50L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder().accountNum(1002L).build())
                                    .amount(50L)
                                    .build())
                    .build();

            final var nftTransfers = TokenTransferList.newBuilder()
                    .token(nftTokenId)
                    .nftTransfers(com.hedera.hapi.node.base.NftTransfer.newBuilder()
                            .senderAccountID(AccountID.newBuilder().accountNum(1003L).build())
                            .receiverAccountID(AccountID.newBuilder().accountNum(1004L).build())
                            .serialNumber(1L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(ftTransfers, nftTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=19000000 (TOKEN_TRANSFER_BASE_CUSTOM_FEES - SINGLE charge)
            // + 0 (1 FT, includedCount=1)
            // + 0 (1 NFT, includedCount=1)
            // Custom fees present, so we use the custom fee tier (19M, not 9M)
            assertThat(result.service).isEqualTo(19000000L);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty transfer")
        void emptyTransfer() {
            // Given: Empty transfer (no accounts or tokens)
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);

            final var op = CryptoTransferTransactionBody.newBuilder().build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: HBAR-only transfer with 0 accounts → baseFee = 1M
            // (An empty transfer is still technically HBAR-only since no token transfers)
            assertThat(result.service).isEqualTo(1000000L);
        }
    }

    @Nested
    @DisplayName("Token Store Integration Tests")
    class TokenStoreIntegrationTests {
        @Test
        @DisplayName("Fungible token with custom fees identified from store")
        void fungibleTokenWithCustomFeesFromStore() {
            // Given: Token store returns token with custom fees
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);

            final var tokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of(mock(CustomFee.class))) // Non-empty list indicates custom fees
                    .build();

            when(tokenStore.get(tokenId)).thenReturn(token);

            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1001L)
                                            .build())
                                    .amount(-50L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1002L)
                                            .build())
                                    .amount(50L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=19000000 (TOKEN_TRANSFER_BASE_CUSTOM_FEES fee)
            // + 0 (1 FUNGIBLE_TOKEN, includedCount=1, so first token is free)
            assertThat(result.service).isEqualTo(19000000L);
        }

        @Test
        @DisplayName("Mix of standard and custom fee tokens")
        void mixOfStandardAndCustomFeeTokens() {
            // Given: Multiple tokens with different fee structures
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);

            final var standardTokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var customFeeTokenId = TokenID.newBuilder().tokenNum(2002L).build();

            final var standardToken = Token.newBuilder()
                    .tokenId(standardTokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of()) // No custom fees
                    .build();

            final var customFeeToken = Token.newBuilder()
                    .tokenId(customFeeTokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of(mock(CustomFee.class))) // Non-empty list indicates custom fees
                    .build();

            when(tokenStore.get(standardTokenId)).thenReturn(standardToken);
            when(tokenStore.get(customFeeTokenId)).thenReturn(customFeeToken);

            final var standardTransfer = TokenTransferList.newBuilder()
                    .token(standardTokenId)
                    .transfers(AccountAmount.newBuilder()
                            .accountID(AccountID.newBuilder().accountNum(1001L).build())
                            .amount(-50L)
                            .build())
                    .build();

            final var customFeeTransfer = TokenTransferList.newBuilder()
                    .token(customFeeTokenId)
                    .transfers(AccountAmount.newBuilder()
                            .accountID(AccountID.newBuilder().accountNum(1002L).build())
                            .amount(-100L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(standardTransfer, customFeeTransfer)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: 19000000 (TOKEN_TRANSFER_BASE_CUSTOM_FEES, custom fee takes precedence)
            // + 0 (1 standard fungible, included) + 1000000 (1 custom fee token, includedCount=0)
            assertThat(result.service).isEqualTo(20000000L);
        }
    }

    @Nested
    @DisplayName("Hook Fee Tests")
    class HookFeeTests {
        @Test
        @DisplayName("HBAR transfer with 2 hooks charges base + hook fees")
        void hbarTransferWithHooks() {
            // Given: 2 accounts with preTxAllowanceHook on each
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);

            final var sender = AccountID.newBuilder().accountNum(1001L).build();
            final var receiver = AccountID.newBuilder().accountNum(1002L).build();

            final var hbarTransfers = TransferList.newBuilder()
                    .accountAmounts(
                            AccountAmount.newBuilder()
                                    .accountID(sender)
                                    .amount(-100L)
                                    .preTxAllowanceHook(HookCall.DEFAULT)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(receiver)
                                    .amount(100L)
                                    .preTxAllowanceHook(HookCall.DEFAULT)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .transfers(hbarTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then:
            // - baseFee (HBAR-only): 1,000,000 tinycents
            // - HOOK_EXECUTION: 2 hooks × 50M = 100,000,000 tinycents
            // - Total service fee: 101,000,000 tinycents
            assertThat(result.service).isEqualTo(101_000_000L);
        }

        @Test
        @DisplayName("Mixed transfer with 4 hooks (HBAR + fungible token)")
        void mixedTransferWithMultipleHooks() {
            // Given: HBAR transfer with 2 hooks + fungible token transfer with 2 hooks
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);

            final var sender = AccountID.newBuilder().accountNum(1001L).build();
            final var receiver = AccountID.newBuilder().accountNum(1002L).build();
            final var tokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(tokenId)).thenReturn(token);

            // HBAR transfers with hooks
            final var hbarTransfers = TransferList.newBuilder()
                    .accountAmounts(
                            AccountAmount.newBuilder()
                                    .accountID(sender)
                                    .amount(-100L)
                                    .preTxAllowanceHook(HookCall.DEFAULT)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(receiver)
                                    .amount(100L)
                                    .preTxAllowanceHook(HookCall.DEFAULT)
                                    .build())
                    .build();

            // Fungible token transfer with hooks on sender and receiver
            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(sender)
                                    .amount(-50L)
                                    .preTxAllowanceHook(HookCall.DEFAULT)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(receiver)
                                    .amount(50L)
                                    .preTxAllowanceHook(HookCall.DEFAULT)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .transfers(hbarTransfers)
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then:
            // - TOKEN_TRANSFER_BASE: 9,000,000 tinycents
            // - HOOK_EXECUTION: 4 hooks × 50M = 200,000,000 tinycents
            // - Total service fee: 209,000,000 tinycents
            assertThat(result.service).isEqualTo(209_000_000L);
        }

        @Test
        @DisplayName("Single hook on HBAR transfer")
        void singleHookOnHbarTransfer() {
            // Given: 1 hook on sender only
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);

            final var sender = AccountID.newBuilder().accountNum(1001L).build();
            final var receiver = AccountID.newBuilder().accountNum(1002L).build();

            final var hbarTransfers = TransferList.newBuilder()
                    .accountAmounts(
                            AccountAmount.newBuilder()
                                    .accountID(sender)
                                    .amount(-100L)
                                    .preTxAllowanceHook(HookCall.DEFAULT)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(receiver)
                                    .amount(100L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .transfers(hbarTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then:
            // - baseFee (HBAR-only): 1,000,000 tinycents
            // - HOOK_EXECUTION: 1 hook × 50M = 50,000,000 tinycents
            // - Total service fee: 51,000,000 tinycents
            assertThat(result.service).isEqualTo(51_000_000L);
        }
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
                        makeExtraDef(Extra.BYTES, 110L),
                        makeExtraDef(Extra.ACCOUNTS, 0L),
                        makeExtraDef(Extra.FUNGIBLE_TOKENS, 1000000L),
                        makeExtraDef(Extra.NON_FUNGIBLE_TOKENS, 1000000L),
                        makeExtraDef(Extra.TOKEN_TRANSFER_BASE, 9000000L),
                        makeExtraDef(Extra.TOKEN_TRANSFER_BASE_CUSTOM_FEES, 19000000L),
                        makeExtraDef(Extra.HOOK_EXECUTION, 50000000L))
                .services(makeService(
                        "CryptoTransfer",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_TRANSFER,
                                1000000L, // baseFee for HBAR-only transfers
                                makeExtraIncluded(Extra.TOKEN_TRANSFER_BASE, 0),
                                makeExtraIncluded(Extra.TOKEN_TRANSFER_BASE_CUSTOM_FEES, 0),
                                makeExtraIncluded(Extra.HOOK_EXECUTION, 0),
                                makeExtraIncluded(Extra.ACCOUNTS, 2),
                                makeExtraIncluded(Extra.FUNGIBLE_TOKENS, 1),
                                makeExtraIncluded(Extra.NON_FUNGIBLE_TOKENS, 1))))
                .build();
    }
}
