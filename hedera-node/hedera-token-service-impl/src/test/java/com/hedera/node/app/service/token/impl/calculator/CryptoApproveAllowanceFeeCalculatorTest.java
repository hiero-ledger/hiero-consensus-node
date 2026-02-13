// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
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
 * Unit tests for {@link CryptoApproveAllowanceFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class CryptoApproveAllowanceFeeCalculatorTest {

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        final var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(testSchedule, Set.of(new CryptoApproveAllowanceFeeCalculator()));
    }

    @Nested
    @DisplayName("CryptoApproveAllowance Fee Calculation Tests")
    class CryptoApproveAllowanceTests {
        @Test
        @DisplayName("calculateTxFee with single crypto allowance")
        void calculateTxFeeWithSingleCryptoAllowance() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var allowance = CryptoAllowance.newBuilder()
                    .spender(AccountID.newBuilder().accountNum(123).build())
                    .amount(1000)
                    .build();
            final var op = CryptoApproveAllowanceTransactionBody.newBuilder()
                    .cryptoAllowances(allowance)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoApproveAllowance(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee (500000000) with 1 allowance included (includedCount=1)
            assertThat(result.getServiceTotalTinycents()).isEqualTo(500000000L);
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(900000L);
        }

        @Test
        @DisplayName("calculateTxFee with multiple crypto allowances")
        void calculateTxFeeWithMultipleCryptoAllowances() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var allowance1 = CryptoAllowance.newBuilder()
                    .spender(AccountID.newBuilder().accountNum(123).build())
                    .amount(1000)
                    .build();
            final var allowance2 = CryptoAllowance.newBuilder()
                    .spender(AccountID.newBuilder().accountNum(456).build())
                    .amount(2000)
                    .build();
            final var allowance3 = CryptoAllowance.newBuilder()
                    .spender(AccountID.newBuilder().accountNum(789).build())
                    .amount(3000)
                    .build();
            final var op = CryptoApproveAllowanceTransactionBody.newBuilder()
                    .cryptoAllowances(allowance1, allowance2, allowance3)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoApproveAllowance(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee (500000000) + 2 extra allowances (2 * 500000000 = 1000000000)
            assertThat(result.getServiceTotalTinycents()).isEqualTo(1500000000L);
        }

        @Test
        @DisplayName("calculateTxFee with mixed allowance types")
        void calculateTxFeeWithMixedAllowanceTypes() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var cryptoAllowance = CryptoAllowance.newBuilder()
                    .spender(AccountID.newBuilder().accountNum(123).build())
                    .amount(1000)
                    .build();
            final var tokenAllowance = TokenAllowance.newBuilder()
                    .tokenId(TokenID.newBuilder().tokenNum(456).build())
                    .spender(AccountID.newBuilder().accountNum(789).build())
                    .amount(5000)
                    .build();
            final var nftAllowance = NftAllowance.newBuilder()
                    .tokenId(TokenID.newBuilder().tokenNum(111).build())
                    .spender(AccountID.newBuilder().accountNum(222).build())
                    .build();
            final var op = CryptoApproveAllowanceTransactionBody.newBuilder()
                    .cryptoAllowances(cryptoAllowance)
                    .tokenAllowances(tokenAllowance)
                    .nftAllowances(nftAllowance)
                    .build();
            final var body =
                    TransactionBody.newBuilder().cryptoApproveAllowance(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));

            // Then: Base fee (500000000) + 2 extra allowances (2 * 500000000 = 1000000000)
            // Total allowances = 1 crypto + 1 token + 1 NFT = 3, so 2 extras
            assertThat(result.getServiceTotalTinycents()).isEqualTo(1500000000L);
        }

        @Test
        @DisplayName("verify getTransactionType returns CRYPTO_APPROVE_ALLOWANCE")
        void verifyTransactionType() {
            // Given
            final var calculator = new CryptoApproveAllowanceFeeCalculator();

            // When
            final var txnType = calculator.getTransactionType();

            // Then
            assertThat(txnType).isEqualTo(TransactionBody.DataOneOfType.CRYPTO_APPROVE_ALLOWANCE);
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
                                HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE,
                                500000000L,
                                makeExtraIncluded(Extra.ALLOWANCES, 1))))
                .build();
    }
}
