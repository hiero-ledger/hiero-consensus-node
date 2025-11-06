// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.CalculatorState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.hapi.support.fees.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CryptoCreateFeeCalculator}.
 * Also tests {@link AbstractSimpleFeeCalculator} utility methods through concrete implementation.
 */
@ExtendWith(MockitoExtension.class)
class CryptoCreateFeeCalculatorTest {

    @Mock
    private CalculatorState calculatorState;

    private CryptoCreateFeeCalculator calculator;
    private org.hiero.hapi.support.fees.FeeSchedule testSchedule;

    @BeforeEach
    void setUp() {
        testSchedule = createTestFeeSchedule();
        calculator = new CryptoCreateFeeCalculator(testSchedule);
    }

    @Nested
    @DisplayName("CryptoCreate Fee Calculation Tests")
    class CryptoCreateTests {
        @Test
        @DisplayName("calculateTxFee with no key")
        void calculateTxFeeWithNoKey() {
            // Given
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);
            final var op = CryptoCreateTransactionBody.newBuilder().build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = calculator.calculateTxFee(body, calculatorState);

            // Then: node=1, service=22 (1-1 sigs * 1=0), network=1*2=2
            assertThat(result).isNotNull();
            assertThat(result.node).isEqualTo(1L);
            assertThat(result.service).isEqualTo(22L);
            assertThat(result.network).isEqualTo(2L);
        }

        @Test
        @DisplayName("calculateTxFee with simple ED25519 key")
        void calculateTxFeeWithSimpleKey() {
            // Given
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(2);
            final var key = Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
            final var op = CryptoCreateTransactionBody.newBuilder().key(key).build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = calculator.calculateTxFee(body, calculatorState);

            // Then: node=1, service=22 + (2-1)*1 + 1*1 = 24, network=2
            assertThat(result.node).isEqualTo(1L);
            assertThat(result.service).isEqualTo(24L);
            assertThat(result.network).isEqualTo(2L);
        }

        @Test
        @DisplayName("calculateTxFee with KeyList containing multiple keys")
        void calculateTxFeeWithKeyList() {
            // Given
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(1);
            final var keyList = KeyList.newBuilder()
                    .keys(
                            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build(),
                            Key.newBuilder()
                                    .ecdsaSecp256k1(Bytes.wrap(new byte[33]))
                                    .build(),
                            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build())
                    .build();
            final var key = Key.newBuilder().keyList(keyList).build();
            final var op = CryptoCreateTransactionBody.newBuilder().key(key).build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = calculator.calculateTxFee(body, calculatorState);

            // Then: service=22 + 0 (sigs) + 3*1 (keys) = 25
            assertThat(result.service).isEqualTo(25L);
        }

        @Test
        @DisplayName("calculateTxFee with ThresholdKey")
        void calculateTxFeeWithThresholdKey() {
            // Given
            lenient().when(calculatorState.numTxnSignatures()).thenReturn(3);
            final var thresholdKey = ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap(new byte[32]))
                                            .build(),
                                    Key.newBuilder()
                                            .ecdsaSecp256k1(Bytes.wrap(new byte[33]))
                                            .build(),
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap(new byte[32]))
                                            .build())
                            .build())
                    .build();
            final var key = Key.newBuilder().thresholdKey(thresholdKey).build();
            final var op = CryptoCreateTransactionBody.newBuilder().key(key).build();
            final var body =
                    TransactionBody.newBuilder().cryptoCreateAccount(op).build();

            // When
            final var result = calculator.calculateTxFee(body, calculatorState);

            // Then: service=22 + (3-1)*1 + 3*1 = 27
            assertThat(result.service).isEqualTo(27L);
        }
    }

    // Helper method to create test fee schedule using real production values from simple-fee-schedule.json
    private static org.hiero.hapi.support.fees.FeeSchedule createTestFeeSchedule() {
        return org.hiero.hapi.support.fees.FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder().baseFee(1L).extras(List.of()).build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(makeExtraDef(Extra.SIGNATURES, 1L), makeExtraDef(Extra.KEYS, 1L), makeExtraDef(Extra.BYTES, 1L))
                .services(makeService(
                        "CryptoService",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_CREATE,
                                22L,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 0))))
                .build();
    }
}
