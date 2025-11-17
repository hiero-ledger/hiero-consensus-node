// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.CalculatorState;
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
    private CalculatorState calculatorState;

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
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

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
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: node=100000, network=200000, service=18 (base)
            // ACCOUNTS extra: 2 accounts, includedCount=2, so 0 extra charge
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.network).isEqualTo(200000L);
            assertThat(result.service).isEqualTo(18L);
        }

        @Test
        @DisplayName("HBAR transfer with 5 accounts")
        void hbarTransferWithMultipleAccounts() {
            // Given: 5 accounts
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

            final var hbarTransfers = TransferList.newBuilder()
                    .accountAmounts(
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1001L)
                                            .build())
                                    .amount(-200L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1002L)
                                            .build())
                                    .amount(50L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1003L)
                                            .build())
                                    .amount(50L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1004L)
                                            .build())
                                    .amount(50L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(AccountID.newBuilder()
                                            .accountNum(1005L)
                                            .build())
                                    .amount(50L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .transfers(hbarTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: service=18 (base) + 0 * 3 (3 extra accounts beyond includedCount=2)
            // ACCOUNTS fee is 0, so same as 2 accounts
            assertThat(result.service).isEqualTo(18L);
        }
    }

    @Nested
    @DisplayName("Fungible Token Transfer Tests")
    class FungibleTokenTransferTests {
        @Test
        @DisplayName("Standard fungible token transfer (no custom fees)")
        void standardFungibleTokenTransfer() {
            // Given: 1 unique fungible token (with 2 account adjustments: sender + receiver)
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

            final var tokenId = TokenID.newBuilder().tokenNum(2001L).build();
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
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: service=18 (base) + 9000000 * 1 (1 unique fungible token)
            // New methodology counts unique tokens, not AccountAmount entries
            assertThat(result.service).isEqualTo(18L + 9000000L);
        }

        @Test
        @DisplayName("Multiple fungible token transfers")
        void multipleFungibleTokenTransfers() {
            // Given: 2 unique fungible tokens (total 4 account adjustments across both)
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

            final var token1 = TokenID.newBuilder().tokenNum(2001L).build();
            final var token2 = TokenID.newBuilder().tokenNum(2002L).build();

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
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: service=18 (base) + 9000000 * 2 (2 unique fungible tokens)
            // New methodology counts unique tokens, not AccountAmount entries
            assertThat(result.service).isEqualTo(18L + 9000000L * 2);
        }
    }

    @Nested
    @DisplayName("NFT Transfer Tests")
    class NftTransferTests {
        @Test
        @DisplayName("Standard NFT transfer (no custom fees)")
        void standardNftTransfer() {
            // Given: 1 standard NFT transfer
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

            final var tokenId = TokenID.newBuilder().tokenNum(3001L).build();
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
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: service=18 (base) + 9000000 (1 STANDARD_NON_FUNGIBLE_TOKEN)
            // Updated from 100000 to 9000000 to match canonical price of $0.001
            assertThat(result.service).isEqualTo(18L + 9000000L);
        }

        @Test
        @DisplayName("Multiple NFTs from same collection")
        void multipleNftsFromSameCollection() {
            // Given: 3 standard NFT transfers from same collection
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

            final var tokenId = TokenID.newBuilder().tokenNum(3001L).build();
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
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: service=18 (base) + 9000000 * 3 (3 STANDARD_NON_FUNGIBLE_TOKENs)
            // Updated from 100000 to 9000000 to match canonical price of $0.001 per NFT
            assertThat(result.service).isEqualTo(18L + 9000000L * 3);
        }
    }

    @Nested
    @DisplayName("Mixed Transfer Tests")
    class MixedTransferTests {
        @Test
        @DisplayName("Mixed HBAR + fungible token transfer")
        void mixedHbarAndFungible() {
            // Given: HBAR transfer + 1 fungible token (2 adjustments)
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

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

            final var tokenId = TokenID.newBuilder().tokenNum(2001L).build();
            final var tokenTransfers = TokenTransferList.newBuilder()
                    .token(tokenId)
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
                    .tokenTransfers(tokenTransfers)
                    .build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: service=18 (base) + 9000000 * 1 (1 unique fungible token)
            // New methodology counts unique tokens, not AccountAmount entries
            // Note: 4 unique accounts, but ACCOUNTS includedCount=2, fee=0 so no charge
            assertThat(result.service).isEqualTo(18L + 9000000L);
        }

        @Test
        @DisplayName("Mixed HBAR + fungible + NFT transfer")
        void mixedAllTypes() {
            // Given: HBAR + fungible + NFT
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

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
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: service=18 + 9000000*1 (1 unique fungible) + 9000000*1 (1 NFT)
            // New methodology counts unique tokens, updated NFT fee to match canonical price
            assertThat(result.service).isEqualTo(18L + 9000000L + 9000000L);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {
        @Test
        @DisplayName("Null calculator state uses base fee only")
        void nullCalculatorState() {
            // Given: null calculator state (no state access)
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
            final var result = feeCalculator.calculateTxFee(body, null);

            // Then: Base fees + charges for transfers
            // node=100000, network=200000, service=18 (base)
            assertThat(result.node).isEqualTo(100000L);
            assertThat(result.network).isEqualTo(200000L);
            assertThat(result.service).isEqualTo(18L);
        }

        @Test
        @DisplayName("Empty transfer")
        void emptyTransfer() {
            // Given: Empty transfer (no accounts or tokens)
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);

            final var op = CryptoTransferTransactionBody.newBuilder().build();
            final var body = TransactionBody.newBuilder().cryptoTransfer(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, calculatorState);

            // Then: Only base fee (0 accounts within includedCount=2)
            assertThat(result.service).isEqualTo(18L);
        }
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 10)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000L),
                        makeExtraDef(Extra.KEYS, 100000000L),
                        makeExtraDef(Extra.BYTES, 110L),
                        makeExtraDef(Extra.ACCOUNTS, 0L),
                        makeExtraDef(Extra.STANDARD_FUNGIBLE_TOKENS, 9000000L),
                        makeExtraDef(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 9000000L), // Updated to match canonical price
                        makeExtraDef(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 19000000L),
                        makeExtraDef(
                                Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 19000000L), // Updated to match canonical price
                        makeExtraDef(Extra.CREATED_AUTO_ASSOCIATIONS, 0L),
                        makeExtraDef(Extra.CREATED_ACCOUNTS, 0L))
                .services(makeService(
                        "CryptoService",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_TRANSFER,
                                18L,
                                makeExtraIncluded(Extra.ACCOUNTS, 2),
                                makeExtraIncluded(Extra.STANDARD_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(Extra.STANDARD_NON_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(Extra.CUSTOM_FEE_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(Extra.CREATED_AUTO_ASSOCIATIONS, 0),
                                makeExtraIncluded(Extra.CREATED_ACCOUNTS, 0))))
                .build();
    }
}
