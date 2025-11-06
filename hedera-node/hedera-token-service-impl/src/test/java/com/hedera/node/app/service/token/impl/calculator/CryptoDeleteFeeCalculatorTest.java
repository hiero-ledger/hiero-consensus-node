// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.token.CryptoDeleteTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import java.util.List;
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
 * Unit tests for {@link CryptoDeleteFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class CryptoDeleteFeeCalculatorTest {

    @Mock
    private SimpleFeeCalculator.TxContext txContext;

    @Mock
    private FeeCalculatorFactory feeCalculatorFactory;

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private FeeContext feeContext;

    private CryptoDeleteFeeCalculator calculator;
    private FeeSchedule testSchedule;

    @BeforeEach
    void setUp() {
        testSchedule = createTestFeeSchedule();
        calculator = new CryptoDeleteFeeCalculator(testSchedule);

        // Set up standard mocks with lenient to avoid unnecessary stubbings warnings
        lenient().when(txContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
        lenient().when(feeCalculatorFactory.feeCalculator(any())).thenReturn(feeCalculator);
        lenient().when(feeCalculator.getSimpleFeesSchedule()).thenReturn(testSchedule);
        lenient().when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
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
            lenient().when(feeContext.body()).thenReturn(body);

            // When
            final var result = calculator.calculateTxFee(feeContext);

            // Then: node=1, service=7 (1-1 sigs=0), network=1*2=2
            assertThat(result).isNotNull();
            assertThat(result.node).isEqualTo(1L);
            assertThat(result.service).isEqualTo(7L);
            assertThat(result.network).isEqualTo(2L);
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
            lenient().when(feeContext.body()).thenReturn(body);

            // When
            final var result = calculator.calculateTxFee(feeContext);

            // Then: base service fee only (0-1 gives negative, so 0 overage)
            assertThat(result.service).isEqualTo(7L);
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
            lenient().when(feeContext.body()).thenReturn(body);

            // When
            final var result = calculator.calculateTxFee(feeContext);

            // Then: service=7 + (5-1)*1 = 11
            assertThat(result.service).isEqualTo(11L);
        }
    }

    // Helper method to create test fee schedule using real production values from simple-fee-schedule.json
    // Note: CryptoDelete not in JSON, using pattern from FileDelete (baseFee=7, SIGNATURES included=1)
    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder().baseFee(1L).extras(List.of()).build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(makeExtraDef(Extra.SIGNATURES, 1L))
                .services(makeService(
                        "CryptoService",
                        makeServiceFee(HederaFunctionality.CRYPTO_DELETE, 7L, makeExtraIncluded(Extra.SIGNATURES, 1))))
                .build();
    }
}
