// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.token.CryptoDeleteAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftRemoveAllowance;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeContextUtil;
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
 * Unit tests for {@link CryptoDeleteAllowanceFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class CryptoDeleteAllowanceFeeCalculatorTest {

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        final var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(testSchedule, Set.of(new CryptoDeleteAllowanceFeeCalculator()));
    }

    @Nested
    @DisplayName("CryptoDeleteAllowance Fee Calculation Tests")
    class CryptoDeleteAllowanceTests {
        @Test
        @DisplayName("calculateTxFee with single NFT allowance")
        void calculateTxFeeWithSingleNftAllowance() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var allowance = NftRemoveAllowance.newBuilder()
                    .tokenId(TokenID.newBuilder().tokenNum(123).build())
                    .owner(AccountID.newBuilder().accountNum(456).build())
                    .serialNumbers(1L, 2L, 3L)
                    .build();
            final var op = CryptoDeleteAllowanceTransactionBody.newBuilder()
                    .nftAllowances(allowance)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoDeleteAllowance(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee (500000000) with 1 allowance included (includedCount=1)
            assertThat(result.getServiceTotalTinycents()).isEqualTo(500000000L);
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
        }

        @Test
        @DisplayName("calculateTxFee with multiple NFT allowances")
        void calculateTxFeeWithMultipleNftAllowances() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var allowance1 = NftRemoveAllowance.newBuilder()
                    .tokenId(TokenID.newBuilder().tokenNum(123).build())
                    .owner(AccountID.newBuilder().accountNum(456).build())
                    .serialNumbers(1L)
                    .build();
            final var allowance2 = NftRemoveAllowance.newBuilder()
                    .tokenId(TokenID.newBuilder().tokenNum(789).build())
                    .owner(AccountID.newBuilder().accountNum(456).build())
                    .serialNumbers(2L, 3L)
                    .build();
            final var allowance3 = NftRemoveAllowance.newBuilder()
                    .tokenId(TokenID.newBuilder().tokenNum(999).build())
                    .owner(AccountID.newBuilder().accountNum(456).build())
                    .serialNumbers(4L, 5L, 6L)
                    .build();
            final var op = CryptoDeleteAllowanceTransactionBody.newBuilder()
                    .nftAllowances(allowance1, allowance2, allowance3)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoDeleteAllowance(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee (500000000) + 2 extra allowances (2 * 500000000 = 1000000000)
            assertThat(result.getServiceTotalTinycents()).isEqualTo(1500000000L);
        }

        @Test
        @DisplayName("calculateTxFee with many NFT allowances")
        void calculateTxFeeWithManyNftAllowances() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var allowances = new NftRemoveAllowance[10];
            for (int i = 0; i < 10; i++) {
                allowances[i] = NftRemoveAllowance.newBuilder()
                        .tokenId(TokenID.newBuilder().tokenNum(100 + i).build())
                        .owner(AccountID.newBuilder().accountNum(456).build())
                        .serialNumbers((long) i)
                        .build();
            }
            final var op = CryptoDeleteAllowanceTransactionBody.newBuilder()
                    .nftAllowances(allowances)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoDeleteAllowance(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee (500000000) + 9 extra allowances (9 * 500000000 = 4500000000)
            assertThat(result.getServiceTotalTinycents()).isEqualTo(5000000000L);
        }

        @Test
        @DisplayName("verify getTransactionType returns CRYPTO_DELETE_ALLOWANCE")
        void verifyTransactionType() {
            // Given
            final var calculator = new CryptoDeleteAllowanceFeeCalculator();

            // When
            final var txnType = calculator.getTransactionType();

            // Then
            assertThat(txnType).isEqualTo(TransactionBody.DataOneOfType.CRYPTO_DELETE_ALLOWANCE);
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
                        makeExtraDef(Extra.ALLOWANCES, 500000000L),
                        makeExtraDef(Extra.BYTES, 110000L))
                .services(makeService(
                        "CryptoService",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_DELETE_ALLOWANCE,
                                500000000L,
                                makeExtraIncluded(Extra.ALLOWANCES, 1))))
                .build();
    }
}
