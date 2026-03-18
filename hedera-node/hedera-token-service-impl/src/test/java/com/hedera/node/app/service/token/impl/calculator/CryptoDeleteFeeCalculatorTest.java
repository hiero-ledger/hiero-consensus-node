// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.token.CryptoDeleteTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.context.SimpleFeeContextImpl;
import com.hedera.node.app.spi.fees.FeeContext;
import java.util.List;
import java.util.Set;
import org.hiero.hapi.support.fees.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CryptoDeleteFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class CryptoDeleteFeeCalculatorTest {

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;
    private FeeSchedule testSchedule;

    @BeforeEach
    void setUp() {
        testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(testSchedule, Set.of(new CryptoDeleteFeeCalculator()));
        when(feeContext.functionality()).thenReturn(HederaFunctionality.CRYPTO_DELETE);
    }

    @Nested
    @DisplayName("CryptoDelete Fee Calculation Tests")
    class CryptoDeleteTests {
        @Test
        @DisplayName("calculateTxFee with basic delete operation")
        void calculateTxFeeBasicDelete() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var op = CryptoDeleteTransactionBody.newBuilder()
                    .deleteAccountID(AccountID.newBuilder().accountNum(1001).build())
                    .transferAccountID(AccountID.newBuilder().accountNum(1002).build())
                    .build();
            final var body = TransactionBody.newBuilder().cryptoDelete(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Then: Real production values from simpleFeesSchedules.json
            // node=100000, network=200000, service=49850000 (base only, no extras)
            assertThat(result).isNotNull();
            assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            assertThat(result.getServiceTotalTinycents()).isEqualTo(49850000L);
            assertThat(result.getNetworkTotalTinycents()).isEqualTo(200000L);
        }

        @Test
        @DisplayName("calculateTxFee with zero signatures")
        void calculateTxFeeWithZeroSignatures() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(0);
            final var op = CryptoDeleteTransactionBody.newBuilder()
                    .deleteAccountID(AccountID.newBuilder().accountNum(1001).build())
                    .build();
            final var body = TransactionBody.newBuilder().cryptoDelete(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Then: Base service fee only - CryptoDelete doesn't call addExtraFee
            // service=49850000
            assertThat(result.getServiceTotalTinycents()).isEqualTo(49850000L);
        }

        @Test
        @DisplayName("calculateTxFee with multiple signatures")
        void calculateTxFeeWithMultipleSignatures() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(5);
            final var op = CryptoDeleteTransactionBody.newBuilder()
                    .deleteAccountID(AccountID.newBuilder().accountNum(1001).build())
                    .build();
            final var body = TransactionBody.newBuilder().cryptoDelete(op).build();

            // When
            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

            // Then: Base service fee only - CryptoDelete doesn't call addExtraFee
            // service=49850000 (SIGNATURES are handled by node fee, not service fee)
            assertThat(result.getServiceTotalTinycents()).isEqualTo(49850000L);
        }
    }

    // Helper method to create test fee schedule using real production values from simpleFeesSchedules.json
    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 10)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(makeExtraDef(Extra.SIGNATURES, 1000000L))
                .services(makeService(
                        "CryptoService",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_DELETE, 49850000L, makeExtraIncluded(Extra.SIGNATURES, 1))))
                .build();
    }
}
