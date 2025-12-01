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
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ReadableTokenRelationStore tokenRelStore;

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

            // Then: node=100000, network=900000 (100000*9), service=0 (base)
            // ACCOUNTS extra: 2 accounts, includedCount=2, so 0 extra charge
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.network).isEqualTo(900000L);
            assertThat(result.service).isEqualTo(0L);
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
            lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
            lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);

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

            // Then: service=10000000 (CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON base fee)
            // + 0 for STANDARD_FUNGIBLE_TOKENS (1 token with includedCount=1)
            // New methodology: charges type-specific base + additional tokens beyond included
            assertThat(result.service).isEqualTo(10000000L);
        }

        @Test
        @DisplayName("Multiple fungible token transfers")
        void multipleFungibleTokenTransfers() {
            // Given: 2 unique fungible tokens (total 4 account adjustments across both)
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
            lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
            lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);

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

            // Then: service=10000000 (CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON base fee)
            // + 1000000 * 1 (2 unique fungible tokens, first included in base, second charged)
            // New methodology: type-specific base + additional tokens beyond included
            assertThat(result.service).isEqualTo(11000000L);
        }
    }

    @Nested
    @DisplayName("NFT Transfer Tests")
    class NftTransferTests {
        @Test
        @DisplayName("Standard NFT transfer (no custom fees)")
        void standardNftTransfer() {
            // Given: 1 standard NFT transfer
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
            lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
            lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);

            final var tokenId = TokenID.newBuilder().tokenNum(3001L).build();
            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(tokenId)).thenReturn(token);
            final var nftTransfer = NftTransfer.newBuilder()
                    .senderAccountID(AccountID.newBuilder().accountNum(1001L).build())
                    .receiverAccountID(AccountID.newBuilder().accountNum(1002L).build())
                    .serialNumber(1L)
                    .build();

            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .nftTransfers(nftTransfer)
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=10000000 (CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE base fee)
            // + 0 (1 STANDARD_NON_FUNGIBLE_TOKEN with includedCount=1)
            // First NFT is covered by base fee
            assertThat(result.service).isEqualTo(10000000L);
        }

        @Test
        @DisplayName("Multiple NFTs from same collection")
        void multipleNftsFromSameCollection() {
            // Given: 3 standard NFT transfers from same collection
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
            lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
            lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);

            final var tokenId = TokenID.newBuilder().tokenNum(3001L).build();
            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(tokenId)).thenReturn(token);

            final var nft1 = NftTransfer.newBuilder()
                    .senderAccountID(AccountID.newBuilder().accountNum(1001L).build())
                    .receiverAccountID(AccountID.newBuilder().accountNum(1002L).build())
                    .serialNumber(1L)
                    .build();
            final var nft2 = NftTransfer.newBuilder()
                    .senderAccountID(AccountID.newBuilder().accountNum(1001L).build())
                    .receiverAccountID(AccountID.newBuilder().accountNum(1002L).build())
                    .serialNumber(2L)
                    .build();
            final var nft3 = NftTransfer.newBuilder()
                    .senderAccountID(AccountID.newBuilder().accountNum(1001L).build())
                    .receiverAccountID(AccountID.newBuilder().accountNum(1002L).build())
                    .serialNumber(3L)
                    .build();

            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .nftTransfers(nft1, nft2, nft3)
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=10000000 (CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE base fee)
            // + 1000000 * 2 (3 NFT serials: 1 included in base, 2 additional @ 1M each)
            assertThat(result.service).isEqualTo(12000000L);
        }
    }

    @Nested
    @DisplayName("Mixed Transfer Tests")
    class MixedTransferTests {
        @Test
        @DisplayName("Mixed HBAR + fungible + NFT transfer")
        void mixedAllTypes() {
            // Given: HBAR + fungible + NFT
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
            lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
            lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);

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

            final var nftToken = TokenID.newBuilder().tokenNum(3001L).build();
            final var nftTokenObj = Token.newBuilder()
                    .tokenId(nftToken)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(nftToken)).thenReturn(nftTokenObj);

            final var nftTransfer = NftTransfer.newBuilder()
                    .senderAccountID(AccountID.newBuilder().accountNum(1005L).build())
                    .receiverAccountID(AccountID.newBuilder().accountNum(1006L).build())
                    .serialNumber(1L)
                    .build();
            final var nftTransfers = TokenTransferList.newBuilder()
                    .token(nftToken)
                    .nftTransfers(nftTransfer)
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .transfers(hbarTransfers)
                    .tokenTransfers(fungibleTransfers, nftTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=10000000 (CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE base fee, NFT takes precedence)
            // + 0*1 (1 unique fungible included in STANDARD_FUNGIBLE_TOKENS) + 0*1 (1 NFT included)
            // New methodology: charges highest-tier base (NFT > fungible), then additional tokens
            assertThat(result.service).isEqualTo(10000000L);
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

            // Then: Only base fee (0 accounts within includedCount=2)
            assertThat(result.service).isEqualTo(0L);
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
            lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
            lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);

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

            // Then: service=20000000 (CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES base)
            // + 1000000 (1 CUSTOM_FEE_FUNGIBLE_TOKEN, includedCount=0)
            assertThat(result.service).isEqualTo(21000000L);
        }

        @Test
        @DisplayName("NFT with custom fees identified from store")
        void nftWithCustomFeesFromStore() {
            // Given: Token store returns NFT token with custom fees
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
            lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
            lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);

            final var tokenId = TokenID.newBuilder().tokenNum(3001L).build();
            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .customFees(List.of(mock(CustomFee.class))) // Non-empty list indicates custom fees
                    .build();

            when(tokenStore.get(tokenId)).thenReturn(token);

            final var nftTransfer = NftTransfer.newBuilder()
                    .senderAccountID(AccountID.newBuilder().accountNum(1001L).build())
                    .receiverAccountID(AccountID.newBuilder().accountNum(1002L).build())
                    .serialNumber(1L)
                    .build();

            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .nftTransfers(nftTransfer)
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=20000000 (CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES base)
            // + 1000000 (1 CUSTOM_FEE_NON_FUNGIBLE_TOKEN, includedCount=0)
            assertThat(result.service).isEqualTo(21000000L);
        }

        @Test
        @DisplayName("Mix of standard and custom fee tokens")
        void mixOfStandardAndCustomFeeTokens() {
            // Given: Multiple tokens with different fee structures
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
            lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
            lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);

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

            // Then: 20000000 (CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES base, custom fee takes precedence)
            // + 0 (1 standard fungible, included) + 1000000 (1 custom fee token, includedCount=0)
            assertThat(result.service).isEqualTo(21000000L);
        }
    }

    @Nested
    @DisplayName("Auto-Association Prediction Tests")
    class AutoAssociationPredictionTests {
        @Test
        @DisplayName("Predicts auto-association when recipient has slots available")
        void predictsAutoAssociationWithAvailableSlots() {
            // Given: All stores available, token without KYC/Freeze, recipient with auto-association slots
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient()
                    .when(feeContext.readableStore(ReadableTokenStore.class))
                    .thenReturn(tokenStore);
            lenient()
                    .when(feeContext.readableStore(ReadableAccountStore.class))
                    .thenReturn(accountStore);
            lenient()
                    .when(feeContext.readableStore(ReadableTokenRelationStore.class))
                    .thenReturn(tokenRelStore);

            final var tokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var recipientId = AccountID.newBuilder().accountNum(1002L).build();

            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of())
                    .build(); // No KYC or freeze keys

            final var recipient = Account.newBuilder()
                    .accountId(recipientId)
                    .maxAutoAssociations(10)
                    .usedAutoAssociations(5)
                    .build();

            when(tokenStore.get(tokenId)).thenReturn(token);
            when(accountStore.getAliasedAccountById(recipientId)).thenReturn(recipient);
            when(tokenRelStore.get(recipientId, tokenId)).thenReturn(null); // No existing relation

            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .transfers(AccountAmount.newBuilder()
                            .accountID(recipientId)
                            .amount(50L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then: service=10000000 (CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON base)
            // + 0 (standard token with includedCount=1) + 0 (auto-association fee is 0)
            assertThat(result.service).isEqualTo(10000000L);
        }

        @Test
        @DisplayName("No auto-association when token has KYC key")
        void noAutoAssociationWhenTokenHasKycKey() {
            // Given: Token with KYC key
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient()
                    .when(feeContext.readableStore(ReadableTokenStore.class))
                    .thenReturn(tokenStore);
            lenient()
                    .when(feeContext.readableStore(ReadableAccountStore.class))
                    .thenReturn(accountStore);
            lenient()
                    .when(feeContext.readableStore(ReadableTokenRelationStore.class))
                    .thenReturn(tokenRelStore);

            final var tokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var recipientId = AccountID.newBuilder().accountNum(1002L).build();

            final var kycKey =
                    Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .kycKey(kycKey) // Has KYC key
                    .customFees(List.of())
                    .build();

            when(tokenStore.get(tokenId)).thenReturn(token);

            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .transfers(AccountAmount.newBuilder()
                            .accountID(recipientId)
                            .amount(50L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then:
            assertThat(result.service).isEqualTo(10000000L);
        }

        @Test
        @DisplayName("No auto-association when token relation exists")
        void noAutoAssociationWhenRelationExists() {
            // Given: Token relation already exists
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient()
                    .when(feeContext.readableStore(ReadableTokenStore.class))
                    .thenReturn(tokenStore);
            lenient()
                    .when(feeContext.readableStore(ReadableAccountStore.class))
                    .thenReturn(accountStore);
            lenient()
                    .when(feeContext.readableStore(ReadableTokenRelationStore.class))
                    .thenReturn(tokenRelStore);

            final var tokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var recipientId = AccountID.newBuilder().accountNum(1002L).build();

            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of())
                    .build();

            final var recipient = Account.newBuilder()
                    .accountId(recipientId)
                    .maxAutoAssociations(10)
                    .usedAutoAssociations(5)
                    .build();

            final var tokenRel = TokenRelation.newBuilder()
                    .tokenId(tokenId)
                    .accountId(recipientId)
                    .build();

            when(tokenStore.get(tokenId)).thenReturn(token);
            when(accountStore.getAliasedAccountById(recipientId)).thenReturn(recipient);
            when(tokenRelStore.get(recipientId, tokenId)).thenReturn(tokenRel); // Existing relation

            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .transfers(AccountAmount.newBuilder()
                            .accountID(recipientId)
                            .amount(50L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then:
            assertThat(result.service).isEqualTo(10000000L);
        }

        @Test
        @DisplayName("No auto-association when account has no slots")
        void noAutoAssociationWhenNoSlots() {
            // Given: Account with no available auto-association slots
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient()
                    .when(feeContext.readableStore(ReadableTokenStore.class))
                    .thenReturn(tokenStore);
            lenient()
                    .when(feeContext.readableStore(ReadableAccountStore.class))
                    .thenReturn(accountStore);
            lenient()
                    .when(feeContext.readableStore(ReadableTokenRelationStore.class))
                    .thenReturn(tokenRelStore);

            final var tokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var recipientId = AccountID.newBuilder().accountNum(1002L).build();

            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of())
                    .build();

            final var recipient = Account.newBuilder()
                    .accountId(recipientId)
                    .maxAutoAssociations(10)
                    .usedAutoAssociations(10)
                    .build();

            when(tokenStore.get(tokenId)).thenReturn(token);
            when(accountStore.getAliasedAccountById(recipientId)).thenReturn(recipient);
            when(tokenRelStore.get(recipientId, tokenId)).thenReturn(null);

            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .transfers(AccountAmount.newBuilder()
                            .accountID(recipientId)
                            .amount(50L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then
            assertThat(result.service).isEqualTo(10000000L);
        }
    }

    @Nested
    @DisplayName("Hollow Account Prediction Tests")
    class HollowAccountPredictionTests {
        @Test
        @DisplayName("Predicts hollow account creation for HBAR transfer to alias")
        void predictsHollowAccountForHbarTransferToAlias() {
            // Given: HBAR transfer to alias that doesn't exist
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient()
                    .when(feeContext.readableStore(ReadableAccountStore.class))
                    .thenReturn(accountStore);

            final var aliasBytes = Bytes.wrap(new byte[20]); // Some alias
            final var aliasAccountId = AccountID.newBuilder().alias(aliasBytes).build();

            when(accountStore.getAliasedAccountById(aliasAccountId)).thenReturn(null); // No account exists

            final var hbarTransfers = TransferList.newBuilder()
                    .accountAmounts(AccountAmount.newBuilder()
                            .accountID(aliasAccountId)
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
            assertThat(result.service).isEqualTo(0L);
        }

        @Test
        @DisplayName("Predicts hollow account for token transfer to alias")
        void predictsHollowAccountForTokenTransferToAlias() {
            // Given: Token transfer to alias that doesn't exist
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
            lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);

            final var tokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var aliasBytes = Bytes.wrap(new byte[20]);
            final var aliasAccountId = AccountID.newBuilder().alias(aliasBytes).build();

            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(tokenId)).thenReturn(token);
            when(accountStore.getAliasedAccountById(aliasAccountId)).thenReturn(null);

            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .transfers(AccountAmount.newBuilder()
                            .accountID(aliasAccountId)
                            .amount(50L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then:
            assertThat(result.service).isEqualTo(10000000L);
        }

        @Test
        @DisplayName("Predicts hollow account for NFT transfer receiver with alias")
        void predictsHollowAccountForNftReceiver() {
            // Given: NFT transfer to alias that doesn't exist
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
            lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);

            final var tokenId = TokenID.newBuilder().tokenNum(3001L).build();
            final var senderAccountId = AccountID.newBuilder().accountNum(1001L).build();
            final var aliasBytes = Bytes.wrap(new byte[20]);
            final var receiverAliasAccountId =
                    AccountID.newBuilder().alias(aliasBytes).build();

            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .customFees(List.of())
                    .build();
            when(tokenStore.get(tokenId)).thenReturn(token);
            lenient()
                    .when(accountStore.getAliasedAccountById(senderAccountId))
                    .thenReturn(Account.newBuilder().accountId(senderAccountId).build());
            when(accountStore.getAliasedAccountById(receiverAliasAccountId)).thenReturn(null);

            final var nftTransfer = NftTransfer.newBuilder()
                    .senderAccountID(senderAccountId)
                    .receiverAccountID(receiverAliasAccountId)
                    .serialNumber(1L)
                    .build();

            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .nftTransfers(nftTransfer)
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then:
            assertThat(result.service).isEqualTo(10000000L);
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
            // - CRYPTO_TRANSFER_WITH_HOOKS base: 50,000,000 tinycents
            // - HOOKS extra: 2 hooks × 10B = 20,000,000,000 tinycents
            // - Total service fee: 20,050,000,000 tinycents
            assertThat(result.service).isEqualTo(20_050_000_000L);
        }

        @Test
        @DisplayName("Mixed transfer with 4 hooks (HBAR + NFT sender/receiver)")
        void mixedTransferWithMultipleHooks() {
            // Given: HBAR transfer with 2 hooks + NFT transfer with sender/receiver hooks
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
            lenient().when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
            lenient().when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelStore);

            final var sender = AccountID.newBuilder().accountNum(1001L).build();
            final var receiver = AccountID.newBuilder().accountNum(1002L).build();
            final var tokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var token = Token.newBuilder()
                    .tokenId(tokenId)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
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

            // NFT transfer with sender and receiver hooks
            final var nftTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
                    .nftTransfers(NftTransfer.newBuilder()
                            .senderAccountID(sender)
                            .receiverAccountID(receiver)
                            .serialNumber(1L)
                            .preTxSenderAllowanceHook(HookCall.DEFAULT)
                            .preTxReceiverAllowanceHook(HookCall.DEFAULT)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .transfers(hbarTransfers)
                    .tokenTransfers(nftTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, feeContext);

            // Then:
            // - CRYPTO_TRANSFER_WITH_HOOKS base: 50,000,000 tinycents
            // - HOOKS: 4 hooks × 10B = 40,000,000,000 tinycents
            // - Total service fee: 40,050,000,000 tinycents
            assertThat(result.service).isEqualTo(40_050_000_000L);
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
            // - CRYPTO_TRANSFER_WITH_HOOKS base: 50,000,000 tinycents
            // - HOOKS extra: 1 hook × 10,000,000,000 = 10,000,000,000 tinycents
            // - Total service fee: 10,050,000,000 tinycents (~$1.005)
            assertThat(result.service).isEqualTo(10_050_000_000L);
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
                        makeExtraDef(Extra.STANDARD_FUNGIBLE_TOKENS, 1000000L),
                        makeExtraDef(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 1000000L),
                        makeExtraDef(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 1000000L),
                        makeExtraDef(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 1000000L),
                        makeExtraDef(Extra.CREATED_AUTO_ASSOCIATIONS, 0L),
                        makeExtraDef(Extra.CREATED_ACCOUNTS, 0L),
                        makeExtraDef(Extra.HOOKS, 10000000000L),
                        makeExtraDef(Extra.CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON, 10000000L),
                        makeExtraDef(Extra.CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE, 10000000L),
                        makeExtraDef(Extra.CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES, 20000000L),
                        makeExtraDef(Extra.CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES, 20000000L),
                        makeExtraDef(Extra.CRYPTO_TRANSFER_WITH_HOOKS, 50000000L))
                .services(makeService(
                        "CryptoTransfer",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_TRANSFER,
                                0L,
                                makeExtraIncluded(Extra.CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON, 0),
                                makeExtraIncluded(Extra.CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE, 0),
                                makeExtraIncluded(Extra.CRYPTO_TRANSFER_TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES, 0),
                                makeExtraIncluded(Extra.CRYPTO_TRANSFER_TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES, 0),
                                makeExtraIncluded(Extra.CRYPTO_TRANSFER_WITH_HOOKS, 0),
                                makeExtraIncluded(Extra.HOOKS, 0),
                                makeExtraIncluded(Extra.ACCOUNTS, 2),
                                makeExtraIncluded(Extra.STANDARD_FUNGIBLE_TOKENS, 1),
                                makeExtraIncluded(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 1),
                                makeExtraIncluded(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(Extra.CREATED_AUTO_ASSOCIATIONS, 0),
                                makeExtraIncluded(Extra.CREATED_ACCOUNTS, 0))))
                .build();
    }
}
